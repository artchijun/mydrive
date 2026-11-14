// util.h — 문자열·시간·경로 도구
#pragma once
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <string>
#include <cstdint>
#include <cstdio>
#include <cwchar>
#include <cwctype>
#include <mutex>
#include <atomic>

namespace md {

inline std::wstring W(const std::string& s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0);
    std::wstring w(n, 0);
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), w.data(), n);
    return w;
}
inline std::string U(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string s(n, 0);
    WideCharToMultiByte(CP_UTF8, 0, w.data(), (int)w.size(), s.data(), n, nullptr, nullptr);
    return s;
}

// FILETIME(1601년부터 100ns) <-> 유닉스 ns
constexpr int64_t EPOCH_DIFF_100NS = 116444736000000000LL;
inline int64_t FtToNs(FILETIME ft) {
    int64_t t = ((int64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime;
    return (t - EPOCH_DIFF_100NS) * 100;
}
inline FILETIME NsToFt(int64_t ns) {
    int64_t t = ns / 100 + EPOCH_DIFF_100NS;
    FILETIME ft{(DWORD)(t & 0xffffffff), (DWORD)(t >> 32)};
    return ft;
}
// 같은 시각인지 (Windows 는 100ns 단위라 그 안의 차이는 같다고 봄)
inline bool SameTime(int64_t a, int64_t b) { return (a > b ? a - b : b - a) < 100; }

// 상대 경로(슬래시) -> 절대 경로(역슬래시, 긴 경로 접두어)
inline std::wstring Abs(const std::wstring& root, const std::string& rel) {
    std::wstring r = W(rel);
    for (auto& c : r) if (c == L'/') c = L'\\';
    std::wstring p = root;
    if (!p.empty() && p.back() != L'\\') p += L'\\';
    p += r;
    if (p.rfind(L"\\\\?\\", 0) != 0) p = L"\\\\?\\" + p;
    return p;
}
inline std::string Parent(const std::string& rel) {
    auto i = rel.rfind('/');
    return i == std::string::npos ? std::string() : rel.substr(0, i);
}
inline std::string Leaf(const std::string& rel) {
    auto i = rel.rfind('/');
    return i == std::string::npos ? rel : rel.substr(i + 1);
}
inline int Depth(const std::string& rel) {
    int d = 0; for (char c : rel) if (c == '/') d++; return d;
}

// Windows 에서 쓸 수 없는 이름 (NAS 에는 있을 수 있음)
inline bool BadWinName(const std::string& rel) {
    std::wstring w = W(rel);
    size_t start = 0;
    while (start <= w.size()) {
        size_t e = w.find(L'/', start);
        if (e == std::wstring::npos) e = w.size();
        std::wstring part = w.substr(start, e - start);
        if (part.empty()) return true;
        for (wchar_t c : part) if (c < 32 || wcschr(L"<>:\"\\|?*", c)) return true;
        if (part.back() == L'.' || part.back() == L' ') return true;
        std::wstring up = part.substr(0, part.find(L'.'));
        for (auto& c : up) c = (wchar_t)towupper(c);
        static const wchar_t* dev[] = {L"CON", L"PRN", L"AUX", L"NUL", L"COM1", L"COM2", L"COM3", L"COM4", L"LPT1", L"LPT2", L"LPT3"};
        for (auto d : dev) if (up == d) return true;
        start = e + 1;
    }
    return false;
}

// 동기화하지 않는 이름
//   늘 빼는 것 (설정으로 바꿀 수 없음): Mydrive 가 받는 동안 쓰는 임시 파일, 시놀로지 내부 폴더
//   설정 '임시·시스템 파일' 체크로 고르는 종류 (g_skipMask 비트): 1 .tmp / 2 오피스·리브레오피스 잠금 / 4 desktop.ini / 8 Thumbs.db
//   16 점(.)으로 시작하는 폴더와 그 안 전체 (.git, .vscode …) — 경로를 봐야 해서 Engine::Excluded 에서 처리
inline std::atomic<unsigned> g_skipMask{15};
inline bool AlwaysSkipName(const std::wstring& n) {
    if (n.rfind(L".mydrive-tmp-", 0) == 0) return true;
    return n == L"#recycle" || n == L"@eaDir" || n == L"#snapshot" || n == L"@tmp" || n == L".SynologyWorkingDirectory";
}
inline unsigned SkipKind(const std::wstring& n) {
    size_t l = n.size();
    if (l > 4 && _wcsicmp(n.c_str() + l - 4, L".tmp") == 0) return 1;
    if (n.rfind(L"~$", 0) == 0 || n.rfind(L".~lock.", 0) == 0) return 2;
    if (_wcsicmp(n.c_str(), L"desktop.ini") == 0) return 4;
    if (_wcsicmp(n.c_str(), L"Thumbs.db") == 0) return 8;
    return 0;
}
inline bool SkipLocalName(const std::wstring& n) { return AlwaysSkipName(n) || (SkipKind(n) & g_skipMask.load()) != 0; }
inline bool SkipRemoteRel(const std::string& rel) {
    std::wstring w = W(Leaf(rel));
    return SkipLocalName(w);
}

// 기록 (콘솔 + 파일)
class Log {
public:
    static Log& I() { static Log l; return l; }
    void Open(const std::wstring& path) { std::lock_guard<std::mutex> g(mu_); f_ = _wfopen(path.c_str(), L"ab"); }
    template <class... A> void P(const char* fmt, A... a) {
        char buf[2048]; snprintf(buf, sizeof buf, fmt, a...);
        SYSTEMTIME st; GetLocalTime(&st);
        char line[2300]; snprintf(line, sizeof line, "%04d-%02d-%02d %02d:%02d:%02d  %s\n", st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond, buf);
        std::lock_guard<std::mutex> g(mu_);
        std::wstring wl = W(line); DWORD w;
        HANDLE h = GetStdHandle(STD_OUTPUT_HANDLE);
        if (!WriteConsoleW(h, wl.c_str(), (DWORD)wl.size(), &w, nullptr)) { fputs(line, stdout); fflush(stdout); }
        if (f_) { fputs(line, f_); fflush(f_); }
    }
private:
    std::mutex mu_; FILE* f_ = nullptr;
};
#define LOG(...) ::md::Log::I().P(__VA_ARGS__)

} // namespace md
