#include "http.h"
#include <algorithm>
#pragma comment(lib, "winhttp.lib")

namespace md {

// ---------- 속도 제한: 방향마다 하나의 통 (모든 전송이 나눠 씀) ----------
static std::atomic<int64_t> g_lim[2];
static std::mutex g_limMu[2];
static double g_next[2];   // 이 시각(ms)까지는 이미 보낸 양으로 꽉 참
void SetRateLimit(int64_t upBps, int64_t downBps) { g_lim[0] = upBps; g_lim[1] = downBps; }
int64_t UpRateLimit() { return g_lim[0]; }
static void Throttle(int dir, int64_t n, const std::atomic<bool>& cancel) {
    int64_t lim = g_lim[dir];
    if (lim <= 0 || n <= 0) return;
    double wait;
    {
        std::lock_guard<std::mutex> g(g_limMu[dir]);
        double now = (double)GetTickCount64();
        if (g_next[dir] < now - 500) g_next[dir] = now - 500;   // 쉬던 동안 0.5초어치만 모아 둠
        g_next[dir] += n * 1000.0 / lim;
        wait = g_next[dir] - now;
    }
    while (wait >= 1 && !cancel) { DWORD s = (DWORD)std::min(wait, 200.0); Sleep(s); wait -= s; }
}

thread_local const std::atomic<bool>* t_xferStop = nullptr;
static bool XferStopped() { return t_xferStop && t_xferStop->load(); }

Http::Http(const std::wstring& baseUrl, const std::string& token) : token_(token) {
    URL_COMPONENTS uc{sizeof uc};
    wchar_t host[256] = {}, path[1024] = {};
    uc.lpszHostName = host; uc.dwHostNameLength = 256;
    uc.lpszUrlPath = path; uc.dwUrlPathLength = 1024;
    WinHttpCrackUrl(baseUrl.c_str(), 0, 0, &uc);
    https_ = uc.nScheme == INTERNET_SCHEME_HTTPS;
    basePath_ = path;
    if (!basePath_.empty() && basePath_.back() == L'/') basePath_.pop_back();
    ses_ = WinHttpOpen(L"Mydrive/0.1", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    // 같은 서버에 여러 연결을 열어 두고 다시 씀 (왕복을 줄임)
    DWORD maxc = 16;
    WinHttpSetOption(ses_, WINHTTP_OPTION_MAX_CONNS_PER_SERVER, &maxc, sizeof maxc);
    con_ = WinHttpConnect(ses_, host, uc.nPort, 0);
}
Http::~Http() {
    if (con_) WinHttpCloseHandle(con_);
    if (ses_) WinHttpCloseHandle(ses_);
}

void Http::Close(HINTERNET h) {
    std::lock_guard<std::mutex> g(mu_);
    if (live_.erase(h)) WinHttpCloseHandle(h);
}
// 다른 스레드에서: 진행 중인 요청을 모두 끊음 (기다리는 long-poll 포함)
void Http::Abort() {
    cancel = true;
    std::lock_guard<std::mutex> g(mu_);
    for (HINTERNET h : live_) WinHttpCloseHandle(h);
    live_.clear();
}

std::string Http::Enc(const std::string& s) {
    static const char* hex = "0123456789ABCDEF";
    std::string o;
    for (unsigned char c : s) {
        if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') o += (char)c;
        else { o += '%'; o += hex[c >> 4]; o += hex[c & 15]; }
    }
    return o;
}

HINTERNET Http::Open(const wchar_t* method, const std::string& path, const std::map<std::string, std::string>& q, int timeoutSec) {
    std::string url = U(basePath_) + path;
    char sep = '?';
    for (auto& [k, v] : q) { url += sep; url += Enc(k); url += '='; url += Enc(v); sep = '&'; }
    HINTERNET h = WinHttpOpenRequest(con_, method, W(url).c_str(), nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                     https_ ? WINHTTP_FLAG_SECURE : 0);
    if (!h) return nullptr;
    { std::lock_guard<std::mutex> g(mu_); live_.insert(h); }
    if (cancel) { Close(h); SetLastError(ERROR_WINHTTP_OPERATION_CANCELLED); return nullptr; }
    int ms = timeoutSec * 1000;
    WinHttpSetTimeouts(h, 10000, 10000, ms, ms);
    // 서버 관리 화면에 이 PC 이름이 보이게 (한글은 %XX 로)
    static const std::string pc = [] { wchar_t n[256]; DWORD k = 256; return GetComputerNameExW(ComputerNamePhysicalDnsHostname, n, &k) ? Enc(U(n)) : std::string(); }();
    std::wstring hdr = L"Accept-Encoding: gzip\r\n";
    if (!plain) {   // 우리 NAS 서버에만: 토큰과 PC 이름 (GitHub 같은 바깥 서버에는 보내지 않음)
        hdr += L"Authorization: Bearer " + W(token_) + L"\r\n";
        if (!pc.empty()) hdr += L"X-Mydrive-PC: " + W(pc) + L"\r\n";
    }
    WinHttpAddRequestHeaders(h, hdr.c_str(), (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);
    DWORD dec = WINHTTP_DECOMPRESSION_FLAG_ALL;
    WinHttpSetOption(h, WINHTTP_OPTION_DECOMPRESSION, &dec, sizeof dec);
    return h;
}

static std::string ErrText(DWORD e) {
    switch (e) {
    case ERROR_WINHTTP_CANNOT_CONNECT: return "서버에 연결할 수 없음";
    case ERROR_WINHTTP_TIMEOUT: return "응답 시간 초과";
    case ERROR_WINHTTP_CONNECTION_ERROR: return "연결이 끊김";
    case ERROR_WINHTTP_NAME_NOT_RESOLVED: return "서버 이름을 찾을 수 없음";
    }
    return "통신 오류 " + std::to_string(e);
}

Resp Http::Finish(HINTERNET h, HANDLE outFile, std::atomic<int64_t>* progress) {
    Resp r;
    if (!WinHttpReceiveResponse(h, nullptr)) { r.error = ErrText(GetLastError()); Close(h); return r; }
    DWORD st = 0, sz = sizeof st;
    WinHttpQueryHeaders(h, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &st, &sz, nullptr);
    r.status = (int)st;
    wchar_t eh[128]; DWORD ehl = sizeof eh;
    if (WinHttpQueryHeaders(h, WINHTTP_QUERY_CUSTOM, L"X-Mydrive-Entry", eh, &ehl, nullptr)) r.entryHdr = U(eh);
    bool toFile = outFile != INVALID_HANDLE_VALUE && r.ok();
    std::vector<char> buf(1 << 20);
    for (;;) {
        if (cancel || XferStopped()) { r.status = 0; r.error = "취소됨"; break; }
        DWORD got = 0;
        if (!WinHttpReadData(h, buf.data(), (DWORD)buf.size(), &got)) { r.status = 0; r.error = ErrText(GetLastError()); break; }
        if (got == 0) break;
        if (toFile) {
            DWORD w; if (!WriteFile(outFile, buf.data(), got, &w, nullptr) || w != got) { r.status = 0; r.error = "디스크 쓰기 실패"; break; }
            if (progress) *progress += got;
            Throttle(1, got, cancel);
        } else r.body.append(buf.data(), got);
    }
    Close(h);
    return r;
}

Resp Http::Stream(const std::string& path, const std::map<std::string, std::string>& q, int64_t offset,
                  const std::function<bool(const char*, size_t)>& sink, std::atomic<int64_t>* progress) {
    Resp r;
    HINTERNET h = Open(L"GET", path, q, 120);
    if (!h) { r.error = ErrText(GetLastError()); return r; }
    if (offset > 0) {
        std::wstring rg = L"Range: bytes=" + std::to_wstring(offset) + L"-\r\n";
        WinHttpAddRequestHeaders(h, rg.c_str(), (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_REPLACE);
    }
    // 압축하면 위치가 어긋나므로 받는 쪽 풀기를 끄고, 압축하지 말라고 요청
    DWORD dec = 0; WinHttpSetOption(h, WINHTTP_OPTION_DECOMPRESSION, &dec, sizeof dec);
    WinHttpAddRequestHeaders(h, L"Accept-Encoding: identity\r\n", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_REPLACE);
    if (!WinHttpSendRequest(h, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) || !WinHttpReceiveResponse(h, nullptr)) {
        r.error = ErrText(GetLastError()); Close(h); return r;
    }
    DWORD st = 0, sz = sizeof st;
    WinHttpQueryHeaders(h, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &st, &sz, nullptr);
    r.status = (int)st;
    if (!r.ok() || (offset > 0 && st != 206)) { if (r.ok()) { r.status = 0; r.error = "서버가 이어받기를 지원하지 않음"; } Close(h); return r; }
    std::vector<char> buf(1 << 20);
    for (;;) {
        if (cancel) { r.status = 0; r.error = "취소됨"; break; }
        DWORD got = 0;
        if (!WinHttpReadData(h, buf.data(), (DWORD)buf.size(), &got)) { r.status = 0; r.error = ErrText(GetLastError()); break; }
        if (got == 0) break;
        if (progress) *progress += got;
        Throttle(1, got, cancel);
        if (!sink(buf.data(), got)) break;   // 받는 쪽이 그만 받겠다고 함 (끝까지 받았거나 취소)
    }
    Close(h);
    return r;
}

Resp Http::Range(const std::string& path, const std::map<std::string, std::string>& q, int64_t off, int64_t len, std::string& out) {
    Resp r;
    out.clear();
    if (len <= 0) { r.status = 206; return r; }
    HINTERNET h = Open(L"GET", path, q, 120);
    if (!h) { r.error = ErrText(GetLastError()); return r; }
    std::wstring rg = L"Range: bytes=" + std::to_wstring(off) + L"-" + std::to_wstring(off + len - 1) + L"\r\n";
    WinHttpAddRequestHeaders(h, rg.c_str(), (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_REPLACE);
    DWORD dec = 0; WinHttpSetOption(h, WINHTTP_OPTION_DECOMPRESSION, &dec, sizeof dec);
    WinHttpAddRequestHeaders(h, L"Accept-Encoding: identity\r\n", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_REPLACE);
    if (!WinHttpSendRequest(h, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) || !WinHttpReceiveResponse(h, nullptr)) {
        r.error = ErrText(GetLastError()); Close(h); return r;
    }
    DWORD st = 0, sz = sizeof st;
    WinHttpQueryHeaders(h, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &st, &sz, nullptr);
    r.status = (int)st;
    if (!r.ok() || (st == 200 && off > 0)) { if (r.ok()) { r.status = 0; r.error = "서버가 조각 받기를 지원하지 않음"; } Close(h); return r; }
    out.reserve((size_t)len);
    std::vector<char> buf(256 * 1024);
    while ((int64_t)out.size() < len) {
        if (cancel) { r.status = 0; r.error = "취소됨"; break; }
        DWORD got = 0;
        if (!WinHttpReadData(h, buf.data(), (DWORD)buf.size(), &got)) { r.status = 0; r.error = ErrText(GetLastError()); break; }
        if (got == 0) break;
        size_t take = (size_t)std::min<int64_t>(got, len - (int64_t)out.size());
        out.append(buf.data(), take);
        Throttle(1, got, cancel);
    }
    Close(h);
    return r;
}

Resp Http::Req(const wchar_t* method, const std::string& path, const std::map<std::string, std::string>& q,
               const std::string* body, int timeoutSec) {
    HINTERNET h = Open(method, path, q, timeoutSec);
    if (!h) { Resp r; r.error = ErrText(GetLastError()); return r; }
    DWORD len = body ? (DWORD)body->size() : 0;
    if (!WinHttpSendRequest(h, WINHTTP_NO_ADDITIONAL_HEADERS, 0, body && len ? (LPVOID)body->data() : WINHTTP_NO_REQUEST_DATA, len, len, 0)) {
        Resp r; r.error = ErrText(GetLastError()); Close(h); return r;
    }
    return Finish(h, INVALID_HANDLE_VALUE, nullptr);
}

Resp Http::Upload(const std::string& path, const std::map<std::string, std::string>& q, const std::wstring& file,
                  std::atomic<int64_t>* progress) {
    Resp r;
    HANDLE f = CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                           OPEN_EXISTING, FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
    if (f == INVALID_HANDLE_VALUE) { r.status = -1; r.error = "파일을 열 수 없음 (다른 프로그램이 쓰는 중일 수 있음)"; return r; }
    LARGE_INTEGER size; GetFileSizeEx(f, &size);
    HINTERNET h = Open(L"PUT", path, q, 120);   // 한 번 주고받기 2분 넘게 멈추면 끊고 다음에 다시 (그 통로만 막힘)
    if (!h) { CloseHandle(f); r.error = ErrText(GetLastError()); return r; }
    // 4GB 넘는 파일: 길이를 헤더로
    std::wstring cl = L"Content-Length: " + std::to_wstring(size.QuadPart) + L"\r\n";
    WinHttpAddRequestHeaders(h, cl.c_str(), (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD | WINHTTP_ADDREQ_FLAG_REPLACE);
    DWORD total = size.QuadPart > 0xFFFFFFFELL ? WINHTTP_IGNORE_REQUEST_TOTAL_LENGTH : (DWORD)size.QuadPart;
    if (!WinHttpSendRequest(h, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, total, 0)) {
        r.error = ErrText(GetLastError()); Close(h); CloseHandle(f); return r;
    }
    std::vector<char> buf(1 << 20);
    int64_t left = size.QuadPart;
    while (left > 0) {
        if (cancel || XferStopped()) { Close(h); CloseHandle(f); r.error = "취소됨"; return r; }
        DWORD got = 0;
        if (!ReadFile(f, buf.data(), (DWORD)buf.size(), &got, nullptr)) { Close(h); CloseHandle(f); r.status = -1; r.error = "파일 읽기 실패"; return r; }
        if (got == 0) break;
        DWORD w = 0;
        if (!WinHttpWriteData(h, buf.data(), got, &w)) { r.error = ErrText(GetLastError()); Close(h); CloseHandle(f); return r; }
        left -= got;
        if (progress) *progress += got;
        Throttle(0, got, cancel);
    }
    CloseHandle(f);
    if (left != 0) { Close(h); r.status = -1; r.error = "올리는 중 파일 크기가 바뀜"; return r; }
    return Finish(h, INVALID_HANDLE_VALUE, nullptr);
}

Resp Http::Download(const std::string& path, const std::map<std::string, std::string>& q, const std::wstring& outFile,
                    std::atomic<int64_t>* progress) {
    Resp r;
    HANDLE f = CreateFileW(outFile.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_HIDDEN, nullptr);
    if (f == INVALID_HANDLE_VALUE) { r.status = -1; r.error = "임시 파일을 만들 수 없음"; return r; }
    HINTERNET h = Open(L"GET", path, q, 120);   // 한 번 주고받기 2분 넘게 멈추면 끊고 다음에 다시 (그 통로만 막힘)
    if (!h || !WinHttpSendRequest(h, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0)) {
        r.error = ErrText(GetLastError()); if (h) Close(h); CloseHandle(f); DeleteFileW(outFile.c_str()); return r;
    }
    r = Finish(h, f, progress);
    FlushFileBuffers(f);
    CloseHandle(f);
    if (!r.ok()) DeleteFileW(outFile.c_str());
    return r;
}

} // namespace md
