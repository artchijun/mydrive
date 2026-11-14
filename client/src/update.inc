// update.inc — 새 판 받기 (app.cpp 안에 포함, popup.inc 다음)
//   GitHub 공개 저장소의 최신 릴리스(api.github.com/repos/artchijun/mydrive/releases/latest)를 보고 이 PC보다 새것이면
//   설치 파일(Mydrive-setup-<판>.exe)을 받아 GitHub가 알려 준 SHA-256과 맞춰 본 뒤 "/quiet /tray" 로 실행.
//   설치 파일이 이 앱을 끄고(WM_APP_QUIT) 새 판으로 바꾼 뒤 트레이로 다시 켬. 설정·동기화 기록은 그대로.
//   로그인·토큰 필요 없음. 새 판 내기: client/release.ps1
#include "version.h"
#include <bcrypt.h>
#pragma comment(lib, "bcrypt.lib")
#define WM_APP_UPDATE (WM_APP + 32)
static const char* kUpdRepo = "artchijun/mydrive";

enum UpdState { U_NONE, U_CHECKING, U_LATEST, U_AVAIL, U_DOWNLOADING, U_INSTALLING, U_FAILED };
struct UpdInfo { std::string version, file, sha256, notes; int64_t size = 0; };
struct UpdResult { int state = U_FAILED; UpdInfo info; std::wstring msg, installer; bool install = false, manual = false; };
static int g_updState = U_NONE;
static UpdInfo g_updInfo;             // 마지막으로 서버가 알려 준 판
static std::wstring g_updMsg;         // 실패 사유
static bool g_updBusy = false;
static uint64_t g_updNextAt = 0;      // 다음 자동 확인 시각 (GetTickCount64, 0 = 곧)
static std::string g_updNotified;     // 이 판은 이미 알림을 띄움
static const uint64_t kUpdEvery = 6ull * 3600 * 1000;
static void Settings_UpdateUi();      // 설정 창 (아래)

// "0.5.10" > "0.5.9"
static int VerCmp(const std::string& a, const std::string& b) {
    size_t i = 0, j = 0;
    while (i < a.size() || j < b.size()) {
        long x = 0, y = 0;
        for (; i < a.size() && a[i] != '.'; i++) if (isdigit((unsigned char)a[i])) x = x * 10 + (a[i] - '0');
        for (; j < b.size() && b[j] != '.'; j++) if (isdigit((unsigned char)b[j])) y = y * 10 + (b[j] - '0');
        if (x != y) return x < y ? -1 : 1;
        if (i < a.size()) i++;
        if (j < b.size()) j++;
    }
    return 0;
}
static bool VerOk(const std::string& v) {
    if (v.empty() || v.size() > 20 || !isdigit((unsigned char)v[0])) return false;
    for (char c : v) if (!isdigit((unsigned char)c) && c != '.') return false;
    return true;
}
static std::string FileSha256(const std::wstring& path) {
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
    if (f == INVALID_HANDLE_VALUE) return "";
    BCRYPT_ALG_HANDLE alg = nullptr; BCRYPT_HASH_HANDLE hh = nullptr; std::string out;
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, nullptr, 0) == 0 &&
        BCryptCreateHash(alg, &hh, nullptr, 0, nullptr, 0, 0) == 0) {
        std::vector<unsigned char> buf(1 << 16); DWORD n = 0; bool ok = true;
        while (ReadFile(f, buf.data(), (DWORD)buf.size(), &n, nullptr) && n > 0)
            if (BCryptHashData(hh, buf.data(), n, 0) != 0) { ok = false; break; }
        unsigned char d[32];
        if (ok && BCryptFinishHash(hh, d, 32, 0) == 0) {
            static const char* hx = "0123456789abcdef";
            for (unsigned char c : d) { out += hx[c >> 4]; out += hx[c & 15]; }
        }
    }
    if (hh) BCryptDestroyHash(hh);
    if (alg) BCryptCloseAlgorithmProvider(alg, 0);
    CloseHandle(f);
    return out;
}

// GitHub에 묻고(install 이면 받기까지) 결과를 주 창으로 보냄. 주 스레드에서만 부름
static void UpdateWork(bool install, bool manual) {
    if (g_updBusy) return;
    if (install && !IsInstalled()) {   // 개발용 빌드(build 폴더)에서 실행하면 설치된 앱을 바꾸지 않음
        g_updState = U_FAILED; g_updMsg = L"설치된 Mydrive에서만 업데이트합니다 (지금은 개발용 실행 파일)."; Settings_UpdateUi();
        return;
    }
    g_updBusy = true; g_updState = install ? U_DOWNLOADING : U_CHECKING;
    Settings_UpdateUi();
    std::thread([install, manual] {
        auto* res = new UpdResult; res->install = install; res->manual = manual;
        Http api(L"https://api.github.com", ""); api.plain = true;
        Resp r = api.Req(L"GET", std::string("/repos/") + kUpdRepo + "/releases/latest", {}, nullptr, 20);
        json j = r.ok() ? json::parse(r.body, nullptr, false) : json();
        std::string url;
        if (r.status == 0) res->msg = L"GitHub에 닿지 않습니다 (인터넷 연결을 확인하세요).";
        else if (r.status == 404) res->state = U_LATEST;   // 아직 릴리스가 없음
        else if (r.status == 403 || r.status == 429) res->msg = L"GitHub 요청 한도에 걸렸습니다. 한 시간쯤 뒤 다시 시도하세요.";
        else if (!r.ok() || j.is_discarded() || !j.is_object()) res->msg = Fmt(L"GitHub 응답이 이상합니다 (%d).", r.status);
        else {
            UpdInfo& u = res->info;
            u.version = j.value("tag_name", std::string());
            if (!u.version.empty() && (u.version[0] == 'v' || u.version[0] == 'V')) u.version.erase(0, 1);
            std::string body = j.value("body", std::string());   // 릴리스 설명의 첫 줄을 메모로
            u.notes = body.substr(0, body.find_first_of("\r\n"));
            if (u.notes.size() > 160) u.notes.resize(160);
            for (auto& a : j.value("assets", json::array())) {
                std::string n = a.value("name", std::string());
                if (n.rfind("Mydrive-setup-", 0) != 0 || n.size() < 5 || _stricmp(n.c_str() + n.size() - 4, ".exe")) continue;
                u.file = n; u.size = a.value("size", (int64_t)0);
                url = a.value("browser_download_url", std::string());
                std::string dg = a.is_object() && a.contains("digest") && a["digest"].is_string() ? a["digest"].get<std::string>() : "";
                if (dg.rfind("sha256:", 0) == 0) u.sha256 = dg.substr(7);
                break;
            }
            const std::string pre = std::string("https://github.com/") + kUpdRepo + "/releases/download/";
            if (u.version.empty() || !VerOk(u.version) || VerCmp(u.version, MYDRIVE_VERSION_STR) <= 0) res->state = U_LATEST;
            else if (u.file.empty() || url.rfind(pre, 0) != 0) res->msg = L"GitHub 릴리스 " + W(u.version) + L"에 설치 파일이 없습니다.";
            else if (u.sha256.size() != 64) res->msg = L"GitHub가 설치 파일의 SHA-256을 알려 주지 않아 설치하지 않습니다.";
            else if (!install) res->state = U_AVAIL;
            else {
                wchar_t tp[MAX_PATH]; GetTempPathW(MAX_PATH, tp);
                std::wstring dir = std::wstring(tp) + L"Mydrive-update";
                CreateDirectoryW(dir.c_str(), nullptr);
                std::wstring out = dir + L"\\Mydrive-setup-" + W(u.version) + L".exe";
                DeleteFileW(out.c_str());
                Http dl(L"https://github.com", ""); dl.plain = true;   // 받기 주소는 github.com → 저장소 서버로 넘겨줌 (자동으로 따라감)
                Resp d = dl.Download(url.substr(std::string("https://github.com").size()), {}, out);
                WIN32_FILE_ATTRIBUTE_DATA fa{};
                int64_t got = GetFileAttributesExW(out.c_str(), GetFileExInfoStandard, &fa) ? ((int64_t)fa.nFileSizeHigh << 32 | fa.nFileSizeLow) : -1;
                std::string sha = got > 0 ? FileSha256(out) : "";
                std::string want = u.sha256; for (auto& c : want) c = (char)tolower((unsigned char)c);
                if (!d.ok()) res->msg = Fmt(L"새 판을 받지 못했습니다 (%d).", d.status);
                else if (u.size > 0 && got != u.size) res->msg = L"받은 파일 크기가 맞지 않습니다. 다시 시도하세요.";
                else if (sha != want) res->msg = L"받은 파일의 SHA-256이 GitHub가 알려 준 값과 다릅니다. 설치하지 않았습니다.";
                else { res->state = U_INSTALLING; res->installer = out; }
                if (res->state != U_INSTALLING) DeleteFileW(out.c_str());
            }
        }
        if (!PostMessageW(g_main, WM_APP_UPDATE, 0, (LPARAM)res)) delete res;
    }).detach();
}
static void UpdateCheck(bool manual) { UpdateWork(false, manual); }
static void UpdateInstallNow() { UpdateWork(true, true); }

static void OnUpdate(UpdResult* p) {
    std::unique_ptr<UpdResult> r(p);
    g_updBusy = false;
    g_updState = r->state; g_updMsg = r->msg;
    if (!r->info.version.empty()) g_updInfo = r->info;
    uint64_t now = GetTickCount64();
    if (r->state == U_INSTALLING) {
        SHELLEXECUTEINFOW se{sizeof se};
        se.lpVerb = L"open"; se.lpFile = r->installer.c_str(); se.lpParameters = L"/quiet /tray"; se.nShow = SW_HIDE;
        if (ShellExecuteExW(&se)) {
            TrayBalloon(L"Mydrive 업데이트", L"새 판 " + W(g_updInfo.version) + L"을 설치합니다. 잠시 뒤 Mydrive가 다시 켜집니다.", NIIF_INFO);
        } else { g_updState = U_FAILED; g_updMsg = L"설치 파일을 실행하지 못했습니다."; }
    } else if (r->state == U_AVAIL) {
        if (!r->manual && g_cfg.autoUpdate && IsInstalled()) {
            if (GetOverall().state == T_IDLE) { UpdateWork(true, false); return; }
            g_updNextAt = now + 10 * 60 * 1000;   // 옮기는 중이면 10분 뒤에 다시
        } else if (g_updNotified != g_updInfo.version) {
            g_updNotified = g_updInfo.version;
            TrayBalloon(L"Mydrive 새 판 " + W(g_updInfo.version),
                        L"설정 → 업데이트, 또는 트레이 아이콘 오른쪽 클릭 메뉴에서 설치할 수 있습니다." +
                        (g_updInfo.notes.empty() ? std::wstring() : L"\n" + W(g_updInfo.notes)), NIIF_INFO);
        }
    } else if (r->state == U_FAILED && r->install && !r->manual) {
        g_updNextAt = now + 60 * 60 * 1000;   // 자동 설치가 실패하면 1시간 뒤 다시
    }
    Settings_UpdateUi();
}

// 1분마다: 확인할 때가 되었으면 조용히 확인 (처음은 앱을 켠 뒤 1분)
static void UpdateTick() {
    uint64_t now = GetTickCount64();
    if (g_updBusy || now < g_updNextAt) return;
    g_updNextAt = now + kUpdEvery;
    UpdateCheck(false);
}

static std::wstring UpdateText() {
    switch (g_updState) {
    case U_CHECKING: return L"확인하는 중…";
    case U_LATEST: return L"✔ 최신 판입니다" + (g_updInfo.version.empty() ? std::wstring() : L" (GitHub의 최신 판 " + W(g_updInfo.version) + L")");
    case U_AVAIL: return L"새 판 " + W(g_updInfo.version) + L"이 있습니다" + (g_updInfo.notes.empty() ? std::wstring() : L" — " + W(g_updInfo.notes));
    case U_DOWNLOADING: return L"새 판을 받는 중…";
    case U_INSTALLING: return L"설치하는 중… 잠시 뒤 Mydrive가 다시 켜집니다";
    case U_FAILED: return L"✖ " + g_updMsg;
    }
    return L"아직 확인하지 않았습니다";
}
static COLORREF UpdateColor() {
    switch (g_updState) {
    case U_LATEST: return RGB(0x38, 0xD3, 0x9F);
    case U_AVAIL: case U_DOWNLOADING: case U_INSTALLING: return RGB(0x6E, 0xA8, 0xFF);
    case U_FAILED: return RGB(0xFF, 0x6B, 0x74);
    }
    return RGB(0x8C, 0x93, 0xA3);
}
static std::wstring UpdateMenuLabel() {
    if (g_updState == U_AVAIL) return L"새 판 " + W(g_updInfo.version) + L" 설치";
    if (g_updState == U_DOWNLOADING || g_updState == U_INSTALLING) return L"새 판 설치 중…";
    return L"";
}
