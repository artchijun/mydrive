// app.cpp — Mydrive 트레이 앱 (주 창 · 설정 창 · 트레이 아이콘)
#include "engine.h"
#include "webdrive.h"
#include "../third_party/json.hpp"
#include <windowsx.h>
#include <commctrl.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <deque>
#include <memory>
#include <algorithm>
#include <cstdarg>
#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "shlwapi.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "dwmapi.lib")
#include <dwmapi.h>
#include <commdlg.h>
#pragma comment(lib, "comdlg32.lib")

using json = nlohmann::json;
using namespace md;

// ---------- 설정 ----------
struct Conn {
    std::string name; std::wstring local;
    std::vector<std::string> skip;    // 선택 동기화로 뺀 NAS 폴더 (연결 안 경로)
    std::vector<std::string> prio;    // 먼저 맞출 폴더
    bool high = false;                // (예전 설정 읽기용) 지금은 목록 순서가 우선순위
    bool onDemand = true;             // 받는 방식: true = 필요할 때 받기, false = 모두 받아 두기 (탐색기 연동은 늘 켬)
    std::vector<std::string> keep;    // 항상 이 PC에 둘 폴더 (나머지는 요청 시 받기)
    bool web = false;                 // 받는 방식 '웹드라이브처럼 사용': PC에 파일을 두지 않고 NAS 를 드라이브로 붙임 (webdrive.cpp)
    std::wstring mount;               // 웹드라이브를 붙일 드라이브 문자 ("M:"). 비어 있으면 local 폴더에 붙임
    bool off = false;                 // 웹드라이브 연결을 끊어 둠 ([연결 끊기] / [연결])
    bool paused = false;              // 이 작업만 '다시 시작할 때까지' 멈춤 (앱을 다시 켜도 유지)
    int64_t pauseUntil = 0;           // 시간을 정한 멈춤이 끝나는 시각 (1970년부터 ms). 앱을 다시 켜거나 업데이트해도 남은 시간만큼 이어 감
};
static int64_t NowEpochMs() {
    FILETIME ft; GetSystemTimeAsFileTime(&ft);
    return (int64_t)((((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime) / 10000ULL) - 11644473600000LL;
}
struct AppCfg {
    std::wstring server = L"http://192.168.0.6:8642";
    std::wstring serverAlt;     // 집 밖에서 쓸 주소 (예: Tailscale http://100.x.x.x:8642). 집 주소가 안 될 때 자동으로 씀
    std::string token;
    std::string pairUser;       // DSM 계정으로 연결했으면 그 사용자 (화면 표시용)
    std::vector<Conn> conns;
    int limit = 50;
    bool startup = true;
    std::wstring excludes = L"*.bak;Thumbs.db;~*.tmp";   // 이름 패턴, ; 로 구분
    int maxSizeMB = 0;          // 0 = 제한 없음
    int autoFreeDays = 0;       // 이만큼 안 연 파일은 온라인 전용으로 (0 = 끔)
    int quietSec = 2, maxDeferSec = 30, rescanMin = 5;
    int upKBps = 0, downKBps = 0;
    bool autoUpdate = true;     // 새 판이 나오면 자동으로 설치
    int skipMask = 15;          // 맞추지 않을 임시·시스템 파일 종류 (1 .tmp, 2 잠금 파일, 4 desktop.ini, 8 Thumbs.db, 16 점으로 시작하는 폴더 — 기본은 맞춤)
};
static AppCfg g_cfg;
static std::wstring g_activeServer;   // 지금 쓰는 주소 (비어 있으면 g_cfg.server)
static std::wstring ActiveServer() { return g_activeServer.empty() ? g_cfg.server : g_activeServer; }
static bool OnAltServer() { return !g_activeServer.empty() && g_activeServer != g_cfg.server; }
static std::wstring g_dataDir;   // %LOCALAPPDATA%\Mydrive

static std::vector<std::wstring> SplitW(const std::wstring& s) {
    std::vector<std::wstring> v; size_t i = 0;
    while (i <= s.size()) {
        size_t j = s.find(L';', i); if (j == std::wstring::npos) j = s.size();
        std::wstring x = s.substr(i, j - i);
        while (!x.empty() && iswspace(x.back())) x.pop_back();
        while (!x.empty() && iswspace(x[0])) x.erase(0, 1);
        if (!x.empty()) v.push_back(x);
        i = j + 1;
    }
    return v;
}
static std::wstring JoinW(const std::vector<std::string>& v) { std::wstring s; for (auto& x : v) { if (!s.empty()) s += L";"; s += W(x); } return s; }
static std::vector<std::string> SplitU(const std::wstring& s) {
    std::vector<std::string> v;
    for (auto& x : SplitW(s)) { std::string u = U(x); for (auto& c : u) if (c == '\\') c = '/'; while (!u.empty() && u.back() == '/') u.pop_back(); while (!u.empty() && u[0] == '/') u.erase(0, 1); if (!u.empty()) v.push_back(u); }
    return v;
}

static std::wstring CfgPath() { return g_dataDir + L"\\config.json"; }
static void LoadCfg() {
    FILE* f = _wfopen(CfgPath().c_str(), L"rb");
    if (!f) return;
    std::string s; char b[4096]; size_t n;
    while ((n = fread(b, 1, sizeof b, f)) > 0) s.append(b, n);
    fclose(f);
    json j = json::parse(s, nullptr, false);
    if (j.is_discarded()) return;
    g_cfg.server = W(j.value("server", U(g_cfg.server)));
    g_cfg.serverAlt = W(j.value("serverAlt", std::string()));
    g_cfg.token = j.value("token", std::string());
    g_cfg.pairUser = j.value("pairUser", std::string());
    g_cfg.limit = j.value("massDeleteLimit", 50);
    g_cfg.startup = j.value("startWithWindows", true);
    g_cfg.excludes = W(j.value("excludes", U(g_cfg.excludes)));
    g_cfg.maxSizeMB = j.value("maxFileSizeMB", 0);
    g_cfg.autoFreeDays = j.value("autoFreeDays", 0);
    g_cfg.quietSec = j.value("quietSec", 2);
    g_cfg.maxDeferSec = j.value("maxDeferSec", 30);
    g_cfg.rescanMin = j.value("rescanMin", 5);
    g_cfg.upKBps = j.value("upKBps", 0);
    g_cfg.downKBps = j.value("downKBps", 0);
    g_cfg.autoUpdate = j.value("autoUpdate", true);
    g_cfg.skipMask = j.value("skipBuiltin", 15);
    g_cfg.conns.clear();
    for (auto& c : j.value("connections", json::array())) {
        Conn x; x.name = c.value("name", std::string()); x.local = W(c.value("local", std::string()));
        x.skip = c.value("skipDirs", std::vector<std::string>()); x.prio = c.value("priorityDirs", std::vector<std::string>());
        x.high = c.value("high", false);
        x.onDemand = c.value("onDemand", false);
        x.keep = c.value("keepDirs", std::vector<std::string>());
        x.web = c.value("mode", std::string()) == "webdrive";
        x.mount = W(c.value("mount", std::string()));
        x.off = c.value("off", false);
        x.paused = c.value("paused", false);
        x.pauseUntil = c.value("pauseUntil", (int64_t)0);
        g_cfg.conns.push_back(x);
    }
    // 예전 '우선순위 높음' 짝은 맨 위로 (이제는 목록 순서 = 우선순위)
    std::stable_sort(g_cfg.conns.begin(), g_cfg.conns.end(), [](const Conn& a, const Conn& b) { return a.high && !b.high; });
    for (auto& c : g_cfg.conns) c.high = false;
}
static void SaveCfg() {
    json j;
    j["server"] = U(g_cfg.server); j["serverAlt"] = U(g_cfg.serverAlt); j["token"] = g_cfg.token; j["pairUser"] = g_cfg.pairUser; j["massDeleteLimit"] = g_cfg.limit; j["startWithWindows"] = g_cfg.startup;
    j["excludes"] = U(g_cfg.excludes); j["maxFileSizeMB"] = g_cfg.maxSizeMB; j["autoFreeDays"] = g_cfg.autoFreeDays;
    j["quietSec"] = g_cfg.quietSec; j["maxDeferSec"] = g_cfg.maxDeferSec; j["rescanMin"] = g_cfg.rescanMin;
    j["upKBps"] = g_cfg.upKBps; j["downKBps"] = g_cfg.downKBps; j["autoUpdate"] = g_cfg.autoUpdate; j["skipBuiltin"] = g_cfg.skipMask;
    j["connections"] = json::array();
    for (auto& c : g_cfg.conns)
        j["connections"].push_back({{"name", c.name}, {"local", U(c.local)}, {"skipDirs", c.skip}, {"priorityDirs", c.prio}, {"onDemand", c.onDemand}, {"keepDirs", c.keep}, {"mode", c.web ? "webdrive" : "sync"}, {"mount", U(c.mount)}, {"off", c.off}, {"paused", c.paused}, {"pauseUntil", c.pauseUntil}});
    std::string s = j.dump(2);
    std::wstring tmp = CfgPath() + L".tmp";
    FILE* f = _wfopen(tmp.c_str(), L"wb");
    if (!f) return;
    fwrite(s.data(), 1, s.size(), f); fclose(f);
    MoveFileExW(tmp.c_str(), CfgPath().c_str(), MOVEFILE_REPLACE_EXISTING);
}
static std::wstring ExePath() { wchar_t e[MAX_PATH]; GetModuleFileNameW(nullptr, e, MAX_PATH); return e; }
// 설치된 위치(%LOCALAPPDATA%\Programs\Mydrive)에서 실행 중인지 — 개발용 빌드가 자동 실행에 등록되지 않게
static bool IsInstalled() {
    wchar_t la[MAX_PATH]; ExpandEnvironmentStringsW(L"%LOCALAPPDATA%\\Programs\\Mydrive\\", la, MAX_PATH);
    return _wcsnicmp(ExePath().c_str(), la, wcslen(la)) == 0;
}
// Windows 11은 새 트레이 아이콘을 숨김(^) 칸에 넣음 → 처음 한 번만 작업 표시줄에 보이게 (사용자가 바꾸면 그대로 둠)
static void PromoteTray() {
    HKEY root;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, L"Control Panel\\NotifyIconSettings", 0, KEY_READ, &root) != ERROR_SUCCESS) return;
    std::wstring me = ExePath();
    wchar_t name[256];
    for (DWORD i = 0;; i++) {
        DWORD nl = 256;
        if (RegEnumKeyExW(root, i, name, &nl, nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS) break;
        HKEY k;
        if (RegOpenKeyExW(root, name, 0, KEY_READ | KEY_SET_VALUE, &k) != ERROR_SUCCESS) continue;
        wchar_t path[MAX_PATH * 2] = {}; DWORD pl = sizeof path;
        if (RegQueryValueExW(k, L"ExecutablePath", nullptr, nullptr, (BYTE*)path, &pl) == ERROR_SUCCESS && (_wcsicmp(path, me.c_str()) == 0 || (wcslen(path) > 12 && _wcsicmp(path + wcslen(path) - 12, L"\\Mydrive.exe") == 0)) &&
            RegQueryValueExW(k, L"IsPromoted", nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS) {
            DWORD one = 1; RegSetValueExW(k, L"IsPromoted", 0, REG_DWORD, (const BYTE*)&one, 4);
        }
        RegCloseKey(k);
    }
    RegCloseKey(root);
}
static void ApplyStartup() {
    if (!IsInstalled()) return;
    HKEY k;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Run", 0, KEY_SET_VALUE, &k) != ERROR_SUCCESS) return;
    if (g_cfg.startup) {
        wchar_t exe[MAX_PATH]; GetModuleFileNameW(nullptr, exe, MAX_PATH);
        std::wstring v = L"\"" + std::wstring(exe) + L"\" --tray";
        RegSetValueExW(k, L"Mydrive", 0, REG_SZ, (const BYTE*)v.c_str(), (DWORD)((v.size() + 1) * 2));
    } else RegDeleteValueW(k, L"Mydrive");
    RegCloseKey(k);
}

// ---------- 전역 ----------
#define WM_APP_EVENT (WM_APP + 1)
#define WM_APP_TRAY  (WM_APP + 2)
#define WM_APP_SHOW  (WM_APP + 3)

static HINSTANCE g_inst;
static HWND g_main, g_settings, g_tip;
static HFONT g_font, g_fontBold, g_fontBig, g_fontSmall;
static UINT g_dpi = 96;
static std::vector<std::shared_ptr<Engine>> g_engines;   // shared: 상태 보기 창이 다른 스레드에서 잠시 붙잡을 수 있음
static std::vector<std::shared_ptr<WebDrive>> g_webdrives;   // 받는 방식이 '웹드라이브처럼 사용'인 작업

struct EvRow { std::wstring time, conn, kind, path, extra; int64_t ts = 0; int cat = 0; };
// 엔진 스레드 → 화면 스레드: 기록을 여기 모아 두고 알림은 한 번만 (한 건마다 보내면 메시지 큐가 넘치고 창이 멈춤)
static std::mutex g_evMu;
static std::vector<EvRow> g_evPending;
static bool g_evPosted = false;
static std::deque<EvRow> g_events;   // 최근 것이 앞
static const size_t MAX_EVENTS = 1000;

static int S(int v) { return MulDiv(v, g_dpi, 96); }

static void MakeFonts() {
    for (HFONT* f : {&g_font, &g_fontBold, &g_fontBig, &g_fontSmall}) if (*f) DeleteObject(*f);
    auto mk = [](int pt, int w) {
        return CreateFontW(-MulDiv(pt, g_dpi, 72), 0, 0, 0, w, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"맑은 고딕");
    };
    g_font = mk(9, FW_NORMAL); g_fontBold = mk(9, FW_SEMIBOLD); g_fontBig = mk(13, FW_SEMIBOLD); g_fontSmall = mk(8, FW_NORMAL);
}

static void AddTip(HWND tip, HWND ctl, const wchar_t* text) {
    TOOLINFOW ti{sizeof ti};
    ti.uFlags = TTF_IDISHWND | TTF_SUBCLASS;
    ti.hwnd = GetParent(ctl); ti.uId = (UINT_PTR)ctl; ti.lpszText = (LPWSTR)text;
    SendMessageW(tip, TTM_ADDTOOLW, 0, (LPARAM)&ti);
}
static HWND MakeTip(HWND owner) {
    HWND t = CreateWindowExW(WS_EX_TOPMOST, TOOLTIPS_CLASSW, nullptr, WS_POPUP | TTS_ALWAYSTIP | TTS_NOPREFIX, 0, 0, 0, 0, owner, nullptr, g_inst, nullptr);
    SendMessageW(t, TTM_SETMAXTIPWIDTH, 0, S(380));
    SendMessageW(t, TTM_SETDELAYTIME, TTDT_AUTOPOP, 30000);
    return t;
}

// ---------- 엔진 ----------
static void StopEngines() {
    for (auto& e : g_engines) e->Stop();
    g_engines.clear();
    for (auto& w : g_webdrives) w->Stop();   // 열린 파일 중 고친 것은 올리고 떼어 냄
    g_webdrives.clear();
}
static std::wstring WebMount(const Conn& c) { return c.mount.empty() ? c.local : c.mount; }
static void RemoveOldUserVerbs();   // tools.inc
static void StartWebDrive(const Conn& c) {
    if (c.name.empty() || WebMount(c).empty()) return;
    WebDriveCfg wc; wc.name = c.name; wc.serverUrl = ActiveServer(); wc.token = g_cfg.token;
    wc.mount = WebMount(c); wc.cacheDir = g_dataDir + L"\\webdrive";
    auto w = std::make_shared<WebDrive>(wc);
    std::wstring cname = W(c.name);
    w->onEvent = [cname](const std::string& k, const std::string& p, const std::string& x) {
        SYSTEMTIME st; GetLocalTime(&st);
        wchar_t t[32]; swprintf(t, 32, L"%04d-%02d-%02d %02d:%02d:%02d", st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
        std::lock_guard<std::mutex> g(g_evMu);
        g_evPending.push_back(EvRow{t, cname, W(k), W(p), W(x)});
        if (!g_evPosted) g_evPosted = PostMessageW(g_main, WM_APP_EVENT, 0, 0) != FALSE;
    };
    w->Start();
    g_webdrives.push_back(std::move(w));
}
static void StartEngines() {
    StopEngines();
    SetRateLimit((int64_t)g_cfg.upKBps * 1024, (int64_t)g_cfg.downKBps * 1024);
    g_skipMask = (unsigned)g_cfg.skipMask;
    int rank = 0;
    for (auto& c : g_cfg.conns) {
        if (c.web) {   // 웹드라이브: 엔진(동기화) 없이 NAS 를 드라이브로 붙임 (끊어 둔 것은 그대로)
            if (!c.off) StartWebDrive(c);
            continue;
        }
        if (c.name.empty() || c.local.empty()) continue;
        CreateDirectoryW(c.local.c_str(), nullptr);
        Config ec;
        ec.name = c.name; ec.localRoot = c.local; ec.serverUrl = ActiveServer(); ec.token = g_cfg.token;
        ec.stateDir = g_dataDir + L"\\state"; ec.massDeleteLimit = g_cfg.limit;
        ec.excludes = SplitW(g_cfg.excludes); ec.maxFileSize = (int64_t)g_cfg.maxSizeMB * 1024 * 1024;
        ec.skipDirs = c.skip; ec.priorityDirs = c.prio; ec.rank = rank++; ec.onDemand = c.onDemand;
        ec.keepDirs = c.keep; ec.autoFreeDays = g_cfg.autoFreeDays;
        ec.quietSec = g_cfg.quietSec; ec.maxDeferSec = g_cfg.maxDeferSec; ec.rescanMin = g_cfg.rescanMin;
        wchar_t pc[MAX_COMPUTERNAME_LENGTH + 1]; DWORD pn = MAX_COMPUTERNAME_LENGTH + 1;
        if (GetComputerNameW(pc, &pn)) ec.pcName = pc;
        // 상태 파일은 연결 이름 + PC 폴더마다 따로 (폴더를 바꾸면 처음부터 다시 비교 → 엉뚱한 삭제 방지)
        std::wstring low = c.local; CharLowerBuffW(low.data(), (DWORD)low.size());
        wchar_t hx[32]; swprintf(hx, 32, L"%016llx", (unsigned long long)std::hash<std::wstring>{}(low));
        CreateDirectoryW(ec.stateDir.c_str(), nullptr);
        ec.stateDir += L"\\"; ec.stateDir += hx;
        CreateDirectoryW(ec.stateDir.c_str(), nullptr);
        auto e = std::make_shared<Engine>(ec);
        std::wstring cname = W(c.name);
        e->onEvent = [cname](const std::string& k, const std::string& p, const std::string& x) {
            SYSTEMTIME st; GetLocalTime(&st);
            wchar_t t[32]; swprintf(t, 32, L"%04d-%02d-%02d %02d:%02d:%02d", st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
            std::lock_guard<std::mutex> g(g_evMu);
            g_evPending.push_back(EvRow{t, cname, W(k), W(p), W(x)});
            if (!g_evPosted) g_evPosted = PostMessageW(g_main, WM_APP_EVENT, 0, 0) != FALSE;   // 쌓인 것을 한 번에 가져가라는 알림 하나만
        };
        if (c.paused) e->Pause(-1);
        else if (c.pauseUntil > NowEpochMs()) e->PauseMs((uint64_t)(c.pauseUntil - NowEpochMs()));
        e->Start();
        g_engines.push_back(std::move(e));
    }
    {   // 탐색 창: Mydrive 하나 아래에 모든 짝
        std::vector<std::pair<std::wstring, std::wstring>> ps;
        for (auto& c : g_cfg.conns) if (!c.web && !c.name.empty() && !c.local.empty()) ps.push_back({W(c.name), c.local});
        wchar_t exe[MAX_PATH]; GetModuleFileNameW(nullptr, exe, MAX_PATH);
        cloud::NavHub(ps, g_dataDir + L"\\탐색기", std::wstring(exe) + L",0");
    }
    RemoveOldUserVerbs();   // 예전 판의 사용자별 메뉴 등록 정리 (지금 메뉴는 설치할 때 PC 전체에 넣음)
}

// 전체 상태: 0 정상, 1 동기화 중, 2 멈춤, 3 확인 필요, 4 연결 안 됨
static int EngineState(Engine& e) {
    if (e.Paused()) return 2;
    if (e.stats.heldDeletes > 0) return 3;
    if (!e.stats.online && !e.busy) return 4;
    if (e.busy || e.stats.pending > 0 || e.bigN > 0) return 1;   // 큰 파일을 따로 옮기는 중이어도 동기화 중
    return 0;
}
static const wchar_t* StateText(int s) {
    static const wchar_t* t[] = {L"동기화됨", L"동기화 중", L"일시 중지", L"확인 필요", L"NAS에 연결할 수 없음"};
    return t[s];
}
static int Worst() {
    int w = 0; static const int rank[] = {0, 1, 2, 3, 4};
    for (auto& e : g_engines) { int s = EngineState(*e); if (rank[s] > rank[w]) w = s; }
    return w;
}

static std::wstring Fmt(const wchar_t* f, ...) {
    wchar_t b[1024]; va_list a; va_start(a, f); _vsnwprintf_s(b, _TRUNCATE, f, a); va_end(a); return b;
}
static std::wstring LeftText(uint64_t ms) {
    uint64_t m = (ms + 59999) / 60000;
    if (m >= 60) return Fmt(L"%llu시간 %llu분", m / 60, m % 60);
    return Fmt(L"%llu분", m);
}
static std::wstring ServerHost() {
    std::wstring s = ActiveServer();
    size_t p = s.find(L"://"); if (p != std::wstring::npos) s = s.substr(p + 3);
    if (!s.empty() && s.back() == L'/') s.pop_back();
    if (OnAltServer()) s += L" (밖에서 쓰는 주소)";
    return s;
}

// ---------- 트레이 창 · 트레이 아이콘 · 알림 (popup.inc) ----------
#include "popup.inc"
#include "update.inc"   // 새 판 받기

// ---------- 숨은 주 창: 트레이 메시지, 1초마다 상태 확인, 엔진 소식 받기 ----------
static UINT g_msgTaskbarCreated;
#define WM_APP_QUIT (WM_APP + 4)
static HBRUSH g_brWhite;

static HWND Ctl(HWND parent, const wchar_t* cls, const wchar_t* text, DWORD style, int id, DWORD ex = 0) {
    if (!wcscmp(cls, L"EDIT")) ex |= WS_EX_CLIENTEDGE;
    HWND h = CreateWindowExW(ex, cls, text, WS_CHILD | WS_VISIBLE | style, 0, 0, 10, 10, parent, (HMENU)(INT_PTR)id, g_inst, nullptr);
    SendMessageW(h, WM_SETFONT, (WPARAM)g_font, TRUE);
    return h;
}
static void AddCol(HWND lv, int i, const wchar_t* name, int w) {
    LVCOLUMNW c{}; c.mask = LVCF_TEXT | LVCF_WIDTH; c.pszText = (LPWSTR)name; c.cx = S(w);
    ListView_InsertColumn(lv, i, &c);
}

static void Tick() {
    Overall o = GetOverall();
    WatchAlerts(o);
    std::wstring tip = L"Mydrive · " + o.text;
    if (o.state == T_STUCK && !o.stuck.empty()) tip += L" — " + Leaf(W(o.stuck[0].second.p));
    else if (!o.xf.empty()) tip += L" — " + Leaf(W(o.xf[0].second.p));
    else if (o.state == T_HOLD) tip += AnyPausedForever() ? std::wstring(L" · 다시 시작할 때까지") : L" · " + ClockAfter(PausedLeft()) + L"까지";
    TraySet(o.state, tip);
    Popup_Invalidate();
}

// ---------- 집 밖에서: 집 주소가 안 되면 '밖에서 쓸 주소'(예: Tailscale)로 자동 전환 ----------
#define WM_APP_PROBED (WM_APP + 31)
static uint64_t g_netDownSince = 0, g_lastProbe = 0;
static bool g_probing = false;
static bool Reachable(const std::wstring& url) {
    if (url.empty()) return false;
    Http h(url, "");
    Resp r = h.Req(L"GET", "/v1/ping", {}, nullptr, 4);
    return r.ok() && r.body.rfind("mydrived", 0) == 0;
}
// 연결을 넣거나 뺀 뒤: 탐색기 왼쪽 목록 항목을 PC 전체 영역에 맞춰 넣을지 물음 (관리자 승인 필요)
static bool NavMirrorsCurrent();                 // tools.inc
static bool RunElevated(const wchar_t* arg);    // tools.inc
static uint64_t g_navCheckAt = 0;
static void CheckNav() { g_navCheckAt = 0; }   // 탐색 창은 앱이 직접 'Mydrive' 하나로 만듦 (관리자 승인 필요 없음)
// 10초마다: 연결이 15초 넘게 끊겨 있으면 주소를 다시 고르고, 밖 주소를 쓰는 중이면 1분마다 집 주소가 다시 되는지 봄
static void CheckServer() {
    CheckNav();
    if (g_cfg.serverAlt.empty() || g_engines.empty()) { g_netDownSince = 0; return; }
    bool anyOnline = false;
    for (auto& e : g_engines) if (e->stats.online) anyOnline = true;
    uint64_t now = GetTickCount64();
    if (anyOnline) g_netDownSince = 0; else if (!g_netDownSince) g_netDownSince = now;
    bool want = (!anyOnline && now - g_netDownSince >= 15000 && now - g_lastProbe >= 20000) ||
                (OnAltServer() && now - g_lastProbe >= 60000);
    if (!want || g_probing) return;
    g_probing = true; g_lastProbe = now;
    std::wstring a = g_cfg.server, b = g_cfg.serverAlt;
    std::thread([a, b] {
        auto* s = new std::wstring(Reachable(a) ? a : Reachable(b) ? b : L"");   // 집 주소가 먼저
        if (!PostMessageW(g_main, WM_APP_PROBED, 0, (LPARAM)s)) delete s;
    }).detach();
}
static void OnProbed(std::wstring* s) {
    std::unique_ptr<std::wstring> p(s);
    g_probing = false;
    if (p->empty() || *p == ActiveServer()) return;
    bool toAlt = *p != g_cfg.server;
    g_activeServer = toAlt ? *p : L"";
    g_netDownSince = 0;
    StartEngines();
    std::wstring msg = toAlt ? L"집 주소에 닿지 않아 밖에서 쓰는 주소(" + *p + L")로 연결했습니다."
                             : L"집 주소(" + g_cfg.server + L")로 다시 연결했습니다.";
    AddAlert(A_NETOK, msg);
    TrayBalloon(L"Mydrive 연결 주소", msg, NIIF_INFO);
}

static void ShellCmd(const std::wstring& cmd, const std::wstring& path);   // tools.inc: 탐색기 오른쪽 클릭의 동기화 명령
static void MoveToBatch();   // tools.inc
#define WM_APP_SHELLCMD (WM_APP + 30)
static LRESULT CALLBACK MainProc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == g_msgTaskbarCreated && msg) { g_icoState = -1; TrayAdd(); return 0; }
    switch (msg) {
    case WM_COPYDATA: {   // 다른 Mydrive.exe (오른쪽 클릭 메뉴) 가 보낸 "명령\n경로"
        auto* cd = (COPYDATASTRUCT*)lp;
        if (!cd || cd->dwData != 0x4D44 || !cd->lpData || cd->cbData < 2 || cd->cbData > 64 * 1024) return FALSE;
        auto* s = new std::wstring((const wchar_t*)cd->lpData, cd->cbData / sizeof(wchar_t));
        PostMessageW(h, WM_APP_SHELLCMD, 0, (LPARAM)s);   // 보낸 쪽을 오래 붙잡지 않게 나중에 처리
        return TRUE;
    }
    case WM_APP_SHELLCMD: {
        std::unique_ptr<std::wstring> s((std::wstring*)lp);
        size_t n = s->find(L'\n');
        if (n != std::wstring::npos) ShellCmd(s->substr(0, n), s->substr(n + 1));
        return 0;
    }
    case WM_CREATE: g_main = h; SetTimer(h, 1, 1000, nullptr); SetTimer(h, 2, 5000, nullptr); SetTimer(h, 3, 10000, nullptr); SetTimer(h, 4, 60000, nullptr); return 0;
    case WM_APP_PROBED: OnProbed((std::wstring*)lp); return 0;
    case WM_APP_UPDATE: OnUpdate((UpdResult*)lp); return 0;
    case WM_TIMER:
        if (wp == 2) { KillTimer(h, 2); PromoteTray(); return 0; }
        if (wp == 3) { CheckServer(); return 0; }
        if (wp == 4) { UpdateTick(); return 0; }
        if (wp == 77) { KillTimer(h, 77); MoveToBatch(); return 0; }   // tools.inc: 다른 동기화 작업으로 옮기기
        Tick();
        return 0;
    case WM_APP_EVENT: DrainEvents(); return 0;
    case WM_APP_SHOW: Popup_Show(); return 0;
    case WM_APP_QUIT: DestroyWindow(h); return 0;
    case WM_APP_TRAY:
        switch (LOWORD(lp)) {
        case NIN_SELECT: case NIN_KEYSELECT: Popup_Toggle(); break;
        case WM_CONTEXTMENU: TrayMenu(); break;
        case NIN_BALLOONUSERCLICK:
            if (!g_balloonReveal.empty()) { RevealInExplorer(g_balloonReveal); g_balloonReveal.clear(); }
            else Popup_Show(g_icoState == T_STUCK ? 0 : -1);
            break;
        }
        return 0;
    case WM_COMMAND:
        if (LOWORD(wp) == IDM_EXIT) DestroyWindow(h); else TrayCommand(LOWORD(wp));
        return 0;
    case WM_DISPLAYCHANGE: case WM_SETTINGCHANGE: MakeTrayIcons(); g_icoState = -1; Tick(); return 0;
    case WM_QUERYENDSESSION: return TRUE;
    case WM_ENDSESSION: if (wp) StopEngines(); return 0;
    case WM_DESTROY:
        KillTimer(h, 1);
        Shell_NotifyIconW(NIM_DELETE, &g_nid);
        StopEngines();
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(h, msg, wp, lp);
}

// ---------- 어두운 대화 창 (설정 · 폴더 고르기) — 트레이 창과 같은 색 ----------
#include <uxtheme.h>
#pragma comment(lib, "uxtheme.lib")
static const COLORREF kBg = RGB(0x1F, 0x23, 0x2B), kEdit = RGB(0x2A, 0x2E, 0x37), kBtn = RGB(0x33, 0x37, 0x40), kBtnDown = RGB(0x45, 0x4A, 0x55),
                      kAccent = RGB(0x3B, 0x6F, 0xD9), kText = RGB(0xED, 0xEF, 0xF4), kMuted = RGB(0x8C, 0x93, 0xA3), kSub = RGB(0xB4, 0xBA, 0xC6);
static HBRUSH g_brDark, g_brEdit;
static int s_tabSel = 0;
static int s_dlgTab = 0;   // 동기화 작업 창의 탭 (0 폴더 1 받는 방식 2 일반)
static void DarkWindow(HWND h) {
    BOOL d = TRUE; DwmSetWindowAttribute(h, 20, &d, sizeof d);
    COLORREF c = kBg; DwmSetWindowAttribute(h, 35 /*DWMWA_CAPTION_COLOR*/, &c, sizeof c);
    COLORREF t = kText; DwmSetWindowAttribute(h, 36 /*DWMWA_TEXT_COLOR*/, &t, sizeof t);
}
// 오른쪽 클릭 메뉴(트레이 아이콘 · 기록 줄 · 작업 줄)도 어둡게: Windows 의 메뉴는 앱이 '어두운 모드'를 고르지 않으면 늘 흰색.
//   uxtheme 의 이름 없는 함수(135 SetPreferredAppMode, 136 FlushMenuThemes) — 탐색기·작업 관리자도 쓰는 방법. 없으면 그냥 흰 메뉴.
static void DarkMenus() {
    HMODULE ux = LoadLibraryExW(L"uxtheme.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!ux) return;
    using SetMode = int(WINAPI*)(int);
    using Flush = void(WINAPI*)();
    if (auto f = (SetMode)GetProcAddress(ux, MAKEINTRESOURCEA(135))) f(2 /*ForceDark: 창들이 늘 어두운 색이므로 메뉴도 늘 어둡게*/);
    if (auto f = (Flush)GetProcAddress(ux, MAKEINTRESOURCEA(136))) f();
}
static bool ChkGet(HWND b) { return GetPropW(b, L"chk.on") != nullptr; }
static void ChkSet(HWND b, bool on) { if (on) SetPropW(b, L"chk.on", (HANDLE)1); else RemovePropW(b, L"chk.on"); InvalidateRect(b, nullptr, TRUE); }
static void MakeChk(HWND b) { SetPropW(b, L"chk", (HANDLE)1); SetWindowLongPtrW(b, GWL_STYLE, (GetWindowLongPtrW(b, GWL_STYLE) & ~0xFL) | BS_OWNERDRAW); }
// 목록 머리줄 글자를 밝게 (머리줄 알림은 목록 컨트롤로 가므로 목록을 서브클래스)
static LRESULT CALLBACK LvSub(HWND h, UINT msg, WPARAM wp, LPARAM lp, UINT_PTR, DWORD_PTR) {
    if (msg == WM_NOTIFY && ((NMHDR*)lp)->code == NM_CUSTOMDRAW && ((NMHDR*)lp)->hwndFrom == ListView_GetHeader(h)) {
        auto* cd = (NMCUSTOMDRAW*)lp;
        if (cd->dwDrawStage == CDDS_PREPAINT) return CDRF_NOTIFYITEMDRAW;
        if (cd->dwDrawStage == CDDS_ITEMPREPAINT) { SetTextColor(cd->hdc, kSub); return CDRF_NEWFONT; }
    }
    return DefSubclassProc(h, msg, wp, lp);
}
// 자식 컨트롤을 어둡게: 입력 칸·목록은 Windows 어두운 테마, 단추는 직접 그림
static void DarkenChildren(HWND h) {
    EnumChildWindows(h, [](HWND c, LPARAM) -> BOOL {
        wchar_t cls[64]; GetClassNameW(c, cls, 64);
        if (!_wcsicmp(cls, L"Edit") || !_wcsicmp(cls, L"ComboBox")) SetWindowTheme(c, L"DarkMode_CFD", nullptr);
        else if (!_wcsicmp(cls, L"ListBox")) SetWindowTheme(c, L"DarkMode_Explorer", nullptr);   // 스크롤 막대를 어둡게
        else if (!_wcsicmp(cls, WC_LISTVIEWW)) {
            SetWindowTheme(c, L"DarkMode_Explorer", nullptr);
            ListView_SetBkColor(c, kEdit); ListView_SetTextBkColor(c, kEdit); ListView_SetTextColor(c, kText);
            if (HWND hd = ListView_GetHeader(c)) SetWindowTheme(hd, L"DarkMode_ItemsView", nullptr);
            SetWindowSubclass(c, LvSub, 1, 0);
        } else if (!_wcsicmp(cls, L"Button")) {
            LONG_PTR st = GetWindowLongPtrW(c, GWL_STYLE) & 0xF;
            if (st == BS_PUSHBUTTON || st == BS_DEFPUSHBUTTON) {
                if (st == BS_DEFPUSHBUTTON) SetPropW(c, L"accent", (HANDLE)1);
                SetWindowLongPtrW(c, GWL_STYLE, (GetWindowLongPtrW(c, GWL_STYLE) & ~0xFL) | BS_OWNERDRAW);
            }
        }
        return TRUE;
    }, 0);
}
static void FillRound(HDC dc, RECT r, COLORREF c, int rad) {
    HBRUSH b = CreateSolidBrush(c); HPEN p = CreatePen(PS_SOLID, 1, c);
    HGDIOBJ ob = SelectObject(dc, b), op = SelectObject(dc, p);
    RoundRect(dc, r.left, r.top, r.right, r.bottom, rad, rad);
    SelectObject(dc, ob); SelectObject(dc, op); DeleteObject(b); DeleteObject(p);
}
static void DrawDark(DRAWITEMSTRUCT* d) {
    HDC dc = d->hDC; RECT r = d->rcItem;
    FillRect(dc, &r, g_brDark);
    bool down = d->itemState & ODS_SELECTED, dis = d->itemState & ODS_DISABLED, focus = d->itemState & ODS_FOCUS;
    wchar_t txt[256]; GetWindowTextW(d->hwndItem, txt, 256);
    SetBkMode(dc, TRANSPARENT);
    HGDIOBJ of = SelectObject(dc, g_font);
    if (int pt = (int)(INT_PTR)GetPropW(d->hwndItem, L"ptab")) {   // 작업 창 위쪽 탭: 고른 탭은 밝은 글자 + 파란 밑줄
        bool sel = pt - 1 == s_dlgTab;
        SelectObject(dc, sel ? g_fontBold : g_font);
        SetTextColor(dc, sel ? kText : kSub);
        RECT tr = r; tr.bottom -= S(4);
        DrawTextW(dc, txt, -1, &tr, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        if (sel) { RECT bar{r.left + S(14), r.bottom - S(4), r.right - S(14), r.bottom - S(1)}; FillRound(dc, bar, kAccent, S(3)); }
        SelectObject(dc, of);
        return;
    }
    if (GetPropW(d->hwndItem, L"ict")) {   // 아이콘 + 글자 단추 (글자의 첫 글자 = 아이콘)
        bool accent = GetPropW(d->hwndItem, L"accent") != nullptr;
        FillRound(dc, r, down ? kBtnDown : (accent && !dis) ? kAccent : kBtn, S(8));
        std::wstring lab = txt + 1;
        SIZE sz; SelectObject(dc, g_font); GetTextExtentPoint32W(dc, lab.c_str(), (int)lab.size(), &sz);
        int gw = S(16), gap = S(6), total = gw + gap + sz.cx, x0 = (r.left + r.right - total) / 2;
        HFONT icf = CreateFontW(-S(14), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, ui::IconFont());
        SelectObject(dc, icf); SetTextColor(dc, dis ? kMuted : kText);
        RECT gr{x0, r.top, x0 + gw, r.bottom}; DrawTextW(dc, txt, 1, &gr, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        SelectObject(dc, g_font);
        RECT lr{x0 + gw + gap, r.top, r.right, r.bottom}; DrawTextW(dc, lab.c_str(), -1, &lr, DT_LEFT | DT_VCENTER | DT_SINGLELINE);
        SelectObject(dc, of); DeleteObject(icf);
        return;
    }
    int tab = (int)(INT_PTR)GetPropW(d->hwndItem, L"tab");
    if (int side = (int)(INT_PTR)GetPropW(d->hwndItem, L"side")) {   // 설정 창 왼쪽 메뉴
        bool sel = side - 1 == s_tabSel;
        HBRUSH sb = CreateSolidBrush(RGB(0x19, 0x1C, 0x22)); FillRect(dc, &r, sb); DeleteObject(sb);
        if (sel || down) FillRound(dc, r, sel ? RGB(0x2A, 0x3B, 0x5A) : RGB(0x26, 0x2A, 0x33), S(10));
        if (sel) { RECT bar{r.left + S(2), r.top + S(11), r.left + S(5), r.bottom - S(11)}; FillRound(dc, bar, kAccent, S(3)); }
        wchar_t ic = (wchar_t)(INT_PTR)GetPropW(d->hwndItem, L"icon");
        HFONT icf = CreateFontW(-S(16), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, ui::IconFont());
        HGDIOBJ o2 = SelectObject(dc, icf);
        SetTextColor(dc, sel ? RGB(0x8F, 0xB4, 0xFF) : kSub);
        RECT ir{r.left + S(14), r.top, r.left + S(40), r.bottom};
        DrawTextW(dc, &ic, 1, &ir, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        SelectObject(dc, sel ? g_fontBold : g_font); DeleteObject(icf);
        SetTextColor(dc, sel ? kText : kSub);
        RECT tr{r.left + S(48), r.top, r.right - S(6), r.bottom};
        DrawTextW(dc, txt, -1, &tr, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
        SelectObject(dc, of);
        return;
    }
    if (GetPropW(d->hwndItem, L"opt")) {   // 고르기 칸 (제목 + 설명 두 줄, 고른 것은 파란 테두리)
        bool on = ChkGet(d->hwndItem);
        FillRound(dc, r, on ? kAccent : RGB(0x3A, 0x40, 0x4C), S(12));
        RECT in{r.left + 1, r.top + 1, r.right - 1, r.bottom - 1};
        FillRound(dc, in, on ? RGB(0x24, 0x34, 0x52) : RGB(0x27, 0x2B, 0x34), S(11));
        int rs = S(16), cy = r.top + S(19);
        RECT rb{r.left + S(12), cy - rs / 2, r.left + S(12) + rs, cy + rs / 2};
        FillRound(dc, rb, on ? kAccent : kMuted, rs);
        RECT ri{rb.left + 2, rb.top + 2, rb.right - 2, rb.bottom - 2};
        FillRound(dc, ri, on ? kAccent : RGB(0x27, 0x2B, 0x34), rs);
        if (on) { RECT dot{rb.left + S(5), rb.top + S(5), rb.right - S(5), rb.bottom - S(5)}; FillRound(dc, dot, RGB(255, 255, 255), rs); }
        std::wstring t = txt, sub;
        if (auto k = t.find(L'\n'); k != std::wstring::npos) { sub = t.substr(k + 1); t = t.substr(0, k); }
        SelectObject(dc, g_fontBold); SetTextColor(dc, dis ? kMuted : kText);
        RECT tr{rb.right + S(8), r.top + S(8), r.right - S(8), r.top + S(30)};
        DrawTextW(dc, t.c_str(), -1, &tr, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
        SelectObject(dc, g_fontSmall); SetTextColor(dc, dis ? kMuted : kSub);
        RECT sr{r.left + S(12), r.top + S(31), r.right - S(8), r.bottom - S(5)};
        DrawTextW(dc, sub.c_str(), -1, &sr, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
        SelectObject(dc, of);
        return;
    }
    if (GetPropW(d->hwndItem, L"chk")) {   // 체크 상자
        bool on = ChkGet(d->hwndItem);
        int bs = S(18); RECT b{r.left + S(1), (r.top + r.bottom - bs) / 2, r.left + S(1) + bs, (r.top + r.bottom + bs) / 2};
        if (on) FillRound(dc, b, dis ? kBtnDown : kAccent, S(6));
        else { FillRound(dc, b, kMuted, S(6)); RECT in{b.left + 2, b.top + 2, b.right - 2, b.bottom - 2}; FillRound(dc, in, kEdit, S(5)); }
        if (on) {
            HFONT ic = CreateFontW(-S(12), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, ui::IconFont());
            HGDIOBJ o2 = SelectObject(dc, ic); SetTextColor(dc, RGB(255, 255, 255));
            DrawTextW(dc, L"\xE73E", 1, &b, DT_CENTER | DT_VCENTER | DT_SINGLELINE); SelectObject(dc, o2); DeleteObject(ic);
        }
        RECT t{b.right + S(8), r.top, r.right, r.bottom};
        SetTextColor(dc, dis ? kMuted : kText);
        DrawTextW(dc, txt, -1, &t, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
    } else {
        bool sel = tab && tab - 1 == s_tabSel;
        bool accent = GetPropW(d->hwndItem, L"accent") != nullptr;
        bool danger = GetPropW(d->hwndItem, L"danger") != nullptr && !dis;
        COLORREF bg = tab ? (sel ? RGB(0x2F, 0x46, 0x6E) : RGB(0x28, 0x2C, 0x34)) : danger ? RGB(0x44, 0x25, 0x2B) : (accent && !dis) ? kAccent : kBtn;
        if (down) bg = tab ? RGB(0x36, 0x52, 0x80) : danger ? RGB(0x5A, 0x2D, 0x35) : accent ? RGB(0x4A, 0x7E, 0xE8) : kBtnDown;
        FillRound(dc, r, bg, S(8));
        if (focus && !tab) { HBRUSH fb = CreateSolidBrush(RGB(0x6E, 0x9B, 0xF5)); RECT fr = r; InflateRect(&fr, -1, -1); FrameRect(dc, &fr, fb); DeleteObject(fb); }
        SetTextColor(dc, dis ? kMuted : danger ? RGB(0xFF, 0x8E, 0x97) : (tab && !sel) ? kSub : kText);
        if (GetPropW(d->hwndItem, L"ico")) {   // 아이콘 단추 (글자 = 아이콘 글꼴 한 글자)
            HFONT icf = CreateFontW(-S(15), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, ui::IconFont());
            HGDIOBJ o2 = SelectObject(dc, icf);
            DrawTextW(dc, txt, -1, &r, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
            SelectObject(dc, o2); DeleteObject(icf);
        } else DrawTextW(dc, txt, -1, &r, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
    }
    SelectObject(dc, of);
}
// 어두운 창 공통 메시지 (처리했으면 true)
static bool DarkMsg(HWND h, UINT msg, WPARAM wp, LPARAM lp, LRESULT& out) {
    switch (msg) {
    case WM_ERASEBKGND: { RECT r; GetClientRect(h, &r); FillRect((HDC)wp, &r, g_brDark); out = 1; return true; }
    case WM_CTLCOLOREDIT: case WM_CTLCOLORLISTBOX:
        SetBkColor((HDC)wp, kEdit); SetTextColor((HDC)wp, kText); out = (LRESULT)g_brEdit; return true;
    case WM_CTLCOLORBTN: SetBkColor((HDC)wp, kBg); out = (LRESULT)g_brDark; return true;
    case WM_DRAWITEM: if (((DRAWITEMSTRUCT*)lp)->CtlType == ODT_BUTTON) { DrawDark((DRAWITEMSTRUCT*)lp); out = TRUE; return true; } break;
    }
    return false;
}

// ---------- 설정 창: 왼쪽 메뉴 5개 (보이는 순서: 동기화 작업 · 서버 연결 · 규칙과 저장 공간 · 속도와 시작 · 업데이트) ----------
//   메뉴마다 따로 스크롤되는 면(패널)이 있고, 아래 [저장]·[취소]는 늘 보임.
//   항목 = 굵은 이름 → 입력 칸 → 회색 짧은 설명 (마우스를 올리면 자세한 설명)
enum { IDS_SERVER = 500, IDS_TOKEN, IDS_TEST, IDS_TESTRES, IDS_LIST, IDS_ROOT, IDS_LOCAL, IDS_BROWSE, IDS_ADD, IDS_REMOVE,
       IDS_LIMIT, IDS_STARTUP, IDS_SAVE, IDS_CANCEL, IDS_TAB, IDS_PICK, IDS_PRIO, IDS_HIGH, IDS_EXCL, IDS_MAXSIZE,
       IDS_QUIET, IDS_DEFER, IDS_RESCAN, IDS_UP, IDS_DOWN, IDS_ONDEMAND, IDS_KEEP, IDS_AUTOFREE, IDS_SERVERALT, IDS_PAIR, IDS_PAIRINFO, IDS_HINT,
       IDS_SELLOCAL, IDS_SELBROWSE, IDS_MODEALL, IDS_MOVEUP, IDS_MOVEDN, IDS_ADDPAIR, IDS_PICK2,
       IDS_UPDCHECK, IDS_UPDINST, IDS_UPDRES, IDS_AUTOUPD, IDS_SKTMP, IDS_SKLOCK, IDS_SKINI, IDS_SKTHUMB, IDS_SKDOT, IDS_WEBTOGGLE };
static HWND s_webBtn;   // 웹드라이브 [연결 끊기] / [연결]
#define WM_APP_TESTED (WM_APP + 10)
#define WM_APP_PICKED (WM_APP + 11)
static HWND s_tab, s_server, s_token, s_test, s_testRes, s_list, s_root, s_local, s_browse, s_add, s_remove, s_limit, s_startup, s_tip;
static HWND s_onDemand, s_keep, s_autoFree, s_serverAlt, s_pair, s_pairInfo, s_hint;
static HWND s_selLocal, s_selBrowse, s_selHead, s_empty, s_modeAll, s_moveUp, s_moveDn, s_addPair;
static HWND s_legend, s_pick2;
static HWND s_updCheck, s_updInst, s_updRes, s_autoUpd;   // 업데이트 메뉴
static HWND s_sk[5];   // 맞추지 않을 임시·시스템 파일 종류
static void OpenTask(HWND owner, int idx);   // 동기화 작업 만들기(-1) / 편집   // 탐색기 상태 아이콘 설명
static std::vector<std::wstring> s_names;   // NAS 폴더 이름 ([연결 시험] 때 받아 둠)   // 고른 짝의 PC 폴더 · 제목 · 빈 목록 안내
static HWND s_pick, s_pickInfo, s_prio, s_high, s_excl, s_maxsize, s_quiet, s_defer, s_rescan, s_up, s_down;
static const int kPages = 6;   // 0 서버 연결 1 동기화 작업 2 규칙 3 속도와 시작 4 업데이트 5 탐색기 아이콘
static std::vector<HWND> s_descs, s_labels, s_heads, s_lines, s_page[kPages];
static HWND s_panel[kPages];
static int s_panelH[kPages];
static int s_curPage = 0, s_sel = -1, s_cx = 0, s_cw = 0, s_sideW = 0, s_barH = 0;
static size_t s_mark = 0;          // Field: 이 번호부터가 이번 항목의 컨트롤 (이름 아래로 내림)
static bool s_filling = false;
static std::vector<Conn> s_conns;
static int s_testOk = -1;
static std::string s_pairUser;     // 이번에 DSM 계정으로 연결한 사용자 ([저장] 때 반영)
static HFONT s_fontHead;
static HBRUSH s_brSide, s_brLine;
static const COLORREF kSide = RGB(0x19, 0x1C, 0x22), kLine = RGB(0x33, 0x38, 0x43);

static std::wstring GetText(HWND h) {
    int n = GetWindowTextLengthW(h); std::wstring s(n, 0);
    GetWindowTextW(h, s.data(), n + 1); return s;
}
static std::wstring Trim(std::wstring s) {
    while (!s.empty() && iswspace(s.back())) s.pop_back();
    size_t i = 0; while (i < s.size() && iswspace(s[i])) i++;
    return s.substr(i);
}
// 설정 창 컨트롤: 지금 만드는 메뉴의 면(패널)에 넣음
static HWND SCtl(HWND h, const wchar_t* cls, const wchar_t* text, DWORD style, int id, DWORD ex = 0) {
    HWND parent = s_curPage >= 0 ? s_panel[s_curPage] : h;
    HWND c = Ctl(parent, cls, text, style, id, ex);
    if (s_curPage >= 0) s_page[s_curPage].push_back(c);
    return c;
}
// 폴더별 설정 요약: 항상 이 PC에 / 먼저 맞추기 / 맞추지 않음
static std::wstring FolderSummary(const Conn& c) {
    std::wstring s;
    auto add = [&](const wchar_t* t, size_t n) { if (n) { if (!s.empty()) s += L" · "; s += Fmt(L"%s %d개", t, (int)n); } };
    add(L"항상 이 PC에", c.keep.size()); add(L"먼저 맞추기", c.prio.size()); add(L"맞추지 않음", c.skip.size());
    return s.empty() ? L"모든 폴더가 '보통' (받는 방식대로)" : s;
}
static std::wstring SkipSummary(const Conn& c) {
    if (c.skip.empty()) return L"모든 폴더를 맞춤";
    std::wstring s = Fmt(L"%d개 폴더를 뺌: ", (int)c.skip.size());
    for (size_t i = 0; i < c.skip.size() && i < 3; i++) { if (i) s += L", "; s += W(c.skip[i]); }
    if (c.skip.size() > 3) s += L" …";
    return s;
}
// 동기화 작업 표: NAS 폴더 | PC 폴더 | 받는 방식 | 상태  (위에 있는 작업부터 먼저 맞춤)
static HWND s_thead;
static void FitConnList();   // 아래: 작업 표를 작업 수만큼 키움 (스크롤 없이 모두 보이게)
static HWND s_listHint;
static int s_panelHNow;      // 지금 설정 창의 면 높이
static void FillConnList() {
    s_filling = true;
    SendMessageW(s_list, LB_RESETCONTENT, 0, 0);
    for (int i = 0; i < (int)s_conns.size(); i++) SendMessageW(s_list, LB_ADDSTRING, 0, i);
    SendMessageW(s_list, LB_SETCURSEL, (s_sel >= 0 && s_sel < (int)s_conns.size()) ? s_sel : -1, 0);
    ShowWindow(s_empty, s_conns.empty() ? SW_SHOW : SW_HIDE);
    InvalidateRect(s_list, nullptr, TRUE);
    s_filling = false;
    FitConnList();
}
static void FillRound(HDC dc, RECT r, COLORREF c, int rad);
struct TaskCols { int nas, pc, mode, st; };
static TaskCols TaskColsOf(const RECT& r) {   // 열 시작 자리
    int w = r.right - r.left;
    return {r.left + S(12), r.left + S(150), r.left + w - S(182), r.left + w - S(84)};
}
static void DrawTaskHead(DRAWITEMSTRUCT* d) {
    HDC dc = d->hDC; RECT r = d->rcItem;
    FillRect(dc, &r, g_brDark);
    RECT ln{r.left, r.bottom - 1, r.right, r.bottom}; FillRect(dc, &ln, s_brLine);
    SetBkMode(dc, TRANSPARENT);
    HGDIOBJ of = SelectObject(dc, g_fontSmall); SetTextColor(dc, kMuted);
    TaskCols c = TaskColsOf(r);
    const wchar_t* t[4] = {L"NAS 폴더", L"PC 폴더", L"받는 방식", L"상태"};
    int xs[5] = {c.nas, c.pc, c.mode, c.st, r.right};
    for (int k = 0; k < 4; k++) { RECT tr{xs[k] + (k == 0 ? S(26) : 0), r.top, xs[k + 1] - S(6), r.bottom - S(2)}; DrawTextW(dc, t[k], -1, &tr, DT_LEFT | DT_VCENTER | DT_SINGLELINE); }
    SelectObject(dc, of);
}
static void DrawConnCard(DRAWITEMSTRUCT* d) {   // 작업 한 줄
    HDC dc = d->hDC; RECT r = d->rcItem;
    int i = (int)d->itemID;
    bool sel = (d->itemState & ODS_SELECTED) != 0;
    HBRUSH bg = CreateSolidBrush(sel ? RGB(0x2A, 0x3B, 0x5A) : kBg); FillRect(dc, &r, bg); DeleteObject(bg);
    RECT ln{r.left, r.bottom - 1, r.right, r.bottom}; FillRect(dc, &ln, s_brLine);
    if (i < 0 || i >= (int)s_conns.size()) return;
    const Conn& c = s_conns[i];
    SetBkMode(dc, TRANSPARENT);
    TaskCols cl = TaskColsOf(r);
    int cy = (r.top + r.bottom) / 2;
    HFONT icf = CreateFontW(-S(16), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, ui::IconFont());
    HGDIOBJ of = SelectObject(dc, icf);
    RECT ic{cl.nas, cy - S(10), cl.nas + S(20), cy + S(10)};
    SetTextColor(dc, RGB(0x8F, 0xB4, 0xFF)); DrawTextW(dc, L"\xE8B7", 1, &ic, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    SelectObject(dc, g_fontBold); SetTextColor(dc, kText);
    std::wstring n = W(c.name);
    RECT nr{cl.nas + S(26), r.top, cl.pc - S(8), r.bottom}; DrawTextW(dc, n.c_str(), -1, &nr, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX);
    SelectObject(dc, g_font); SetTextColor(dc, kSub);
    std::wstring pcText = c.web && !c.mount.empty() ? c.mount + L" 드라이브" : c.local;
    RECT pr{cl.pc, r.top, cl.mode - S(10), r.bottom}; DrawTextW(dc, pcText.c_str(), -1, &pr, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_PATH_ELLIPSIS | DT_NOPREFIX);
    SetTextColor(dc, c.web ? RGB(0xC4, 0x9A, 0xFF) : c.onDemand ? RGB(0x8F, 0xB4, 0xFF) : RGB(0x4A, 0xDE, 0xA8));
    RECT mr{cl.mode, r.top, cl.st - S(6), r.bottom}; DrawTextW(dc, c.web ? L"웹드라이브" : c.onDemand ? L"필요할 때" : L"모두 받기", -1, &mr, DT_LEFT | DT_VCENTER | DT_SINGLELINE);
    // 상태: 저장된 작업이면 지금 엔진 상태
    std::wstring st = L"저장 뒤 시작"; COLORREF sc = kMuted;
    for (auto& e : g_engines) if (e->cfg().name == c.name && _wcsicmp(e->cfg().localRoot.c_str(), c.local.c_str()) == 0) {
        switch (EngineState(*e)) {
        case 0: st = L"맞춰짐"; sc = RGB(0x38, 0xD3, 0x9F); break;
        case 1: st = L"맞추는 중"; sc = RGB(0x6C, 0xA8, 0xF0); break;
        case 2: st = L"멈춤"; sc = kSub; break;
        case 3: st = L"확인 필요"; sc = RGB(0xF5, 0xB4, 0x48); break;
        default: st = L"연결 안 됨"; sc = RGB(0xFF, 0x6B, 0x74); break;
        }
    }
    if (c.web && c.off) { st = L"끊김"; sc = kSub; }
    if (c.web) for (auto& w : g_webdrives) if (w->cfg().name == c.name) {
        if (!w->Mounted()) { st = w->Online() ? L"붙이는 중" : L"연결 안 됨"; sc = w->Online() ? RGB(0x6C, 0xA8, 0xF0) : RGB(0xFF, 0x6B, 0x74); }
        else if (!w->Online()) { st = L"NAS 연결 안 됨"; sc = RGB(0xFF, 0x6B, 0x74); }
        else if (w->Uploading()) { st = L"올리는 중"; sc = RGB(0x6C, 0xA8, 0xF0); }
        else { st = L"붙어 있음"; sc = RGB(0x38, 0xD3, 0x9F); }
    }
    RECT dot{cl.st, cy - S(4), cl.st + S(8), cy + S(4)}; FillRound(dc, dot, sc, S(8));
    SetTextColor(dc, sc);
    RECT sr{cl.st + S(14), r.top, r.right - S(4), r.bottom}; DrawTextW(dc, st.c_str(), -1, &sr, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
    SelectObject(dc, of); DeleteObject(icf);
}
// 탐색기 '상태' 칸의 아이콘 뜻 (Windows 가 그리는 모양을 비슷하게)
static const struct { int kind; const wchar_t* name; const wchar_t* desc; } kLegend[] = {
    {0, L"파란 구름 — 온라인 전용", L"내용은 NAS에만 있어 PC 공간을 쓰지 않음. 열면 받아 옴 (인터넷 필요)."},
    {1, L"초록 테두리 체크 — 이 PC에 있음", L"열어서 받아 둔 파일. 오른쪽 클릭 → Mydrive → 공간 확보로 다시 온라인 전용."},
    {2, L"진한 초록 체크 — 항상 이 PC에", L"늘 받아 두는 파일 (모두 받아 두기 · 항상 이 PC에 · 항상 이 장치에 유지)."},
    {3, L"파란 화살표 — 맞추는 중", L"NAS로 올리거나 받는 중입니다. 끝나면 체크나 구름으로 바뀝니다."},
    {4, L"빨간 X — 맞추지 못함", L"이름·권한·잠김 등으로 맞추지 못함. 주 창의 [기록]에서 까닭을 봄."},
    {5, L"아이콘 없음", L"아직 확인 전이거나, 맞추지 않는 파일입니다 ('뺄 이름', '맞추지 않음' 폴더 등)."},
};
static void DrawLegend(DRAWITEMSTRUCT* d) {
    HDC dc = d->hDC; RECT r = d->rcItem;
    FillRect(dc, &r, g_brDark);
    SetBkMode(dc, TRANSPARENT);
    const int rh = S(44);
    HFONT ic = CreateFontW(-S(17), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, ui::IconFont());
    HFONT icS = CreateFontW(-S(11), 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, ui::IconFont());
    HGDIOBJ of = SelectObject(dc, ic);
    const COLORREF green = RGB(0x2E, 0xB8, 0x5C), blue = RGB(0x6C, 0xA8, 0xF0), red = RGB(0xE5, 0x48, 0x48);
    for (int i = 0; i < (int)(sizeof kLegend / sizeof kLegend[0]); i++) {
        int top = r.top + i * rh, cy = top + S(14);
        RECT ib{r.left + S(4), cy - S(10), r.left + S(24), cy + S(10)};
        switch (kLegend[i].kind) {
        case 0: SelectObject(dc, ic); SetTextColor(dc, blue); DrawTextW(dc, L"\xE753", 1, &ib, DT_CENTER | DT_VCENTER | DT_SINGLELINE); break;
        case 1: {
            FillRound(dc, ib, green, S(20));
            RECT in{ib.left + S(2), ib.top + S(2), ib.right - S(2), ib.bottom - S(2)}; FillRound(dc, in, kBg, S(16));
            SelectObject(dc, icS); SetTextColor(dc, green); DrawTextW(dc, L"\xE73E", 1, &ib, DT_CENTER | DT_VCENTER | DT_SINGLELINE); break;
        }
        case 2: FillRound(dc, ib, green, S(20)); SelectObject(dc, icS); SetTextColor(dc, RGB(255, 255, 255)); DrawTextW(dc, L"\xE73E", 1, &ib, DT_CENTER | DT_VCENTER | DT_SINGLELINE); break;
        case 3: SelectObject(dc, ic); SetTextColor(dc, blue); DrawTextW(dc, L"\xE895", 1, &ib, DT_CENTER | DT_VCENTER | DT_SINGLELINE); break;
        case 4: FillRound(dc, ib, red, S(20)); SelectObject(dc, icS); SetTextColor(dc, RGB(255, 255, 255)); DrawTextW(dc, L"\xE711", 1, &ib, DT_CENTER | DT_VCENTER | DT_SINGLELINE); break;
        default: SelectObject(dc, g_font); SetTextColor(dc, kMuted); DrawTextW(dc, L"—", 1, &ib, DT_CENTER | DT_VCENTER | DT_SINGLELINE); break;
        }
        RECT tr{r.left + S(36), top + S(3), r.right, top + S(24)};
        SelectObject(dc, g_fontBold); SetTextColor(dc, kText);
        DrawTextW(dc, kLegend[i].name, -1, &tr, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
        RECT dr{r.left + S(36), top + S(23), r.right, top + rh - S(2)};
        SelectObject(dc, g_fontSmall); SetTextColor(dc, kMuted);
        DrawTextW(dc, kLegend[i].desc, -1, &dr, DT_LEFT | DT_TOP | DT_SINGLELINE | DT_END_ELLIPSIS);
    }
    SelectObject(dc, of); DeleteObject(ic); DeleteObject(icS);
}
static void ShowConnOptions() {   // 도구 단추 켜고 끄기
    bool on = s_sel >= 0 && s_sel < (int)s_conns.size();
    for (HWND c : {s_pick2, s_remove}) if (c) EnableWindow(c, on);
    if (s_webBtn) {   // 고른 작업이 웹드라이브일 때만: 지금 붙어 있으면 [연결 끊기], 아니면 [연결]
        bool web = on && s_conns[s_sel].web;
        bool live = false;
        if (web) for (auto& w : g_webdrives) if (w->cfg().name == s_conns[s_sel].name) live = true;
        SetWindowTextW(s_webBtn, live ? L"\xE8CD연결 끊기" : L"\xE8CE연결");
        ShowWindow(s_webBtn, web ? SW_SHOW : SW_HIDE);
        InvalidateRect(s_webBtn, nullptr, TRUE);
    }
    if (s_moveUp) EnableWindow(s_moveUp, on && s_sel > 0);
    if (s_moveDn) EnableWindow(s_moveDn, on && s_sel + 1 < (int)s_conns.size());
}
// 메뉴 제목 + 가는 선
static int Head(HWND h, int y, const wchar_t* text, bool first = false) {
    if (!first) y += S(8);
    HWND t = SCtl(h, L"STATIC", text, SS_LEFT | SS_NOPREFIX, 0);
    SendMessageW(t, WM_SETFONT, (WPARAM)s_fontHead, TRUE);
    MoveWindow(t, s_cx, y, s_cw, S(24), TRUE);
    s_heads.push_back(t);
    HWND ln = SCtl(h, L"STATIC", L"", SS_LEFT, 0);
    MoveWindow(ln, s_cx, y + S(29), s_cw, 1, TRUE);
    s_lines.push_back(ln);
    s_mark = s_page[s_curPage].size();
    return y + S(44);
}
// 항목: 굵은 이름(위) → 입력 칸(가운데, 미리 y 에 놓아 둔 것을 이름 아래로 내림) → 회색 설명(아래)
static int Field(HWND h, int y, const wchar_t* label, const wchar_t* desc, const wchar_t* tip, std::initializer_list<HWND> ctls, int ctlH) {
    auto& pg = s_page[s_curPage];
    const int lh = S(23);
    for (size_t i = s_mark; i < pg.size(); i++) {
        RECT r; GetWindowRect(pg[i], &r); MapWindowPoints(nullptr, s_panel[s_curPage], (POINT*)&r, 2);
        SetWindowPos(pg[i], nullptr, r.left, r.top + lh, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    }
    HWND l = SCtl(h, L"STATIC", label, SS_LEFT | SS_NOTIFY, 0);
    SendMessageW(l, WM_SETFONT, (WPARAM)g_fontBold, TRUE);
    MoveWindow(l, s_cx, y, s_cw, S(20), TRUE);
    s_labels.push_back(l);
    AddTip(s_tip, l, tip);
    for (HWND c : ctls) AddTip(s_tip, c, tip);
    int dy = y + lh + ctlH + S(5);
    HWND d = SCtl(h, L"STATIC", desc, SS_LEFT | SS_NOPREFIX | SS_NOTIFY, 0);
    SendMessageW(d, WM_SETFONT, (WPARAM)g_fontSmall, TRUE);
    RECT tr{0, 0, s_cw, 0};
    HDC dc = GetDC(h); HGDIOBJ old = SelectObject(dc, g_fontSmall);
    DrawTextW(dc, desc, -1, &tr, DT_CALCRECT | DT_WORDBREAK | DT_NOPREFIX);
    SelectObject(dc, old); ReleaseDC(h, dc);
    MoveWindow(d, s_cx, dy, s_cw, tr.bottom, TRUE);
    AddTip(s_tip, d, tip);
    s_descs.push_back(d);
    s_mark = pg.size();
    return dy + tr.bottom + S(20);
}
static HWND NumEdit(HWND h, int v, int id, int x, int y, int w) {
    HWND e = SCtl(h, L"EDIT", std::to_wstring(v).c_str(), WS_TABSTOP | ES_AUTOHSCROLL | ES_NUMBER, id);
    MoveWindow(e, x, y, w, S(26), TRUE);
    return e;
}
static HWND Unit(HWND h, const wchar_t* t, int x, int y) {
    HWND u = SCtl(h, L"STATIC", t, SS_LEFT | SS_NOPREFIX, 0);
    MoveWindow(u, x, y + S(4), S(320), S(20), TRUE);
    return u;
}
// 면 스크롤 (내용이 창보다 길 때)
static void PanelScroll(HWND h, int dy) {
    SCROLLINFO si{sizeof si, SIF_ALL};
    if (!GetScrollInfo(h, SB_VERT, &si)) return;
    int maxPos = std::max(0, si.nMax - (int)si.nPage + 1);
    int np = std::clamp(si.nPos + dy, 0, maxPos);
    if (np == si.nPos) return;
    ScrollWindowEx(h, 0, si.nPos - np, nullptr, nullptr, nullptr, nullptr, SW_SCROLLCHILDREN | SW_INVALIDATE | SW_ERASE);
    si.fMask = SIF_POS; si.nPos = np; SetScrollInfo(h, SB_VERT, &si, TRUE);
    UpdateWindow(h);
}
static LRESULT CALLBACK PanelProc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_ERASEBKGND: { RECT r; GetClientRect(h, &r); FillRect((HDC)wp, &r, g_brDark); return 1; }
    case WM_COMMAND: case WM_NOTIFY: case WM_CTLCOLORSTATIC: case WM_CTLCOLOREDIT: case WM_CTLCOLORBTN: case WM_CTLCOLORLISTBOX: case WM_DRAWITEM:
        return SendMessageW(GetParent(h), msg, wp, lp);   // 컨트롤 소식은 설정 창이 처리
    case WM_MOUSEWHEEL: PanelScroll(h, -GET_WHEEL_DELTA_WPARAM(wp) * S(60) / WHEEL_DELTA); return 0;
    case WM_VSCROLL: {
        SCROLLINFO si{sizeof si, SIF_ALL}; GetScrollInfo(h, SB_VERT, &si);
        int pos = si.nPos;
        switch (LOWORD(wp)) {
        case SB_LINEUP: pos -= S(30); break; case SB_LINEDOWN: pos += S(30); break;
        case SB_PAGEUP: pos -= (int)si.nPage; break; case SB_PAGEDOWN: pos += (int)si.nPage; break;
        case SB_THUMBTRACK: case SB_THUMBPOSITION: pos = si.nTrackPos; break;
        case SB_TOP: pos = 0; break; case SB_BOTTOM: pos = si.nMax; break;
        }
        PanelScroll(h, pos - si.nPos);
        return 0;
    }
    }
    return DefWindowProcW(h, msg, wp, lp);
}
static void ShowPage(int k) {
    s_tabSel = k;
    for (int i = 0; i < kPages; i++) ShowWindow(s_panel[i], i == k ? SW_SHOW : SW_HIDE);
    HWND w = GetParent(s_panel[0]);
    for (int i = 0; i < kPages; i++) InvalidateRect(GetDlgItem(w, 900 + i), nullptr, TRUE);
    if (k == 1) ShowConnOptions();
}
static std::wstring PairText(const std::string& user) {
    return user.empty() ? L"아직 DSM 계정으로 연결하지 않았습니다" : L"✔ DSM 계정 '" + W(user) + L"'(으)로 연결됨 · 이 PC 전용 열쇠";
}

// 업데이트 메뉴의 상태 줄과 단추 (update.inc 에서 상태가 바뀔 때마다 부름)
static void Settings_UpdateUi() {
    if (!s_updRes || !IsWindow(s_updRes)) return;
    SetWindowTextW(s_updRes, UpdateText().c_str());
    EnableWindow(s_updCheck, !g_updBusy);
    EnableWindow(s_updInst, !g_updBusy && g_updState == U_AVAIL);
    InvalidateRect(s_updRes, nullptr, TRUE);
}
static void CreateSettingsControls(HWND h) {
    s_descs.clear(); s_labels.clear(); s_heads.clear(); s_lines.clear(); for (auto& p : s_page) p.clear();
    s_sel = -1; s_testOk = -1; s_pairUser.clear();
    if (s_fontHead) DeleteObject(s_fontHead);
    s_fontHead = CreateFontW(-MulDiv(11, g_dpi, 72), 0, 0, 0, FW_SEMIBOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"맑은 고딕");
    if (!s_brSide) { s_brSide = CreateSolidBrush(kSide); s_brLine = CreateSolidBrush(kLine); }
    s_tip = MakeTip(h);
    const int WW = S(720);   // 아담하게: 긴 메뉴는 그 안에서 스크롤
    s_sideW = S(172); s_barH = S(58);
    const int PW = WW - s_sideW;
    s_cx = S(28); s_cw = PW - S(28) - S(36);   // 스크롤 막대 자리까지 남김
    const int eh = S(26);
    DWORD es = WS_TABSTOP | ES_AUTOHSCROLL;
    s_tabSel = 0;
    // 왼쪽 메뉴
    const wchar_t* names[kPages] = {L"서버 연결", L"동기화 작업", L"규칙과 저장 공간", L"속도와 시작", L"업데이트", L"탐색기 아이콘"};
    const wchar_t icons[kPages] = {0xE774, 0xE8B7, 0xE71C, 0xEC4A, 0xE895, 0xE753};
    const int order[kPages] = {1, 0, 2, 3, 5, 4};   // 왼쪽 메뉴 보이는 순서: 동기화 작업이 맨 위, 업데이트가 맨 아래
    for (int d = 0; d < kPages; d++) {
        int i = order[d];
        HWND b = Ctl(h, L"BUTTON", names[i], BS_OWNERDRAW | WS_TABSTOP, 900 + i);
        SetPropW(b, L"side", (HANDLE)(INT_PTR)(i + 1));
        SetPropW(b, L"icon", (HANDLE)(INT_PTR)icons[i]);
        MoveWindow(b, S(8), S(14) + d * S(42), s_sideW - S(16), S(38), TRUE);
        if (i == 0) s_tab = b;
    }
    // 메뉴마다 면
    for (int i = 0; i < kPages; i++) {
        s_panel[i] = CreateWindowExW(WS_EX_CONTROLPARENT, L"MydrivePanel", L"", WS_CHILD | WS_VSCROLL | WS_CLIPCHILDREN,
                                     s_sideW, 0, PW, 100, h, nullptr, g_inst, nullptr);
        SetWindowTheme(s_panel[i], L"DarkMode_Explorer", nullptr);
    }
    int y;

    // ── 1. 서버 연결 ──
    s_curPage = 0; s_mark = 0;
    y = Head(h, S(22), L"NAS 서버", true);
    s_server = SCtl(h, L"EDIT", g_cfg.server.c_str(), es, IDS_SERVER);
    MoveWindow(s_server, s_cx, y, s_cw, eh, TRUE);
    y = Field(h, y, L"집 안 주소", L"NAS에서 도는 Mydrive 서버 주소입니다. 예: http://192.168.0.6:8642",
              L"NAS에서 돌아가는 Mydrive 서버(Docker 컨테이너)의 주소입니다.\n"
              L"· 집 안: http://NAS내부IP:8642\n· 집 밖에서 쓸 주소는 아래 칸에 따로 적습니다\n"
              L"주소를 바꾸면 파일은 그대로 두고 연결만 바뀝니다.", {s_server}, eh);
    s_serverAlt = SCtl(h, L"EDIT", g_cfg.serverAlt.c_str(), es, IDS_SERVERALT);
    MoveWindow(s_serverAlt, s_cx, y, s_cw, eh, TRUE);
    y = Field(h, y, L"밖에서 쓸 주소", L"집 주소에 닿지 않으면 이 주소로 자동으로 바꿉니다. 비워 두면 집 안에서만 씁니다.",
              L"집 밖(학교, 카페, 해외)에서도 NAS와 맞추려면 적습니다.\n"
              L"· Tailscale: http://100.x.x.x:8642 (NAS의 Tailscale 주소) — PC에서 Tailscale이 켜져 있어야 함\n"
              L"· 또는 DDNS·공유기 포트 포워딩 주소 (이 경우 https 권장)\n"
              L"집 주소가 15초 넘게 안 되면 이 주소로 바꾸고, 집에 돌아와 집 주소가 다시 되면 1분 안에 집 주소로 돌아갑니다.", {s_serverAlt}, eh);
    y = Head(h, y, L"이 PC 연결");
    s_pair = SCtl(h, L"BUTTON", L"DSM 계정으로 연결…", BS_PUSHBUTTON | WS_TABSTOP, IDS_PAIR);
    SetPropW(s_pair, L"accent", (HANDLE)1);
    MoveWindow(s_pair, s_cx, y, S(180), S(30), TRUE);
    s_pairInfo = SCtl(h, L"STATIC", PairText(g_cfg.pairUser).c_str(), SS_LEFT | SS_NOPREFIX | SS_ENDELLIPSIS, IDS_PAIRINFO);
    MoveWindow(s_pairInfo, s_cx + S(192), y + S(6), s_cw - S(192), S(20), TRUE);
    y = Field(h, y, L"DSM 계정", L"NAS(DSM) 관리자 아이디와 비밀번호로 이 PC를 연결합니다. 토큰을 옮겨 적을 필요가 없습니다.",
              L"[DSM 계정으로 연결]을 누르고 DSM 아이디·비밀번호(2단계 인증을 쓰면 코드도)를 넣으면\n"
              L"서버가 DSM에 확인한 뒤 이 PC만 쓰는 열쇠(토큰)를 만들어 아래 칸에 자동으로 넣습니다.\n"
              L"· 비밀번호는 연결할 때 한 번만 쓰고 어디에도 저장하지 않습니다\n"
              L"· DSM 관리자 계정만 연결할 수 있습니다 (NAS 전체 폴더를 다루기 때문)\n"
              L"· PC를 잃어버리면 관리 콘솔 → 접속한 PC → [끊기]로 이 PC만 막을 수 있습니다", {s_pair}, S(30));
    s_token = SCtl(h, L"EDIT", W(g_cfg.token).c_str(), es | ES_PASSWORD, IDS_TOKEN);
    MoveWindow(s_token, s_cx, y, s_cw - S(124), eh, TRUE);
    s_test = SCtl(h, L"BUTTON", L"연결 시험", BS_PUSHBUTTON | WS_TABSTOP, IDS_TEST);
    MoveWindow(s_test, s_cx + s_cw - S(116), y, S(116), eh, TRUE);
    y = Field(h, y, L"접속 토큰", L"DSM 계정으로 연결하면 자동으로 채워집니다. 관리자가 알려 준 토큰을 직접 넣어도 됩니다.",
              L"서버에 들어가는 열쇠입니다. 이 값을 아는 사람은 NAS 파일에 접근할 수 있으니 다른 사람에게 보내지 마세요.\n"
              L"[연결 시험]을 누르면 서버에 접속해 쓸 수 있는 NAS 폴더 목록을 가져옵니다.", {s_token, s_test}, eh);
    s_testRes = SCtl(h, L"STATIC", L"", SS_LEFT | SS_NOPREFIX | SS_ENDELLIPSIS, IDS_TESTRES);
    MoveWindow(s_testRes, s_cx, y - S(12), s_cw, S(20), TRUE);
    s_mark = s_page[0].size();
    s_panelH[0] = y + S(16);

    // ── 2. 동기화 작업 (시놀로지 드라이브처럼: 도구 단추 + 작업 표, 편집은 따로 창) ──
    s_curPage = 1; s_mark = 0;
    y = Head(h, S(22), L"동기화 작업", true);
    {
        const int bw = S(38), tw = S(92), th = S(32);
        auto tool = [&](const wchar_t* t, int id, int x, int w, bool ico) {
            HWND b = SCtl(h, L"BUTTON", t, BS_PUSHBUTTON | WS_TABSTOP, id);
            SetPropW(b, ico ? L"ico" : L"ict", (HANDLE)1);
            MoveWindow(b, x, y - S(4), w, th, TRUE);
            return b;
        };
        s_addPair = tool(L"\xE710만들기", IDS_ADDPAIR, s_cx, tw, false);
        s_pick2 = tool(L"\xE70F편집", IDS_PICK2, s_cx + tw + S(8), tw, false);
        s_remove = tool(L"\xE74D제거", IDS_REMOVE, s_cx + 2 * (tw + S(8)), tw, false);
        s_webBtn = tool(L"\xE8CD연결 끊기", IDS_WEBTOGGLE, s_cx + 3 * (tw + S(8)), S(98), false);   // 위·아래 단추와 겹치지 않게
        ShowWindow(s_webBtn, SW_HIDE);
        AddTip(s_tip, s_webBtn, L"웹드라이브 연결을 끊거나 다시 붙입니다 (바로 적용, [저장] 필요 없음).\n끊으면 드라이브가 탐색기에서 사라지고, 고치던 파일은 먼저 NAS로 올린 뒤 끊습니다. 다음에 Mydrive를 켜도 끊긴 채로 둡니다.");
        s_moveUp = tool(L"\xE70E", IDS_MOVEUP, s_cx + s_cw - 2 * bw - S(6), bw, true);
        s_moveDn = tool(L"\xE70D", IDS_MOVEDN, s_cx + s_cw - bw, bw, true);
        SetPropW(s_addPair, L"accent", (HANDLE)1);
        AddTip(s_tip, s_addPair, L"새 동기화 작업 — NAS 폴더와 이 PC의 폴더를 고르고, 받을 폴더와 받는 방식을 정합니다.");
        AddTip(s_tip, s_pick2, L"고른 작업 편집 — 받을 폴더(체크 목록) · 받는 방식 · PC 폴더.\n표에서 두 번 눌러도 됩니다.");
        AddTip(s_tip, s_remove, L"고른 작업 제거 — 동기화만 멈추고, NAS와 PC의 파일은 그대로 남습니다.\n[저장]을 눌러야 적용됩니다.");
        AddTip(s_tip, s_moveUp, L"위로 — 위에 있는 작업이 먼저 맞춰집니다.\n위 작업이 파일을 옮기는 동안 아래 작업은 잠시 기다립니다.");
        AddTip(s_tip, s_moveDn, L"아래로");
        y += th + S(6);
        s_thead = SCtl(h, L"STATIC", L"", SS_OWNERDRAW, 0);
        MoveWindow(s_thead, s_cx, y, s_cw, S(28), TRUE);
        y += S(28);
        const int itemH = S(44);
        int listH = itemH * std::max(2, (int)g_cfg.conns.size());   // 작업이 많아도 스크롤 없이 모두 (창이 그만큼 커짐)
        s_list = SCtl(h, L"LISTBOX", L"", LBS_OWNERDRAWFIXED | LBS_NOTIFY | LBS_NOINTEGRALHEIGHT | WS_VSCROLL | WS_TABSTOP, IDS_LIST);
        SendMessageW(s_list, LB_SETITEMHEIGHT, 0, itemH);
        MoveWindow(s_list, s_cx, y, s_cw, listH, TRUE);
        s_empty = SCtl(h, L"STATIC", L"아직 동기화 작업이 없습니다. [만들기]를 누르세요.", SS_CENTER | SS_NOPREFIX, 0);
        MoveWindow(s_empty, s_cx, y + S(14), s_cw, S(20), TRUE);
        AddTip(s_tip, s_list, L"작업마다 NAS 폴더와 PC 폴더를 같은 내용으로 맞춥니다 (양방향).\n두 번 누르면 편집 창이 열립니다.");
        y += listH + S(8);
        HWND d = SCtl(h, L"STATIC", L"위에 있는 작업부터 먼저 맞춥니다. 두 번 누르면 편집합니다.", SS_LEFT | SS_NOPREFIX, 0);
        SendMessageW(d, WM_SETFONT, (WPARAM)g_fontSmall, TRUE);
        MoveWindow(d, s_cx, y, s_cw, S(18), TRUE);
        s_descs.push_back(d);
        s_listHint = d;
        y += S(18) + S(16);
    }
    s_mark = s_page[1].size();
    s_panelH[1] = y;

    // ── 6. 탐색기 아이콘 (탐색기 '상태' 칸의 뜻) ──
    s_curPage = 5; s_mark = 0;
    y = Head(h, S(22), L"탐색기 상태 아이콘", true);
    {
        const int n = (int)(sizeof kLegend / sizeof kLegend[0]), lh = S(44) * n;
        s_legend = SCtl(h, L"STATIC", L"", SS_OWNERDRAW | SS_NOTIFY, 0);
        MoveWindow(s_legend, s_cx, y - S(6), s_cw, lh, TRUE);
        AddTip(s_tip, s_legend, L"탐색기 '상태' 칸에 보이는 아이콘의 뜻입니다.\n파일·폴더를 오른쪽 클릭 → Mydrive → 항상 이 장치에 유지 / 공간 확보로 바꿀 수 있습니다.");
        y += lh + S(4);
    }
    s_panelH[5] = y + S(8);

    // ── 3. 규칙과 저장 공간 ──
    s_curPage = 2; s_mark = 0;
    const int nw = S(96), ux = s_cx + nw + S(10);
    y = Head(h, S(22), L"맞추지 않을 것", true);
    s_excl = SCtl(h, L"EDIT", g_cfg.excludes.c_str(), es, IDS_EXCL);
    MoveWindow(s_excl, s_cx, y, s_cw, eh, TRUE);
    y = Field(h, y, L"뺄 이름", L"이 이름과 맞는 파일·폴더는 맞추지 않습니다. ; 로 구분, * 와 ? 사용 (예: *.bak;node_modules).",
              L"파일 이름이나 경로 중 폴더 이름 하나라도 맞으면 뺍니다.\n"
              L"· *.bak → 확장자가 bak 인 파일\n· node_modules → 그 이름의 폴더 전체\n· ~* → ~ 로 시작하는 이름\n"
              L"뺀 것은 양쪽 모두 건드리지 않습니다 (지우지도 옮기지도 않음).\n"
              L"임시 파일·잠금 파일 같은 종류는 아래 '임시·시스템 파일'에서 체크로 고릅니다.", {s_excl}, eh);
    {
        const wchar_t* sk[5] = {L"임시 파일  (*.tmp)", L"오피스 잠금 파일  (~$*, .~lock.*)", L"폴더 설정 파일  (desktop.ini)", L"축소판 캐시  (Thumbs.db)",
                                L"점(.)으로 시작하는 폴더  (.git, .vscode …)"};
        int cw2 = (s_cw - S(12)) / 2;
        for (int i = 0; i < 5; i++) {
            s_sk[i] = SCtl(h, L"BUTTON", sk[i], BS_AUTOCHECKBOX | WS_TABSTOP, IDS_SKTMP + i);
            MakeChk(s_sk[i]); ChkSet(s_sk[i], ((g_cfg.skipMask >> i) & 1) != 0);
            MoveWindow(s_sk[i], s_cx + (i % 2) * (cw2 + S(12)), y + (i / 2) * (eh + S(4)), i == 4 ? s_cw : cw2, eh, TRUE);   // 마지막 줄은 글이 길어 한 줄 전체
        }
        y = Field(h, y, L"임시·시스템 파일", L"체크한 종류는 맞추지 않고 이 PC에만 둡니다 (탐색기에는 '항상 이 장치에'로 보여 폴더가 '동기화 중'으로 남지 않음).\n"
                  L"늘 빼는 것 (바꿀 수 없음): Mydrive가 받는 동안 쓰는 임시 파일 .mydrive-tmp-…, 시놀로지 내부 폴더 #recycle · @eaDir · #snapshot · @tmp · .SynologyWorkingDirectory",
                  L"프로그램이 저장하거나 문서를 여는 동안 잠깐 만드는 파일들입니다. 맞추면 몇 초 사이에 올렸다 지우기를 되풀이하거나 충돌 사본이 생기기 쉽습니다.\n"
                  L"· 임시 파일: 저장하는 도중에 쓰는 .tmp (끝나면 보통 사라짐)\n"
                  L"· 오피스 잠금 파일: Word·Excel·PowerPoint·리브레오피스가 문서를 열 때 만드는 ~$문서.docx, .~lock.문서#\n"
                  L"· 폴더 설정 파일: 폴더 아이콘·보기 설정 (PC마다 달라도 됨)\n"
                  L"· 축소판 캐시: 예전 Windows가 폴더마다 만드는 미리 보기 모음\n"
                  L"· 점(.)으로 시작하는 폴더: .git · .vscode · .cache 처럼 프로그램이 쓰는 숨은 폴더 — 그 폴더 안의 모든 것을 뺍니다 (기본은 맞춤)\n"
                  L"체크를 풀면 그 종류도 NAS와 맞춥니다. 뺀 파일은 NAS에 없으니 '공간 확보'로 비우지 않습니다.\n"
                  L"이미 맞춘 것을 나중에 빼면 양쪽 파일은 그대로 두고 더 이상 맞추지만 않습니다.", {s_sk[0], s_sk[1], s_sk[2], s_sk[3], s_sk[4]}, eh * 3 + S(8));
    }
    s_maxsize = NumEdit(h, g_cfg.maxSizeMB, IDS_MAXSIZE, s_cx, y, nw);
    Unit(h, L"MB  (0 = 제한 없음)", ux, y);
    y = Field(h, y, L"최대 파일 크기", L"이보다 큰 파일은 맞추지 않습니다.",
              L"큰 영상·디스크 이미지 같은 파일을 빼고 싶을 때 씁니다.\n"
              L"이미 맞춘 파일이 나중에 이 크기를 넘어도 다른 쪽 파일은 지우지 않고 그대로 둡니다.", {s_maxsize}, eh);
    y = Head(h, y, L"안전장치");
    s_limit = NumEdit(h, g_cfg.limit, IDS_LIMIT, s_cx, y, nw);
    Unit(h, L"개 넘게 지워지면 멈추고 묻기", ux, y);
    y = Field(h, y, L"대량 삭제 확인", L"한 번에 이 개수보다 많이 지워지면 삭제만 멈추고 먼저 물어봅니다.",
              L"실수로 폴더를 통째로 지웠을 때 반대쪽까지 지워지는 것을 막습니다.\n이 개수를 넘으면 삭제만 멈추고, 다른 동기화는 계속합니다.\n"
              L"주 창의 [지우기]를 눌러야 반대쪽에서도 지웁니다. 기본값 50.", {s_limit}, eh);
    y = Head(h, y, L"저장 공간");
    s_autoFree = NumEdit(h, g_cfg.autoFreeDays, IDS_AUTOFREE, s_cx, y, nw);
    Unit(h, L"일 동안 안 열면 비우기  (0 = 끔)", ux, y);
    y = Field(h, y, L"오래 안 쓴 파일 비우기", L"이 기간 동안 열지 않은 파일은 디스크 공간을 차지하지 않는 온라인 전용으로 바꿉니다.",
              L"탐색기 연동을 켠 짝에만 적용됩니다.\n"
              L"· NAS에는 그대로 있고, 다시 열면 그 자리에서 받습니다\n"
              L"· '항상 이 PC에 둘 폴더'와 '항상 이 장치에 유지'로 정한 파일은 건드리지 않음\n"
              L"· 지금 열려 있는 파일, 아직 NAS에 다 올라가지 않은 파일도 건드리지 않음\n"
              L"10분마다 살펴봅니다. 예: 30 → 한 달 동안 안 연 파일을 비움.", {s_autoFree}, eh);
    s_panelH[2] = y + S(8);

    // ── 4. 속도와 시작 ──
    s_curPage = 3; s_mark = 0;
    y = Head(h, S(22), L"맞추는 때", true);
    s_quiet = NumEdit(h, g_cfg.quietSec, IDS_QUIET, s_cx, y, nw);
    Unit(h, L"초 동안 조용해지면 맞춤", ux, y);
    y = Field(h, y, L"수정이 몰릴 때", L"파일이 계속 바뀌는 동안은 기다렸다가, 이만큼 조용해지면 한 번에 맞춥니다.",
              L"압축 풀기·빌드·대량 복사처럼 짧은 시간에 수백 개가 바뀔 때\n하나하나 따라가지 않고 끝난 뒤 한 번에 맞춥니다.\n"
              L"방금 저장된 파일도 이 시간이 지나야 올립니다 (아직 쓰는 중일 수 있어서). 기본값 2초.", {s_quiet}, eh);
    s_defer = NumEdit(h, g_cfg.maxDeferSec, IDS_DEFER, s_cx, y, nw);
    Unit(h, L"초가 지나면 그래도 맞춤", ux, y);
    y = Field(h, y, L"최대로 미루는 시간", L"변경이 끝없이 이어져도 이 시간마다 한 번은 맞춥니다.",
              L"예: 로그 파일이 계속 커지는 폴더. 기본값 30초.", {s_defer}, eh);
    s_rescan = NumEdit(h, g_cfg.rescanMin, IDS_RESCAN, s_cx, y, nw);
    Unit(h, L"분마다 전체를 다시 확인", ux, y);
    y = Field(h, y, L"다시 확인 간격", L"알림을 놓쳤을 때를 대비해 이 간격으로 PC 폴더 전체를 다시 훑습니다.",
              L"평소에는 PC 변경은 Windows 알림으로, NAS 변경은 서버 알림으로 바로 압니다.\n"
              L"이 확인은 혹시 놓친 것을 잡는 안전장치입니다. 폴더가 매우 크면 늘리세요. 기본값 5분.", {s_rescan}, eh);
    y = Head(h, y, L"속도");
    s_up = NumEdit(h, g_cfg.upKBps, IDS_UP, s_cx, y, nw);
    Unit(h, L"KB/s  (0 = 제한 없음)", ux, y);
    y = Field(h, y, L"올리기 속도 제한", L"NAS로 올리는 속도의 상한입니다. 모든 연결이 함께 나눠 씁니다.",
              L"집 밖에서 느린 회선을 쓸 때 다른 인터넷 사용이 느려지지 않게 할 때 씁니다.\n1 MB/s = 1024 KB/s.", {s_up}, eh);
    s_down = NumEdit(h, g_cfg.downKBps, IDS_DOWN, s_cx, y, nw);
    Unit(h, L"KB/s  (0 = 제한 없음)", ux, y);
    y = Field(h, y, L"받기 속도 제한", L"NAS에서 받는 속도의 상한입니다.",
              L"모든 연결이 함께 나눠 씁니다. 1 MB/s = 1024 KB/s.", {s_down}, eh);
    y = Head(h, y, L"시작");
    s_startup = SCtl(h, L"BUTTON", L"Windows를 시작할 때 Mydrive 실행", BS_AUTOCHECKBOX | WS_TABSTOP, IDS_STARTUP);
    MakeChk(s_startup); ChkSet(s_startup, g_cfg.startup);
    MoveWindow(s_startup, s_cx, y, s_cw, eh, TRUE);
    y = Field(h, y, L"자동 실행", L"로그인하면 트레이에서 조용히 시작합니다.",
              L"켜 두면 PC를 켤 때마다 동기화가 자동으로 이어집니다.\n끄면 직접 Mydrive를 실행해야 동기화합니다.", {s_startup}, eh);
    s_panelH[3] = y + S(8);

    // ── 5. 업데이트 ──
    s_curPage = 4; s_mark = 0;
    y = Head(h, S(22), L"Mydrive 판", true);
    s_updCheck = SCtl(h, L"BUTTON", L"지금 확인", BS_PUSHBUTTON | WS_TABSTOP, IDS_UPDCHECK);
    MoveWindow(s_updCheck, s_cx, y, S(116), S(30), TRUE);
    s_updInst = SCtl(h, L"BUTTON", L"지금 업데이트", BS_PUSHBUTTON | WS_TABSTOP, IDS_UPDINST);
    SetPropW(s_updInst, L"accent", (HANDLE)1);
    MoveWindow(s_updInst, s_cx + S(124), y, S(132), S(30), TRUE);
    y = Field(h, y, L"이 PC의 판: " MYDRIVE_VERSION_W, L"GitHub에 새 판이 올라와 있는지 확인하고, 있으면 바로 설치합니다.",
              L"새 판은 GitHub 공개 저장소(artchijun/mydrive)의 Releases에서 받습니다. 로그인은 필요 없습니다.\n"
              L"· [지금 확인]: GitHub에 새 판이 있는지 봅니다\n"
              L"· [지금 업데이트]: 받아서 SHA-256으로 손상 여부를 확인한 뒤 설치하고, Mydrive를 다시 켭니다\n"
              L"설정과 동기화 기록, 탐색기 연동은 그대로 둡니다.", {s_updCheck, s_updInst}, S(30));
    s_updRes = SCtl(h, L"STATIC", L"", SS_LEFT | SS_NOPREFIX | SS_ENDELLIPSIS, IDS_UPDRES);
    MoveWindow(s_updRes, s_cx, y - S(12), s_cw, S(20), TRUE);
    s_mark = s_page[4].size();
    y += S(14);
    y = Head(h, y, L"자동 업데이트");
    s_autoUpd = SCtl(h, L"BUTTON", L"새 판이 나오면 자동으로 설치", BS_AUTOCHECKBOX | WS_TABSTOP, IDS_AUTOUPD);
    MakeChk(s_autoUpd); ChkSet(s_autoUpd, g_cfg.autoUpdate);
    MoveWindow(s_autoUpd, s_cx, y, s_cw, eh, TRUE);
    y = Field(h, y, L"자동 설치", L"6시간마다 확인하고, 동기화가 쉬고 있을 때 조용히 설치한 뒤 다시 켭니다.",
              L"켜 두면: 새 판을 찾으면 파일을 옮기는 중이 아닐 때 받아서 설치합니다. 몇 초 동안 트레이 아이콘이 사라졌다가 다시 나타납니다.\n"
              L"끄면: 새 판이 있다고 알림만 띄웁니다. 이 메뉴나 트레이 메뉴에서 직접 설치하세요.", {s_autoUpd}, eh);
    s_panelH[4] = y + S(8);

    // ── 아래 막대: 안내 + [저장] [취소] (늘 보임) ──
    s_curPage = -1;
    int maxH = 0; for (int i = 0; i < kPages; i++) maxH = std::max(maxH, s_panelH[i]);
    HMONITOR mon = MonitorFromWindow(IsWindowVisible(g_main) ? g_main : h, MONITOR_DEFAULTTOPRIMARY);
    MONITORINFO mi{sizeof mi}; GetMonitorInfoW(mon, &mi);
    int workH = mi.rcWork.bottom - mi.rcWork.top;
    // 가장 긴 메뉴에 맞추지 않고 보통 크기로 (넘치는 메뉴는 스크롤). 화면이 작으면 그보다 더 줄임
    int panelH = std::min({maxH, S(480), workH - s_barH - S(90)});
    panelH = std::max(panelH, S(300));
    panelH = std::max(panelH, std::min(s_panelH[1], workH - s_barH - S(90)));   // 동기화 작업 메뉴는 늘 스크롤 없이
    s_panelHNow = panelH;
    int hh = panelH + s_barH;
    for (int i = 0; i < kPages; i++) {
        MoveWindow(s_panel[i], s_sideW, 0, PW, panelH, TRUE);
        SCROLLINFO si{sizeof si, SIF_RANGE | SIF_PAGE | SIF_POS | SIF_DISABLENOSCROLL};
        si.nMin = 0; si.nMax = s_panelH[i] - 1; si.nPage = panelH; si.nPos = 0;
        SetScrollInfo(s_panel[i], SB_VERT, &si, TRUE);
        ShowScrollBar(s_panel[i], SB_VERT, s_panelH[i] > panelH);
    }
    HWND save = Ctl(h, L"BUTTON", L"저장", BS_DEFPUSHBUTTON | WS_TABSTOP, IDS_SAVE);
    HWND cancel = Ctl(h, L"BUTTON", L"취소", BS_PUSHBUTTON | WS_TABSTOP, IDS_CANCEL);
    int by = panelH + (s_barH - S(32)) / 2;
    MoveWindow(save, WW - S(24) - S(204), by, S(100), S(32), TRUE);
    MoveWindow(cancel, WW - S(24) - S(96), by, S(96), S(32), TRUE);
    AddTip(s_tip, save, L"바꾼 내용을 저장하고 동기화를 새 설정으로 다시 시작합니다.");
    s_hint = Ctl(h, L"STATIC", L"바꾼 내용은 [저장]을 눌러야 적용됩니다.", SS_LEFT | SS_NOPREFIX | SS_ENDELLIPSIS, IDS_HINT);
    SendMessageW(s_hint, WM_SETFONT, (WPARAM)g_fontSmall, TRUE);
    MoveWindow(s_hint, s_sideW + s_cx, by + S(8), WW - s_sideW - s_cx - S(250), S(18), TRUE);
    RECT r{0, 0, WW, hh};
    AdjustWindowRectExForDpi(&r, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU, FALSE, 0, g_dpi);
    SetWindowPos(h, nullptr, 0, 0, r.right - r.left, r.bottom - r.top, SWP_NOMOVE | SWP_NOZORDER);
    s_conns = g_cfg.conns;
    if (!s_conns.empty()) s_sel = 0;
    FillConnList();
    s_names.clear(); for (auto& c : s_conns) s_names.push_back(W(c.name));
    ShowPage(g_cfg.token.empty() ? 0 : 1);   // 처음(서버 연결 전)이면 서버 연결, 아니면 맨 위 '동기화 작업'
}

static void StartTest(HWND h);
// ---------- DSM 계정으로 연결 (작은 창) ----------
enum { IDQ_USER = 1200, IDQ_PASS, IDQ_OTP, IDQ_MSG, IDQ_OK, IDQ_CANCEL };
#define WM_APP_PAIRDONE (WM_APP + 40)
static HWND q_win, q_user, q_pass, q_otp, q_msg;
static bool q_err = false;
struct PairResult { int status = 0; std::string token, user, kind, msg; };
static void PairStart(HWND h) {
    std::wstring user = Trim(GetText(q_user)), otp = Trim(GetText(q_otp));
    int n = GetWindowTextLengthW(q_pass); std::wstring pass(n, 0); GetWindowTextW(q_pass, pass.data(), n + 1);
    if (user.empty() || pass.empty()) { q_err = true; SetWindowTextW(q_msg, L"아이디와 비밀번호를 넣어 주세요."); return; }
    std::wstring a = Trim(GetText(s_server)), b = Trim(GetText(s_serverAlt));
    wchar_t pc[MAX_COMPUTERNAME_LENGTH + 1] = {}; DWORD pn = MAX_COMPUTERNAME_LENGTH + 1; GetComputerNameW(pc, &pn);
    json j = {{"account", U(user)}, {"passwd", U(pass)}, {"otp", U(otp)}, {"pc", U(pc)}};
    auto* body = new std::string(j.dump());
    SecureZeroMemory(pass.data(), pass.size() * sizeof(wchar_t));
    q_err = false; SetWindowTextW(q_msg, L"DSM에 확인하는 중…");
    EnableWindow(GetDlgItem(h, IDQ_OK), FALSE);
    std::thread([h, a, b, body] {
        auto* r = new PairResult;
        for (const std::wstring& srv : {a, b}) {
            if (srv.empty()) continue;
            Http http(srv, "");
            Resp p = http.Req(L"POST", "/v1/pair", {}, body, 30);
            r->status = p.status;
            json o = json::parse(p.body, nullptr, false);
            if (!o.is_discarded() && o.is_object()) {
                r->token = o.value("token", std::string()); r->user = o.value("user", std::string());
                r->kind = o.value("error", std::string()); r->msg = o.value("msg", std::string());
            } else r->msg = p.error;
            if (p.status != 0) break;   // 서버에 닿았으면 (성공이든 실패든) 다른 주소는 안 씀
        }
        SecureZeroMemory(body->data(), body->size()); delete body;
        if (!PostMessageW(h, WM_APP_PAIRDONE, 0, (LPARAM)r)) delete r;
    }).detach();
}
static LRESULT CALLBACK PairProc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    LRESULT dr;
    if (DarkMsg(h, msg, wp, lp, dr)) return dr;
    switch (msg) {
    case WM_CREATE: {
        q_win = h; DarkWindow(h);
        const int x = S(24), w = S(372), eh = S(28);
        int y = S(18);
        HWND t = Ctl(h, L"STATIC", L"NAS(DSM) 관리자 계정으로 이 PC를 연결합니다. 비밀번호는 확인에만 쓰고 저장하지 않습니다.", SS_LEFT | SS_NOPREFIX, 0);
        MoveWindow(t, x, y, w, S(40), TRUE); y += S(50);
        auto row = [&](const wchar_t* label, HWND& e, int id, DWORD st) {
            HWND l = Ctl(h, L"STATIC", label, SS_LEFT | SS_NOPREFIX, 0);
            SendMessageW(l, WM_SETFONT, (WPARAM)g_fontBold, TRUE);
            MoveWindow(l, x, y, w, S(20), TRUE); y += S(23);
            e = Ctl(h, L"EDIT", L"", WS_TABSTOP | ES_AUTOHSCROLL | st, id);
            MoveWindow(e, x, y, w, eh, TRUE); y += eh + S(12);
        };
        row(L"DSM 아이디", q_user, IDQ_USER, 0);
        row(L"비밀번호", q_pass, IDQ_PASS, ES_PASSWORD);
        row(L"2단계 인증 코드 (쓰는 경우만)", q_otp, IDQ_OTP, ES_NUMBER);
        q_msg = Ctl(h, L"STATIC", L"집 안(http)에서는 비밀번호가 암호화 없이 NAS로 갑니다. 밖에서는 Tailscale이 암호화합니다.", SS_LEFT | SS_NOPREFIX, IDQ_MSG);
        SendMessageW(q_msg, WM_SETFONT, (WPARAM)g_fontSmall, TRUE);
        MoveWindow(q_msg, x, y, w, S(34), TRUE); y += S(44);
        HWND ok = Ctl(h, L"BUTTON", L"연결", BS_DEFPUSHBUTTON | WS_TABSTOP, IDQ_OK);
        HWND cn = Ctl(h, L"BUTTON", L"취소", BS_PUSHBUTTON | WS_TABSTOP, IDQ_CANCEL);
        MoveWindow(ok, x + w - S(196), y, S(96), S(32), TRUE);
        MoveWindow(cn, x + w - S(92), y, S(92), S(32), TRUE);
        y += S(32) + S(20);
        RECT r{0, 0, x * 2 + w, y};
        AdjustWindowRectExForDpi(&r, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU, FALSE, 0, g_dpi);
        SetWindowPos(h, nullptr, 0, 0, r.right - r.left, r.bottom - r.top, SWP_NOMOVE | SWP_NOZORDER);
        DarkenChildren(h);
        SetFocus(q_user);
        return 0;
    }
    case WM_CTLCOLORSTATIC: {
        HDC dc = (HDC)wp; SetBkColor(dc, kBg);
        SetTextColor(dc, (HWND)lp == q_msg ? (q_err ? RGB(0xFF, 0x6B, 0x74) : kMuted) : kText);
        return (LRESULT)g_brDark;
    }
    case WM_APP_PAIRDONE: {
        std::unique_ptr<PairResult> r((PairResult*)lp);
        EnableWindow(GetDlgItem(h, IDQ_OK), TRUE);
        if (r->status == 200 && !r->token.empty()) {
            SetWindowTextW(q_pass, L"");
            SetWindowTextW(s_token, W(r->token).c_str());
            s_pairUser = r->user;
            SetWindowTextW(s_pairInfo, PairText(r->user).c_str());
            InvalidateRect(s_pairInfo, nullptr, TRUE);
            StartTest(g_settings);   // 받은 열쇠로 바로 연결 시험 (NAS 폴더 목록도 채움)
            DestroyWindow(h);
            return 0;
        }
        q_err = true;
        std::wstring m = r->status == 0 ? L"서버에 닿지 않습니다: " + W(r->msg) : W(r->msg.empty() ? "연결하지 못했습니다" : r->msg);
        SetWindowTextW(q_msg, m.c_str());
        InvalidateRect(q_msg, nullptr, TRUE);
        if (r->kind == "otp") SetFocus(q_otp); else if (r->kind == "login") { SetWindowTextW(q_pass, L""); SetFocus(q_pass); }
        return 0;
    }
    case WM_COMMAND:
        if (LOWORD(wp) == IDQ_OK || LOWORD(wp) == IDOK) { if (IsWindowEnabled(GetDlgItem(h, IDQ_OK))) PairStart(h); }
        else if (LOWORD(wp) == IDQ_CANCEL || LOWORD(wp) == IDCANCEL) DestroyWindow(h);
        return 0;
    case WM_CLOSE: DestroyWindow(h); return 0;
    case WM_DESTROY: EnableWindow(g_settings, TRUE); SetForegroundWindow(g_settings); q_win = nullptr; return 0;
    }
    return DefWindowProcW(h, msg, wp, lp);
}
static void OpenPair(HWND owner) {
    if (q_win) { SetForegroundWindow(q_win); return; }
    RECT o; GetWindowRect(owner, &o);
    HWND h = CreateWindowExW(WS_EX_DLGMODALFRAME | WS_EX_CONTROLPARENT, L"MydrivePair", L"DSM 계정으로 연결", WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU,
                             0, 0, S(440), S(420), owner, nullptr, g_inst, nullptr);
    RECT r; GetWindowRect(h, &r);
    int w = r.right - r.left, hh = r.bottom - r.top;
    SetWindowPos(h, nullptr, (o.left + o.right - w) / 2, (o.top + o.bottom - hh) / 2, 0, 0, SWP_NOSIZE | SWP_NOZORDER);
    EnableWindow(owner, FALSE);
    ShowWindow(h, SW_SHOW);
}

static void SetTestResult(const std::wstring& s, bool ok) { s_testOk = ok ? 1 : 0; SetWindowTextW(s_testRes, s.c_str()); InvalidateRect(s_testRes, nullptr, TRUE); }

static void StartTest(HWND h) {
    std::wstring server = Trim(GetText(s_server));
    std::string token = U(Trim(GetText(s_token)));
    SetTestResult(L"접속하는 중…", true);
    EnableWindow(s_test, FALSE);
    std::thread([h, server, token] {
        Http http(server, token);
        Resp r = http.Req(L"GET", "/v1/roots", {}, nullptr, 8);
        std::string body = r.ok() ? r.body : (r.error.empty() ? r.body : r.error);
        if (r.ok()) {   // 웹 폴더(동기화하지 않는 NAS 폴더: USB 디스크·NAS 전체 등)도 '~이름'으로 — 웹드라이브로만 붙임
            Resp w = http.Req(L"GET", "/v1/webs", {}, nullptr, 8);
            json a = json::parse(body, nullptr, false), b = w.ok() ? json::parse(w.body, nullptr, false) : json();
            if (a.is_array() && b.is_array()) { for (auto& x : b) a.push_back({{"name", "~" + x.value("name", std::string())}, {"items", -1}}); body = a.dump(); }
        }
        auto* out = new std::pair<int, std::string>(r.status, body);
        if (!PostMessageW(h, WM_APP_TESTED, 0, (LPARAM)out)) delete out;
    }).detach();
}
static void OnTested(std::pair<int, std::string>* res) {
    EnableWindow(s_test, TRUE);
    if (res->first == 200) {
        json j = json::parse(res->second, nullptr, false);
        std::wstring names;
        std::wstring cur = s_root ? GetText(s_root) : L"";
        if (s_root) SendMessageW(s_root, CB_RESETCONTENT, 0, 0);
        if (j.is_array()) s_names.clear();
        if (j.is_array()) for (auto& r : j) {
            std::wstring n = W(r.value("name", std::string()));
            s_names.push_back(n);
            if (s_root) SendMessageW(s_root, CB_ADDSTRING, 0, (LPARAM)n.c_str());
            long long it = r.value("items", 0LL);
            names += (names.empty() ? L"" : L", ") + n + (it >= 0 ? Fmt(L"(%lld개)", it) : std::wstring(L"(웹 폴더)"));
        }
        if (s_root) SetWindowTextW(s_root, cur.c_str());
        SetTestResult(L"✔ 연결됨 · NAS 폴더: " + names, true);
    } else if (res->first == 401 || res->first == 403) SetTestResult(L"✖ 토큰이 맞지 않습니다", false);
    else if (res->first == 0) SetTestResult(L"✖ " + W(res->second) + L" · 주소와 NAS 상태를 확인하세요", false);
    else SetTestResult(Fmt(L"✖ 서버 응답 %d", res->first), false);
    delete res;
}
static void BrowseFolder(HWND h, HWND target) {
    IFileOpenDialog* d = nullptr;
    if (FAILED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&d)))) return;
    DWORD o; d->GetOptions(&o); d->SetOptions(o | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM);
    d->SetTitle(L"NAS 폴더와 맞출 PC 폴더 고르기");
    if (SUCCEEDED(d->Show(h))) {
        IShellItem* it = nullptr;
        if (SUCCEEDED(d->GetResult(&it))) {
            PWSTR p = nullptr;
            if (SUCCEEDED(it->GetDisplayName(SIGDN_FILESYSPATH, &p))) { SetWindowTextW(target, p); CoTaskMemFree(p); }
            it->Release();
        }
    }
    d->Release();
}
static bool Overlaps(const std::wstring& a, const std::wstring& b) {
    std::wstring x = a, y = b;
    CharLowerBuffW(x.data(), (DWORD)x.size()); CharLowerBuffW(y.data(), (DWORD)y.size());
    while (!x.empty() && x.back() == L'\\') x.pop_back();
    while (!y.empty() && y.back() == L'\\') y.pop_back();
    if (x == y) return true;
    return (y.size() > x.size() && y.compare(0, x.size(), x) == 0 && y[x.size()] == L'\\') ||
           (x.size() > y.size() && x.compare(0, y.size(), y) == 0 && x[y.size()] == L'\\');
}
// 작업 창의 [확인]: NAS 이름·PC 폴더 확인 (idx = 고치는 작업, -1 = 새 작업)
static bool CheckTask(HWND h, const std::string& name, std::wstring& local, int idx, bool web = false, const std::wstring& mount = L"") {
    while (local.size() > 3 && local.back() == L'\\') local.pop_back();
    if (name.empty()) { MessageBoxW(h, L"NAS 폴더를 고르세요.", L"Mydrive", MB_ICONINFORMATION); return false; }
    if (web && !mount.empty()) {   // 웹드라이브를 드라이브 문자로: PC 폴더는 쓰지 않음
        for (int i = 0; i < (int)s_conns.size(); i++) {
            if (i == idx) continue;
            if (s_conns[i].name == name) { MessageBoxW(h, (L"'" + W(name) + L"'은(는) 이미 다른 작업으로 맞추고 있습니다.").c_str(), L"Mydrive", MB_ICONINFORMATION); return false; }
            if (s_conns[i].web && _wcsicmp(s_conns[i].mount.c_str(), mount.c_str()) == 0) { MessageBoxW(h, (mount + L" 드라이브는 '" + W(s_conns[i].name) + L"' 작업이 이미 씁니다.").c_str(), L"Mydrive", MB_ICONINFORMATION); return false; }
        }
        local.clear();
        return true;
    }
    if (web && (idx < 0 || !s_conns[idx].web || _wcsicmp(s_conns[idx].local.c_str(), local.c_str()) != 0)) {
        DWORD a = GetFileAttributesW(local.c_str());
        if (a != INVALID_FILE_ATTRIBUTES && (!(a & FILE_ATTRIBUTE_DIRECTORY) || !PathIsDirectoryEmptyW(local.c_str()))) {
            MessageBoxW(h, L"웹드라이브는 없는 폴더나 빈 폴더에만 붙일 수 있습니다.\n다른 폴더 이름을 적거나 [받는 방식] 탭에서 '새 드라이브 문자로'를 고르세요.", L"Mydrive", MB_ICONINFORMATION);
            return false;
        }
    }
    if (local.empty()) { MessageBoxW(h, L"이 PC의 폴더를 정하세요.", L"Mydrive", MB_ICONINFORMATION); return false; }
    if (local.size() < 3 || local[1] != L':' || local[2] != L'\\') { MessageBoxW(h, L"PC 폴더는 C:\\폴더 처럼 전체 경로로 적어 주세요.", L"Mydrive", MB_ICONINFORMATION); return false; }
    if (local.size() <= 3) { MessageBoxW(h, L"드라이브 전체(C:\\ 등)는 고를 수 없습니다. 그 안의 폴더를 고르세요.", L"Mydrive", MB_ICONINFORMATION); return false; }
    for (int i = 0; i < (int)s_conns.size(); i++) {
        if (i == idx) continue;
        if (s_conns[i].name == name) { MessageBoxW(h, (L"'" + W(name) + L"'은(는) 이미 다른 작업으로 맞추고 있습니다.").c_str(), L"Mydrive", MB_ICONINFORMATION); return false; }
        if (!s_conns[i].local.empty() && Overlaps(s_conns[i].local, local)) {
            MessageBoxW(h, (L"이 폴더는 '" + W(s_conns[i].name) + L"' 작업의 PC 폴더(" + s_conns[i].local + L")와 겹칩니다.\n서로 안쪽·바깥쪽이 아닌 폴더를 고르세요.").c_str(), L"Mydrive", MB_ICONWARNING);
            return false;
        }
    }
    return true;
}
// [저장] 전에 모든 짝의 PC 폴더 확인 (고른 짝의 PC 폴더 칸을 손으로 고쳤을 수 있음)
static bool CheckConns(HWND h) {
    auto fail = [&](int i, const std::wstring& m) {
        s_sel = i; FillConnList(); ShowConnOptions(); ShowPage(1);
        MessageBoxW(h, m.c_str(), L"Mydrive", MB_ICONINFORMATION); return false;
    };
    for (int i = 0; i < (int)s_conns.size(); i++) {
        auto& l = s_conns[i].local;
        l = Trim(l);
        while (l.size() > 3 && l.back() == L'\\') l.pop_back();
        std::wstring who = L"'" + W(s_conns[i].name) + L"'의 PC 폴더";
        if (s_conns[i].web && !s_conns[i].mount.empty()) { l.clear(); continue; }
        if (l.size() < 3 || l[1] != L':' || l[2] != L'\\') return fail(i, who + L"는 C:\\폴더 처럼 전체 경로로 적어 주세요.");
        if (l.size() <= 3) return fail(i, who + L": 드라이브 전체(C:\\ 등)는 고를 수 없습니다. 그 안의 폴더를 고르세요.");
        for (int k = 0; k < i; k++)
            if (!s_conns[k].local.empty() && Overlaps(s_conns[k].local, l))
                return fail(i, who + L"(" + l + L")가 '" + W(s_conns[k].name) + L"'의 PC 폴더(" + s_conns[k].local + L")와 겹칩니다.\n서로 안쪽·바깥쪽이 아닌 폴더를 고르세요.");
    }
    return true;
}
static void SaveSettings(HWND h) {
    std::wstring server = Trim(GetText(s_server));
    while (!server.empty() && server.back() == L'/') server.pop_back();
    if (server.rfind(L"http://", 0) != 0 && server.rfind(L"https://", 0) != 0) {
        ShowPage(0);
        MessageBoxW(h, L"NAS 서버 주소는 http:// 또는 https:// 로 시작해야 합니다.", L"Mydrive", MB_ICONINFORMATION); return;
    }
    std::wstring serverAlt = Trim(GetText(s_serverAlt));
    while (!serverAlt.empty() && serverAlt.back() == L'/') serverAlt.pop_back();
    if (!serverAlt.empty() && serverAlt.rfind(L"http://", 0) != 0 && serverAlt.rfind(L"https://", 0) != 0) {
        ShowPage(0);
        MessageBoxW(h, L"밖에서 쓸 주소는 http:// 또는 https:// 로 시작해야 합니다 (안 쓰면 비워 두세요).", L"Mydrive", MB_ICONINFORMATION); return;
    }
    std::string token = U(Trim(GetText(s_token)));
    if (token.empty()) { ShowPage(0); MessageBoxW(h, L"[DSM 계정으로 연결]을 하거나 접속 토큰을 넣어 주세요.", L"Mydrive", MB_ICONINFORMATION); return; }
    if (!CheckConns(h)) return;
    auto num = [](HWND e, int def) { std::wstring s = Trim(GetText(e)); return s.empty() ? def : _wtoi(s.c_str()); };
    if (server != g_cfg.server || serverAlt != g_cfg.serverAlt) g_activeServer.clear();   // 주소를 바꾸면 집 주소부터 다시
    if (!s_pairUser.empty()) g_cfg.pairUser = s_pairUser;
    else if (token != g_cfg.token) g_cfg.pairUser.clear();   // 토큰을 손으로 바꿈
    g_cfg.server = server; g_cfg.serverAlt = serverAlt; g_cfg.token = token; g_cfg.conns = s_conns;
    g_cfg.limit = std::max(1, num(s_limit, 50));
    g_cfg.excludes = Trim(GetText(s_excl));
    g_cfg.maxSizeMB = std::max(0, num(s_maxsize, 0));
    g_cfg.autoFreeDays = std::clamp(num(s_autoFree, 0), 0, 3650);
    g_cfg.quietSec = std::clamp(num(s_quiet, 2), 0, 600);
    g_cfg.maxDeferSec = std::clamp(num(s_defer, 30), 1, 3600);
    g_cfg.rescanMin = std::clamp(num(s_rescan, 5), 1, 1440);
    g_cfg.upKBps = std::max(0, num(s_up, 0));
    g_cfg.downKBps = std::max(0, num(s_down, 0));
    g_cfg.startup = ChkGet(s_startup);
    g_cfg.autoUpdate = ChkGet(s_autoUpd);
    g_cfg.skipMask = 0; for (int i = 0; i < 5; i++) if (ChkGet(s_sk[i])) g_cfg.skipMask |= 1 << i;
    SaveCfg(); ApplyStartup();
    StartEngines();
    g_navCheckAt = GetTickCount64() + 20000;   // 탐색 연동을 켜거나 끈 연결의 왼쪽 목록 항목 (Windows가 만든 뒤에 확인)
    DestroyWindow(h);
    RefreshMain();
}

// ---------- 선택 동기화: NAS 폴더 고르기 창 ----------
enum { IDP_LIST = 700, IDP_OK, IDP_CANCEL, IDP_INFO };
static HWND p_win, p_list, p_info;
static std::vector<std::string> p_dirs;
static bool p_guard = false;

// 폴더별 설정: 체크 목록 트리
//   1열 체크 = 이 PC로 받음 (끄면 '맞추지 않음'), 2열 = 항상 이 PC에, 3열 = 먼저 맞추기
//   ▸ 를 누르면 안쪽 폴더가 펼쳐짐. 위 폴더 설정은 안쪽에도 그대로 (흐린 체크로 보임)
static std::vector<char> p_skip, p_keep, p_prio, p_open, p_kids;
static Conn t_conn;            // 작업 창에서 고치는 중인 작업 (확인 전까지는 사본)
static int t_idx = -1;         // 고치는 작업 번호, -1 = 새 작업
static bool p_loaded = false;  // 폴더 목록을 받았는지
static std::string p_loadName; // 받아 온 NAS 폴더 이름
static std::vector<int> p_par, p_rows;
static bool UnderDir(const std::string& p, const std::string& d) { return p.size() > d.size() && p.compare(0, d.size(), d) == 0 && p[d.size()] == '/'; }
static int DirDepth(const std::string& d) { int n = 0; for (char ch : d) if (ch == '/') n++; return n; }
static bool UpFlag(int i, const std::vector<char>& f) { for (int a = p_par[i]; a >= 0; a = p_par[a]) if (f[a]) return true; return false; }
static bool Synced(int i) { return !p_skip[i] && !UpFlag(i, p_skip); }
static bool SomeSkippedInside(int i) { for (int k = i + 1; k < (int)p_dirs.size() && UnderDir(p_dirs[k], p_dirs[i]); k++) if (p_skip[k]) return true; return false; }
static void PickRows() {
    p_rows.clear();
    for (int i = 0; i < (int)p_dirs.size(); i++) {
        bool vis = true; for (int a = p_par[i]; a >= 0; a = p_par[a]) if (!p_open[a]) vis = false;
        if (vis) p_rows.push_back(i);
    }
    p_guard = true;
    int top = ListView_GetTopIndex(p_list);
    ListView_DeleteAllItems(p_list);
    for (int r = 0; r < (int)p_rows.size(); r++) { LVITEMW it{}; it.mask = LVIF_TEXT; it.iItem = r; it.pszText = (LPWSTR)L""; ListView_InsertItem(p_list, &it); }
    if (top > 0 && top < (int)p_rows.size()) ListView_EnsureVisible(p_list, std::min((int)p_rows.size() - 1, top + ListView_GetCountPerPage(p_list) - 1), FALSE);
    p_guard = false;
}
static void PickInfo() {
    int off = 0, k = 0, pr = 0;
    for (int i = 0; i < (int)p_dirs.size(); i++) { off += p_skip[i]; k += p_keep[i]; pr += p_prio[i]; }
    std::wstring s = L"체크를 끄면 그 폴더는 이 PC로 받지 않습니다 (이미 받은 것은 그대로). ▸를 누르면 안쪽 폴더가 보입니다.";
    if (off || k || pr) s += Fmt(L"\n받지 않음 %d · 항상 이 PC에 %d · 먼저 맞추기 %d", off, k, pr);
    SetWindowTextW(p_info, s.c_str());
}
static void PickFill(std::vector<std::string>* dirs) {
    p_dirs = std::move(*dirs); delete dirs;
    const Conn& c = t_conn;
    p_loaded = true;
    size_t n = p_dirs.size();
    p_skip.assign(n, 0); p_keep.assign(n, 0); p_prio.assign(n, 0); p_open.assign(n, 0); p_kids.assign(n, 0); p_par.assign(n, -1);
    auto has = [](const std::vector<std::string>& v, const std::string& d) { return std::find(v.begin(), v.end(), d) != v.end(); };
    for (int i = 0; i < (int)n; i++) {
        for (int k = i - 1; k >= 0; k--) if (UnderDir(p_dirs[i], p_dirs[k])) { p_par[i] = k; p_kids[k] = 1; break; }
        p_skip[i] = has(c.skip, p_dirs[i]); p_keep[i] = has(c.keep, p_dirs[i]); p_prio[i] = has(c.prio, p_dirs[i]);
    }
    // 설정이 있는 폴더는 보이게 위를 펼쳐 둠
    for (int i = 0; i < (int)n; i++) if (p_skip[i] || p_keep[i] || p_prio[i]) for (int a = p_par[i]; a >= 0; a = p_par[a]) p_open[a] = 1;
    PickRows();
    if (p_dirs.empty()) SetWindowTextW(p_info, L"이 NAS 폴더에는 아직 하위 폴더가 없습니다.");
    else PickInfo();
    EnableWindow(GetDlgItem(p_win, IDP_OK), TRUE);
}
static void PickApply() {
    Conn& c = t_conn;
    if (!p_loaded) return;
    auto known = [&](const std::string& d) { return std::find(p_dirs.begin(), p_dirs.end(), d) != p_dirs.end(); };
    std::vector<std::string> skip, keep, prio;
    for (auto& d : c.skip) if (!known(d)) skip.push_back(d);   // 목록에 없는 깊은 폴더의 예전 설정은 그대로
    for (auto& d : c.keep) if (!known(d)) keep.push_back(d);
    for (auto& d : c.prio) if (!known(d)) prio.push_back(d);
    for (int i = 0; i < (int)p_dirs.size(); i++) {
        if (p_skip[i] && !UpFlag(i, p_skip)) skip.push_back(p_dirs[i]);
        if (p_keep[i] && Synced(i)) keep.push_back(p_dirs[i]);
        if (p_prio[i] && Synced(i)) prio.push_back(p_dirs[i]);
    }
    c.skip = skip; c.keep = keep; c.prio = prio;
}
// 받기 체크 바꾸기 (원드라이브 '폴더 선택'처럼: 안쪽 하나를 켜면 위 폴더는 켜고 그 형제는 꺼 둠)
static void PickToggleSync(int i) {
    if (Synced(i)) {
        p_skip[i] = 1;
        for (int k = i + 1; k < (int)p_dirs.size() && UnderDir(p_dirs[k], p_dirs[i]); k++) p_skip[k] = 0;
    } else {
        std::vector<int> chain; for (int a = i; a >= 0; a = p_par[a]) chain.push_back(a);
        for (int c = (int)chain.size() - 1; c >= 1; c--) {   // 위에서부터
            int a = chain[c], next = chain[c - 1];
            if (p_skip[a]) {
                p_skip[a] = 0;
                for (int k = a + 1; k < (int)p_dirs.size() && UnderDir(p_dirs[k], p_dirs[a]); k++) if (p_par[k] == a && k != next) p_skip[k] = 1;
            }
        }
        p_skip[i] = 0;
    }
}
static void DrawBox(HDC dc, RECT b, int state, bool dim) {   // state 0 꺼짐 1 켜짐 2 일부
    COLORREF on = dim ? RGB(0x3E, 0x50, 0x70) : kAccent;
    if (state) FillRound(dc, b, on, S(6));
    else { FillRound(dc, b, dim ? RGB(0x40, 0x45, 0x50) : kMuted, S(6)); RECT in{b.left + 2, b.top + 2, b.right - 2, b.bottom - 2}; FillRound(dc, in, kEdit, S(5)); }
    if (state == 1) {
        HFONT f = CreateFontW(-S(12), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, ui::IconFont());
        HGDIOBJ o = SelectObject(dc, f); SetTextColor(dc, dim ? RGB(0xA8, 0xB4, 0xC8) : RGB(255, 255, 255));
        DrawTextW(dc, L"\xE73E", 1, &b, DT_CENTER | DT_VCENTER | DT_SINGLELINE); SelectObject(dc, o); DeleteObject(f);
    } else if (state == 2) { RECT bar{b.left + S(5), (b.top + b.bottom) / 2 - S(1), b.right - S(5), (b.top + b.bottom) / 2 + S(1)}; FillRound(dc, bar, RGB(255, 255, 255), 2); }
}
// 1열 안의 자리: 펼침 ▸, 체크, 폴더 아이콘, 이름
struct PickGeo { int ex, cb, ic, tx; };
static PickGeo PickGeoOf(int left, int depth) { int x = left + S(6) + depth * S(20); return {x, x + S(22), x + S(48), x + S(72)}; }
static void PickDrawRow(HDC dc, int r) {
    if (r < 0 || r >= (int)p_rows.size()) return;
    int i = p_rows[r];
    RECT row; ListView_GetItemRect(p_list, r, &row, LVIR_BOUNDS);
    bool sel = ListView_GetItemState(p_list, r, LVIS_SELECTED) != 0;
    HBRUSH bg = CreateSolidBrush(sel ? RGB(0x2A, 0x3B, 0x5A) : kEdit); FillRect(dc, &row, bg); DeleteObject(bg);
    SetBkMode(dc, TRANSPARENT);
    HWND hd = ListView_GetHeader(p_list);
    RECT c0, c1, c2; Header_GetItemRect(hd, 0, &c0); Header_GetItemRect(hd, 1, &c1); Header_GetItemRect(hd, 2, &c2);
    int sx = row.left;   // 가로 스크롤
    PickGeo g = PickGeoOf(sx + c0.left, DirDepth(p_dirs[i]));
    int cy = (row.top + row.bottom) / 2, bs = S(18);
    bool syn = Synced(i), upSkip = UpFlag(i, p_skip);
    HFONT ic = CreateFontW(-S(12), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, ui::IconFont());
    HFONT icF = CreateFontW(-S(16), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, ui::IconFont());
    HGDIOBJ of = SelectObject(dc, ic);
    if (p_kids[i]) { RECT e{g.ex, cy - S(10), g.ex + S(18), cy + S(10)}; SetTextColor(dc, kSub); DrawTextW(dc, p_open[i] ? L"\xE70D" : L"\xE76C", 1, &e, DT_CENTER | DT_VCENTER | DT_SINGLELINE); }
    RECT b{g.cb, cy - bs / 2, g.cb + bs, cy + bs / 2};
    DrawBox(dc, b, syn ? (SomeSkippedInside(i) ? 2 : 1) : 0, upSkip);
    RECT fi{g.ic, cy - S(11), g.ic + S(20), cy + S(11)};
    SelectObject(dc, icF); SetTextColor(dc, syn ? RGB(0xE9, 0xB9, 0x49) : kMuted); DrawTextW(dc, L"\xE8B7", 1, &fi, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    SelectObject(dc, g_font); SetTextColor(dc, syn ? kText : kMuted);
    std::wstring nm = W(Leaf(p_dirs[i]));
    RECT tr{g.tx, row.top, sx + c0.right - S(4), row.bottom}; DrawTextW(dc, nm.c_str(), -1, &tr, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX);
    // 2·3열: 항상 이 PC에 / 먼저 맞추기 (받지 않는 폴더는 비활성)
    bool allMode = !t_conn.onDemand;
    for (int col = 1; col <= 2; col++) {
        RECT cr = col == 1 ? c1 : c2;
        int mx = sx + (cr.left + cr.right) / 2;
        RECT bb{mx - bs / 2, cy - bs / 2, mx + bs / 2, cy + bs / 2};
        if (!syn) { DrawBox(dc, bb, 0, true); continue; }
        const auto& f = col == 1 ? p_keep : p_prio;
        bool own = f[i] != 0, up = UpFlag(i, f) || (col == 1 && allMode);
        DrawBox(dc, bb, own || up ? 1 : 0, !own && up);
    }
    SelectObject(dc, of); DeleteObject(ic); DeleteObject(icF);
}
static void PickClick(int r, int x, bool dbl) {
    if (r < 0 || r >= (int)p_rows.size()) return;
    int i = p_rows[r];
    HWND hd = ListView_GetHeader(p_list);
    RECT row; ListView_GetItemRect(p_list, r, &row, LVIR_BOUNDS);
    RECT c0, c1, c2; Header_GetItemRect(hd, 0, &c0); Header_GetItemRect(hd, 1, &c1); Header_GetItemRect(hd, 2, &c2);
    int lx = x - row.left;
    PickGeo g = PickGeoOf(c0.left, DirDepth(p_dirs[i]));
    bool changed = false;
    if (lx >= c1.left && lx < c1.right) { if (Synced(i) && !(UpFlag(i, p_keep))) { p_keep[i] = !p_keep[i]; changed = true; } }
    else if (lx >= c2.left && lx < c2.right) { if (Synced(i) && !(UpFlag(i, p_prio))) { p_prio[i] = !p_prio[i]; changed = true; } }
    else if (p_kids[i] && (dbl || (lx >= g.ex - S(4) && lx < g.cb - S(2)))) { p_open[i] = !p_open[i]; PickRows(); ListView_SetItemState(p_list, r, LVIS_SELECTED, LVIS_SELECTED); return; }
    else if (lx >= g.cb - S(4) && lx < g.ic) { PickToggleSync(i); changed = true; }
    if (changed) { InvalidateRect(p_list, nullptr, FALSE); PickInfo(); }
}
enum { IDP_TAB0 = 720, IDP_TAB1, IDP_TAB2, IDP_OD, IDP_ALL, IDP_ROOT, IDP_LOCAL, IDP_BROWSE, IDP_LINE, IDP_DESC };
enum { IDP_WEB = 740, IDP_MFOLDER, IDP_MDRIVE, IDP_LETTER, IDP_MLABEL, IDP_WFSP, IDP_WDESC };
static std::vector<HWND> p_pg[3];
static HWND p_od, p_all, p_local, p_rootBox;
static HWND p_web, p_mFolder, p_mDrive, p_letter, p_mLabel, p_wfsp, p_wdesc, p_mdesc, p_tip;   // 웹드라이브 고르기
static void PickTab(HWND h, int k);
static std::wstring PickLetter() { std::wstring s = GetText(p_letter); return s.size() >= 2 ? s.substr(0, 2) : L""; }
// 받는 방식 탭: 고른 것 표시 + 웹드라이브면 '연결할 곳' 보이기
static HWND p_browse, p_gd;
// 동기화 방식 탭: 받는 방식에 따라 아래 '이 PC의 폴더 / 연결할 곳' 칸을 바꿔 보여 줌
static void PickModeUi() {
    const int x = S(16), CW = S(530), eh = S(28), bw = S(40);
    bool web = t_conn.web, letter = web && !t_conn.mount.empty();
    ChkSet(p_od, !web && t_conn.onDemand); ChkSet(p_all, !web && !t_conn.onDemand); ChkSet(p_web, web);
    SetWindowTextW(p_mLabel, web ? L"연결할 곳" : L"이 PC의 폴더");
    for (HWND c : {p_mFolder, p_mDrive, p_letter, p_wdesc}) ShowWindow(c, web ? SW_SHOW : SW_HIDE);
    for (HWND c : {p_gd, p_mdesc}) ShowWindow(c, web ? SW_HIDE : SW_SHOW);
    int ly = web ? S(444) : S(340);
    MoveWindow(p_local, x, ly, CW - bw - S(6), eh, TRUE); MoveWindow(p_browse, x + CW - bw, ly, bw, eh, TRUE);
    for (HWND c : {p_local, p_browse}) ShowWindow(c, letter ? SW_HIDE : SW_SHOW);
    ChkSet(p_mFolder, !letter); ChkSet(p_mDrive, letter);
    EnableWindow(p_letter, letter);
    ShowWindow(p_wfsp, web && !WinFspInstalled() ? SW_SHOW : SW_HIDE);
    for (HWND c : {p_od, p_all, p_web, p_mFolder, p_mDrive}) InvalidateRect(c, nullptr, TRUE);
    InvalidateRect(p_mLabel, nullptr, TRUE);
}
#define WM_APP_WFSP (WM_APP + 41)
// WinFsp 설치: GitHub 의 최신 설치 파일(.msi)을 받아 실행 (Windows 가 관리자 승인을 물음)
static void InstallWinFsp(HWND h) {
    EnableWindow(p_wfsp, FALSE); SetWindowTextW(p_wfsp, L"받는 중…");
    std::thread([h] {
        std::wstring err;
        Http api(L"https://api.github.com", ""); api.plain = true;
        Resp r = api.Req(L"GET", "/repos/winfsp/winfsp/releases/latest", {}, nullptr, 30);
        std::string url;
        json j = r.ok() ? json::parse(r.body, nullptr, false) : json();
        if (j.is_object()) for (auto& a : j.value("assets", json::array())) {
            std::string u = a.value("browser_download_url", std::string());
            if (u.size() > 4 && u.compare(u.size() - 4, 4, ".msi") == 0 && u.rfind("https://github.com/winfsp/winfsp/releases/download/", 0) == 0) { url = u; break; }
        }
        std::wstring msi = g_dataDir + L"\\winfsp-setup.msi";
        if (url.empty()) err = L"WinFsp 설치 파일을 찾지 못했습니다" + (r.ok() ? std::wstring() : L" (" + W(r.error.empty() ? std::to_string(r.status) : r.error) + L")");
        else {
            Http dl(L"https://github.com", ""); dl.plain = true;
            Resp d = dl.Download(url.substr(18), {}, msi);
            if (!d.ok()) err = L"WinFsp 설치 파일을 받지 못했습니다 (" + W(d.error.empty() ? std::to_string(d.status) : d.error) + L")";
            else {
                SetFileAttributesW(msi.c_str(), FILE_ATTRIBUTE_NORMAL);
                std::wstring args = L"/i \"" + msi + L"\" /passive";
                SHELLEXECUTEINFOW se{sizeof se}; se.fMask = SEE_MASK_NOCLOSEPROCESS; se.lpFile = L"msiexec.exe"; se.lpParameters = args.c_str(); se.nShow = SW_SHOWNORMAL;
                if (!ShellExecuteExW(&se)) err = L"WinFsp 설치를 시작하지 못했습니다";
                else if (se.hProcess) { WaitForSingleObject(se.hProcess, INFINITE); CloseHandle(se.hProcess); }
                DeleteFileW(msi.c_str());
            }
        }
        auto* m = new std::wstring(err);
        if (!PostMessageW(h, WM_APP_WFSP, 0, (LPARAM)m)) delete m;
    }).detach();
}
// 이 NAS 폴더의 하위 폴더 목록을 받아 옴 (폴더 탭)
static void PickLoad(HWND h, const std::string& root) {
    p_loadName = root; p_loaded = false;
    p_guard = true; ListView_DeleteAllItems(p_list); p_guard = false;
    SetWindowTextW(p_info, L"NAS 폴더 목록을 가져오는 중…");
    std::wstring server = Trim(GetText(s_server)); std::string token = U(Trim(GetText(s_token)));
    std::thread([h, server, token, root] {
        Http http(server, token);
        Resp r = http.Req(L"GET", "/v1/snapshot", {{"root", root}}, nullptr, 120);
        auto* dirs = new std::vector<std::string>();
        json j = r.ok() ? json::parse(r.body, nullptr, false) : json();
        if (j.is_object()) for (auto& e : j["entries"]) {
            if (!e.value("d", false)) continue;
            std::string p = e.value("p", std::string());
            int depth = 0; for (char ch : p) if (ch == '/') depth++;
            if (depth <= 3) dirs->push_back(p);
        }
        std::sort(dirs->begin(), dirs->end(), [](const std::string& a, const std::string& b) {   // 부모 바로 아래에 자식
            size_t n = std::min(a.size(), b.size());
            for (size_t i = 0; i < n; i++) {
                unsigned char x = a[i] == '/' ? 1 : (unsigned char)a[i], y = b[i] == '/' ? 1 : (unsigned char)b[i];
                if (x != y) return x < y;
            }
            return a.size() < b.size();
        });
        if (!r.ok()) dirs->push_back("\x01" + (r.error.empty() ? std::to_string(r.status) : r.error));
        dirs->push_back("\x02" + root);
        if (!PostMessageW(h, WM_APP_PICKED, 0, (LPARAM)dirs)) delete dirs;
    }).detach();
}
static std::string PickRootName() { return t_idx >= 0 ? t_conn.name : U(Trim(GetText(p_rootBox))); }
// 탭: 0 동기화 방식 (NAS 폴더 · 받는 방식 · 이 PC의 폴더/연결할 곳) / 1 받을 폴더 (체크 목록)
static void PickTab(HWND h, int k) {
    s_dlgTab = k;
    for (int t = 0; t < 2; t++) for (HWND c : p_pg[t]) ShowWindow(c, t == k ? SW_SHOW : SW_HIDE);
    for (int t = 0; t < 2; t++) InvalidateRect(GetDlgItem(h, IDP_TAB0 + t), nullptr, TRUE);
    if (k == 0) { PickModeUi(); return; }
    for (HWND c : {p_mFolder, p_mDrive, p_letter, p_wfsp, p_wdesc, p_local, p_browse, p_gd, p_mdesc}) ShowWindow(c, SW_HIDE);
    if (t_conn.web) {
        ShowWindow(p_list, SW_HIDE);
        SetWindowTextW(p_info, L"웹드라이브는 NAS 폴더 전체를 드라이브로 보여 줍니다. 여기서 고를 폴더는 없습니다.");
        return;
    }
    std::string root = PickRootName();
    if (root.empty()) { p_guard = true; ListView_DeleteAllItems(p_list); p_guard = false; p_loadName.clear(); p_loaded = false; SetWindowTextW(p_info, L"먼저 [동기화 방식] 탭에서 NAS 폴더를 고르세요."); }
    else if (root != p_loadName) PickLoad(h, root);
}
static LRESULT CALLBACK PickProc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    LRESULT dr;
    if (DarkMsg(h, msg, wp, lp, dr)) return dr;
    switch (msg) {
    case WM_CREATE: {
        p_win = h; p_loaded = false; p_loadName.clear(); p_dirs.clear();
        for (auto& v : p_pg) v.clear();
        RECT pc; GetClientRect(h, &pc); const int ch = pc.bottom, x = S(16), CW = S(530), eh = S(28), bw = S(40);
        const wchar_t* tn[2] = {L"동기화 방식", L"받을 폴더"};
        for (int t = 0; t < 2; t++) {
            HWND b = Ctl(h, L"BUTTON", tn[t], BS_OWNERDRAW | WS_TABSTOP, IDP_TAB0 + t);
            SetPropW(b, L"ptab", (HANDLE)(INT_PTR)(t + 1));
            MoveWindow(b, x + t * S(118), S(8), S(114), S(38), TRUE);
        }
        HWND ln = Ctl(h, L"STATIC", L"", SS_LEFT, IDP_LINE); MoveWindow(ln, x, S(46), CW, 1, TRUE);
        p_tip = MakeTip(h);

        // ── 탭 0: 동기화 방식 ──
        HWND l1 = Ctl(h, L"STATIC", L"NAS 폴더", SS_LEFT | SS_NOPREFIX, 0);
        SendMessageW(l1, WM_SETFONT, (WPARAM)g_fontBold, TRUE); MoveWindow(l1, x, S(58), CW, S(20), TRUE);
        if (t_idx < 0) {
            p_rootBox = Ctl(h, WC_COMBOBOXW, L"", CBS_DROPDOWN | CBS_AUTOHSCROLL | WS_VSCROLL | WS_TABSTOP, IDP_ROOT);
            MoveWindow(p_rootBox, x, S(80), CW, S(220), TRUE);
            for (auto& n : s_names) {
                bool used = false; for (auto& c : s_conns) if (W(c.name) == n) used = true;
                if (!used) SendMessageW(p_rootBox, CB_ADDSTRING, 0, (LPARAM)n.c_str());
            }
            SendMessageW(p_rootBox, CB_SETCUEBANNER, 0, (LPARAM)L"NAS 폴더 고르기 (~ 는 웹 폴더 · 웹드라이브 전용)");
            s_root = p_rootBox;   // [연결 시험] 결과가 오면 목록이 채워짐
            if (!Trim(GetText(s_token)).empty()) StartTest(g_settings);
        } else {
            p_rootBox = Ctl(h, L"STATIC", W(t_conn.name).c_str(), SS_LEFT | SS_NOPREFIX, IDP_ROOT);
            MoveWindow(p_rootBox, x, S(84), CW, S(22), TRUE);
        }
        HWND lm = Ctl(h, L"STATIC", L"받는 방식", SS_LEFT | SS_NOPREFIX, 0);
        SendMessageW(lm, WM_SETFONT, (WPARAM)g_fontBold, TRUE); MoveWindow(lm, x, S(120), CW, S(20), TRUE);
        p_od = Ctl(h, L"BUTTON", L"필요할 때 받기\n새 파일은 탐색기에 자리만 두고(구름), 열 때 받음 · PC 공간 절약", BS_OWNERDRAW | WS_TABSTOP, IDP_OD);
        p_all = Ctl(h, L"BUTTON", L"모두 받아 두기\n모든 파일을 이 PC에 받아 둠 (초록 체크) · 인터넷 없어도 열림", BS_OWNERDRAW | WS_TABSTOP, IDP_ALL);
        p_web = Ctl(h, L"BUTTON", L"웹드라이브처럼 사용\nPC에 파일을 두지 않음 · 열 때 NAS에서 바로 읽고 저장하면 바로 올림 · 인터넷 필요", BS_OWNERDRAW | WS_TABSTOP, IDP_WEB);
        for (HWND b : {p_od, p_all, p_web}) SetPropW(b, L"opt", (HANDLE)1);
        MoveWindow(p_od, x, S(142), CW, S(50), TRUE); MoveWindow(p_all, x, S(198), CW, S(50), TRUE); MoveWindow(p_web, x, S(254), CW, S(50), TRUE);
        AddTip(p_tip, p_od, L"탐색기에는 NAS의 모든 파일이 보이지만, 내용은 열 때 받습니다. 받은 파일은 이 PC에 남고, 오른쪽 클릭 → Mydrive → 공간 확보로 다시 비울 수 있습니다.");
        AddTip(p_tip, p_all, L"NAS의 모든 파일을 이 PC에 받아 둡니다. 인터넷이 없어도 열리지만 PC 공간을 그만큼 씁니다.");
        AddTip(p_tip, p_web, L"PC에 파일을 받아 두지 않고 NAS 폴더를 드라이브처럼 씁니다. 파일을 열면 NAS에서 바로 읽고, 저장하고 닫으면 바로 NAS로 올립니다. 인터넷(NAS 연결)이 없으면 열리지 않습니다. WinFsp 드라이버가 필요합니다(처음 한 번 설치, 관리자 승인).");
        p_mLabel = Ctl(h, L"STATIC", L"이 PC의 폴더", SS_LEFT | SS_NOPREFIX, IDP_MLABEL);
        SendMessageW(p_mLabel, WM_SETFONT, (WPARAM)g_fontBold, TRUE); MoveWindow(p_mLabel, x, S(316), CW, S(20), TRUE);
        // 동기화(필요할 때·모두 받기): 이 PC의 폴더
        p_local = Ctl(h, L"EDIT", t_conn.local.c_str(), WS_TABSTOP | ES_AUTOHSCROLL, IDP_LOCAL);
        SendMessageW(p_local, EM_SETCUEBANNER, TRUE, (LPARAM)L"예: D:\\작업");
        p_browse = Ctl(h, L"BUTTON", L"\xE838", BS_PUSHBUTTON | WS_TABSTOP, IDP_BROWSE);
        SetPropW(p_browse, L"ico", (HANDLE)1);
        AddTip(p_tip, p_local, L"NAS 폴더와 같은 내용으로 맞출 이 PC의 폴더입니다 (웹드라이브면 NAS 내용이 보일 자리).");
        p_gd = Ctl(h, L"STATIC", t_idx < 0 ? L"없는 폴더면 새로 만듭니다. 이미 파일이 있어도 지우지 않고, 같은 이름·다른 내용이면 둘 다 남깁니다."
                                         : L"바꾸면 [저장] 뒤 새 폴더에서 처음 맞추기를 다시 합니다. 원래 폴더의 파일은 그대로 남습니다.", SS_LEFT | SS_NOPREFIX, IDP_DESC + 1);
        SendMessageW(p_gd, WM_SETFONT, (WPARAM)g_fontSmall, TRUE); MoveWindow(p_gd, x, S(374), CW, S(36), TRUE);
        p_mdesc = Ctl(h, L"STATIC", L"폴더마다 다르게 하려면 [받을 폴더] 탭의 '항상 이 PC에' 칸을 체크하세요.\n탐색기에서 오른쪽 클릭 → Mydrive → 항상 이 장치에 유지 / 공간 확보로도 바꿀 수 있습니다.", SS_LEFT | SS_NOPREFIX, IDP_DESC);
        SendMessageW(p_mdesc, WM_SETFONT, (WPARAM)g_fontSmall, TRUE); MoveWindow(p_mdesc, x, S(414), CW, S(44), TRUE);
        // 웹드라이브: 연결할 곳
        p_mDrive = Ctl(h, L"BUTTON", L"새 드라이브 문자로\n탐색기 '내 PC'에 네트워크 드라이브로 보임", BS_OWNERDRAW | WS_TABSTOP, IDP_MDRIVE);
        p_mFolder = Ctl(h, L"BUTTON", L"PC 폴더에 붙이기\n아래 폴더 자리에 NAS 내용이 보임 · 그 폴더는 없거나 비어 있어야 함", BS_OWNERDRAW | WS_TABSTOP, IDP_MFOLDER);
        SetPropW(p_mFolder, L"opt", (HANDLE)1); SetPropW(p_mDrive, L"opt", (HANDLE)1);
        MoveWindow(p_mDrive, x, S(340), CW - S(96), S(46), TRUE); MoveWindow(p_mFolder, x, S(392), CW, S(46), TRUE);
        p_letter = Ctl(h, WC_COMBOBOXW, L"", CBS_DROPDOWNLIST | WS_VSCROLL | WS_TABSTOP, IDP_LETTER);
        MoveWindow(p_letter, x + CW - S(88), S(349), S(88), S(300), TRUE);
        {
            std::wstring fl = FreeDriveLetters(), cur = t_conn.mount.size() >= 1 ? std::wstring(1, (wchar_t)towupper(t_conn.mount[0])) : L"";
            if (!cur.empty() && fl.find(cur[0]) == std::wstring::npos) { fl += cur; std::sort(fl.begin(), fl.end()); }
            int sel = -1, idxM = -1;
            for (size_t i = 0; i < fl.size(); i++) {
                std::wstring s = std::wstring(1, fl[i]) + L":";
                SendMessageW(p_letter, CB_ADDSTRING, 0, (LPARAM)s.c_str());
                if (!cur.empty() && fl[i] == cur[0]) sel = (int)i;
                if (fl[i] == L'M') idxM = (int)i;
            }
            SendMessageW(p_letter, CB_SETCURSEL, sel >= 0 ? sel : idxM >= 0 ? idxM : (int)fl.size() - 1, 0);
        }
        p_wdesc = Ctl(h, L"STATIC", L"목록은 NAS에서 그때그때 보여 주고, 프로그램이 읽는 조각만 잠시 받습니다. 오프라인이면 열리지 않고 탐색기 상태 아이콘도 없습니다.\n"
                                     L"WinFsp - Windows File System Proxy, Copyright (C) Bill Zissimopoulos · github.com/winfsp/winfsp", SS_LEFT | SS_NOPREFIX, IDP_WDESC);
        SendMessageW(p_wdesc, WM_SETFONT, (WPARAM)g_fontSmall, TRUE); MoveWindow(p_wdesc, x, S(480), CW, S(42), TRUE);
        p_wfsp = Ctl(h, L"BUTTON", L"WinFsp 설치", BS_PUSHBUTTON | WS_TABSTOP, IDP_WFSP);
        MoveWindow(p_wfsp, x, ch - S(46), S(150), S(32), TRUE);
        AddTip(p_tip, p_mFolder, L"아래에 적은 PC 폴더 자리에 NAS 내용이 보입니다. 그 폴더는 아직 없거나 비어 있어야 합니다.");
        AddTip(p_tip, p_mDrive, L"오른쪽에서 고른 문자(예: M:)로 탐색기 '내 PC'에 네트워크 드라이브가 생깁니다.");
        AddTip(p_tip, p_letter, L"지금 비어 있는 드라이브 문자만 보입니다.");
        AddTip(p_tip, p_wfsp, L"WinFsp 공식 설치 파일(GitHub)을 받아 실행합니다. Windows가 관리자 승인을 묻습니다.");
        p_pg[0] = {l1, p_rootBox, lm, p_od, p_all, p_web, p_mLabel};

        // ── 탭 1: 받을 폴더 (체크 목록 트리) ──
        p_list = Ctl(h, WC_LISTVIEWW, L"", LVS_REPORT | LVS_SINGLESEL | LVS_NOSORTHEADER | WS_TABSTOP, IDP_LIST, WS_EX_CLIENTEDGE);
        ListView_SetExtendedListViewStyle(p_list, LVS_EX_FULLROWSELECT | LVS_EX_DOUBLEBUFFER);
        AddCol(p_list, 0, L"이 PC로 받을 폴더", 318); AddCol(p_list, 1, L"항상 이 PC에", 96); AddCol(p_list, 2, L"먼저 맞추기", 90);
        ListView_SetImageList(p_list, ImageList_Create(1, S(30), ILC_COLOR32, 1, 1), LVSIL_SMALL);   // 줄 높이
        MoveWindow(p_list, x, S(58), CW, ch - S(58) - S(104), TRUE);
        p_info = Ctl(h, L"STATIC", L"", SS_LEFT | SS_NOPREFIX, IDP_INFO);
        SendMessageW(p_info, WM_SETFONT, (WPARAM)g_fontSmall, TRUE);
        MoveWindow(p_info, x, ch - S(96), CW, S(40), TRUE);
        p_pg[1] = {p_list, p_info};
        p_pg[2].clear();

        HWND ok = Ctl(h, L"BUTTON", t_idx < 0 ? L"만들기" : L"확인", BS_DEFPUSHBUTTON | WS_TABSTOP, IDP_OK);
        HWND cancel = Ctl(h, L"BUTTON", L"취소", BS_PUSHBUTTON | WS_TABSTOP, IDP_CANCEL);
        MoveWindow(ok, x + CW - S(200), ch - S(46), S(96), S(32), TRUE);
        MoveWindow(cancel, x + CW - S(96), ch - S(46), S(96), S(32), TRUE);
        DarkWindow(h); DarkenChildren(h);
        PickTab(h, 0);
        return 0;
    }
    case WM_APP_WFSP: {
        auto* m = (std::wstring*)lp;
        SetWindowTextW(p_wfsp, L"WinFsp 설치"); EnableWindow(p_wfsp, TRUE);
        if (!m->empty()) MessageBoxW(h, m->c_str(), L"Mydrive", MB_ICONWARNING);
        else if (WinFspInstalled()) MessageBoxW(h, L"WinFsp를 설치했습니다. [확인]을 누르고 설정을 저장하면 웹드라이브를 붙입니다.", L"Mydrive", MB_ICONINFORMATION);
        delete m;
        PickModeUi();
        return 0;
    }
    case WM_APP_PICKED: {
        auto* d = (std::vector<std::string>*)lp;
        std::string root; if (!d->empty() && d->back()[0] == '\x02') { root = d->back().substr(1); d->pop_back(); }
        if (root != p_loadName) { delete d; return 0; }   // 그 사이 다른 NAS 폴더를 고름
        if (!d->empty() && d->back()[0] == '\x01') { SetWindowTextW(p_info, (L"NAS 폴더 목록을 가져오지 못했습니다: " + W(d->back().substr(1))).c_str()); delete d; return 0; }
        PickFill(d);
        return 0;
    }
    case WM_CTLCOLORSTATIC: {
        HDC dc = (HDC)wp; SetBkColor(dc, kBg);
        int id = GetDlgCtrlID((HWND)lp);
        if (id == IDP_LINE) return (LRESULT)s_brLine;
        SetTextColor(dc, (HWND)lp == p_info || id == IDP_DESC || id == IDP_DESC + 1 || id == IDP_WDESC ? kMuted : kText);
        return (LRESULT)g_brDark;
    }
    case WM_NOTIFY: {
        auto* nm = (NMHDR*)lp;
        if (nm->hwndFrom == p_list && (nm->code == NM_CLICK || nm->code == NM_DBLCLK)) {
            auto* ia = (NMITEMACTIVATE*)lp;
            LVHITTESTINFO ht{}; ht.pt = ia->ptAction; ListView_SubItemHitTest(p_list, &ht);
            PickClick(ht.iItem, ia->ptAction.x, nm->code == NM_DBLCLK);
            return 0;
        }
        if (nm->hwndFrom == p_list && nm->code == LVN_KEYDOWN) {
            auto* kd = (NMLVKEYDOWN*)lp; int r = ListView_GetNextItem(p_list, -1, LVNI_SELECTED);
            if (r >= 0 && r < (int)p_rows.size()) {
                int i = p_rows[r];
                if (kd->wVKey == VK_SPACE) { PickToggleSync(i); InvalidateRect(p_list, nullptr, FALSE); PickInfo(); }
                else if ((kd->wVKey == VK_RIGHT && !p_open[i]) || (kd->wVKey == VK_LEFT && p_open[i])) { if (p_kids[i]) { p_open[i] = !p_open[i]; PickRows(); ListView_SetItemState(p_list, r, LVIS_SELECTED | LVIS_FOCUSED, LVIS_SELECTED | LVIS_FOCUSED); } }
            }
            return 0;
        }
        if (nm->hwndFrom == p_list && nm->code == NM_CUSTOMDRAW) {
            auto* cd = (NMLVCUSTOMDRAW*)lp;
            if (cd->nmcd.dwDrawStage == CDDS_PREPAINT) return CDRF_NOTIFYITEMDRAW;
            if (cd->nmcd.dwDrawStage == CDDS_ITEMPREPAINT) { PickDrawRow(cd->nmcd.hdc, (int)cd->nmcd.dwItemSpec); return CDRF_SKIPDEFAULT; }
        }
        return 0;
    }
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case IDP_TAB0: case IDP_TAB1: case IDP_TAB2: PickTab(h, LOWORD(wp) - IDP_TAB0); break;
        case IDP_OD: case IDP_ALL:
            if (!PickRootName().empty() && PickRootName()[0] == '~') { MessageBoxW(h, L"웹 폴더(~)는 동기화하지 않고 웹드라이브로만 붙입니다.\n(NAS 전체·USB 디스크처럼 큰 곳을 PC에 받아 두지 않도록)", L"Mydrive", MB_ICONINFORMATION); break; }
            t_conn.onDemand = LOWORD(wp) == IDP_OD; t_conn.web = false;
            PickModeUi();
            InvalidateRect(p_list, nullptr, FALSE); PickInfo();
            break;
        case IDP_WEB:
            if (!t_conn.web && Trim(GetText(p_local)).empty()) t_conn.mount = PickLetter();   // PC 폴더를 아직 안 정했으면 드라이브 문자로
            t_conn.web = true;
            PickModeUi();
            break;
        case IDP_MFOLDER: t_conn.mount.clear(); PickModeUi(); break;
        case IDP_MDRIVE: t_conn.mount = PickLetter(); PickModeUi(); break;
        case IDP_LETTER: if (HIWORD(wp) == CBN_SELCHANGE && !t_conn.mount.empty()) t_conn.mount = PickLetter(); break;
        case IDP_WFSP: InstallWinFsp(h); break;
        case IDP_BROWSE: BrowseFolder(h, p_local); break;
        case IDP_ROOT: if (HIWORD(wp) == CBN_SELCHANGE || HIWORD(wp) == CBN_EDITCHANGE) {
                p_loaded = false;
                // '~' 로 시작 = 웹 폴더 → 웹드라이브로만 (서버에 전체 목록이 없어 동기화할 수 없음)
                wchar_t b[300] = {}; int sel = (int)SendMessageW(p_rootBox, CB_GETCURSEL, 0, 0);
                if (HIWORD(wp) == CBN_SELCHANGE && sel >= 0) SendMessageW(p_rootBox, CB_GETLBTEXT, sel, (LPARAM)b); else GetWindowTextW(p_rootBox, b, 300);
                if (b[0] == L'~' && !t_conn.web) { if (Trim(GetText(p_local)).empty()) t_conn.mount = PickLetter(); t_conn.web = true; PickModeUi(); }
            } break;
        case IDP_OK: case IDOK: {
            std::string name = PickRootName();
            std::wstring local = Trim(GetText(p_local));
            if (t_conn.web && !t_conn.mount.empty()) t_conn.mount = PickLetter();
            if (!name.empty() && name[0] == '~' && !t_conn.web) { t_conn.web = true; if (local.empty()) t_conn.mount = PickLetter(); PickModeUi(); }
            if (!CheckTask(h, name, local, t_idx, t_conn.web, t_conn.mount)) { PickTab(h, 0); break; }
            if (t_conn.web && !WinFspInstalled() &&
                MessageBoxW(h, L"웹드라이브에는 WinFsp 드라이버가 필요합니다. 지금 설치할까요?\n(GitHub의 공식 설치 파일을 받아 실행합니다. Windows가 관리자 승인을 묻습니다.)", L"Mydrive", MB_YESNO | MB_ICONQUESTION) == IDYES) {
                PickTab(h, 0); InstallWinFsp(h); break;
            }
            if (!t_conn.web && p_loaded && p_loadName == name) PickApply();
            t_conn.name = name; t_conn.local = local;
            if (t_idx < 0) { s_conns.push_back(t_conn); s_sel = (int)s_conns.size() - 1; }
            else s_conns[t_idx] = t_conn;
            FillConnList(); ShowConnOptions();
            DestroyWindow(h);
            break;
        }
        case IDP_CANCEL: case IDCANCEL: DestroyWindow(h); break;
        }
        return 0;
    case WM_CLOSE: DestroyWindow(h); return 0;
    case WM_DESTROY:
        if (s_root == p_rootBox) s_root = nullptr;
        EnableWindow(g_settings, TRUE); SetForegroundWindow(g_settings); p_win = nullptr; return 0;
    }
    return DefWindowProcW(h, msg, wp, lp);
}
static void OpenTask(HWND owner, int idx) {
    if (p_win) { SetForegroundWindow(p_win); return; }
    if (idx >= (int)s_conns.size()) return;
    t_idx = idx;
    if (idx >= 0) t_conn = s_conns[idx]; else { t_conn = Conn(); t_conn.onDemand = true; }
    MONITORINFO mi{sizeof mi}; GetMonitorInfoW(MonitorFromWindow(owner, MONITOR_DEFAULTTONEAREST), &mi);
    RECT r{0, 0, S(562), std::min<int>(S(590), mi.rcWork.bottom - mi.rcWork.top - S(60))};
    AdjustWindowRectExForDpi(&r, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU, FALSE, 0, g_dpi);
    RECT o; GetWindowRect(owner, &o);
    int w = r.right - r.left, hh = r.bottom - r.top;
    std::wstring title = idx < 0 ? std::wstring(L"새 동기화 작업") : L"동기화 작업 편집 — " + W(t_conn.name);
    HWND h = CreateWindowExW(WS_EX_DLGMODALFRAME | WS_EX_CONTROLPARENT, L"MydrivePick", title.c_str(), WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU,
                             std::clamp<int>((o.left + o.right - w) / 2, mi.rcWork.left, mi.rcWork.right - w), std::clamp<int>((o.top + o.bottom - hh) / 2, mi.rcWork.top, mi.rcWork.bottom - hh), w, hh, owner, nullptr, g_inst, nullptr);
    EnableWindow(owner, FALSE);
    ShowWindow(h, SW_SHOW);
}

// 작업 표를 작업 수만큼 키우고, 넘치면 설정 창도 키움 (화면 높이까지)
static void FitConnList() {
    if (!s_list || !IsWindow(s_list) || !s_panel[1]) return;
    int itemH = (int)SendMessageW(s_list, LB_GETITEMHEIGHT, 0, 0);
    int listH = itemH * std::max(2, (int)s_conns.size());
    RECT r; GetWindowRect(s_list, &r); MapWindowPoints(nullptr, s_panel[1], (POINT*)&r, 2);
    MoveWindow(s_list, r.left, r.top, r.right - r.left, listH, TRUE);
    int y = r.top + listH + S(8);
    if (s_listHint) MoveWindow(s_listHint, r.left, y, r.right - r.left, S(18), TRUE);
    s_panelH[1] = y + S(18) + S(16);
    HWND h = GetParent(s_panel[1]);
    MONITORINFO mi{sizeof mi}; GetMonitorInfoW(MonitorFromWindow(h, MONITOR_DEFAULTTONEAREST), &mi);
    int panelH = std::min(s_panelH[1], (int)(mi.rcWork.bottom - mi.rcWork.top) - s_barH - S(90));
    if (panelH <= s_panelHNow) return;
    s_panelHNow = panelH;
    RECT pc; GetClientRect(h, &pc);
    int WW = pc.right, PW = WW - s_sideW;
    for (int i = 0; i < kPages; i++) {
        MoveWindow(s_panel[i], s_sideW, 0, PW, panelH, TRUE);
        SCROLLINFO si{sizeof si, SIF_RANGE | SIF_PAGE | SIF_DISABLENOSCROLL};
        si.nMin = 0; si.nMax = s_panelH[i] - 1; si.nPage = panelH;
        SetScrollInfo(s_panel[i], SB_VERT, &si, TRUE);
        ShowScrollBar(s_panel[i], SB_VERT, s_panelH[i] > panelH);
    }
    int by = panelH + (s_barH - S(32)) / 2;
    if (HWND b = GetDlgItem(h, IDS_SAVE)) MoveWindow(b, WW - S(24) - S(204), by, S(100), S(32), TRUE);
    if (HWND b = GetDlgItem(h, IDS_CANCEL)) MoveWindow(b, WW - S(24) - S(96), by, S(96), S(32), TRUE);
    if (s_hint) MoveWindow(s_hint, s_sideW + s_cx, by + S(8), WW - s_sideW - s_cx - S(250), S(18), TRUE);
    RECT wr{0, 0, WW, panelH + s_barH};
    AdjustWindowRectExForDpi(&wr, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU, FALSE, 0, g_dpi);
    SetWindowPos(h, nullptr, 0, 0, wr.right - wr.left, wr.bottom - wr.top, SWP_NOMOVE | SWP_NOZORDER);
    InvalidateRect(h, nullptr, TRUE);
}

static LRESULT CALLBACK SettingsProc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    LRESULT dr;
    if (msg == WM_ERASEBKGND) {   // 왼쪽 메뉴 띠, 아래 막대 위 가는 선
        RECT r; GetClientRect(h, &r); HDC dc = (HDC)wp;
        FillRect(dc, &r, g_brDark);
        RECT sr{0, 0, s_sideW, r.bottom}; FillRect(dc, &sr, s_brSide);
        RECT ln{s_sideW, r.bottom - s_barH, r.right, r.bottom - s_barH + 1}; FillRect(dc, &ln, s_brLine);
        return 1;
    }
    if (msg == WM_DRAWITEM && ((DRAWITEMSTRUCT*)lp)->hwndItem == s_list && s_list) { DrawConnCard((DRAWITEMSTRUCT*)lp); return TRUE; }
    if (msg == WM_DRAWITEM && ((DRAWITEMSTRUCT*)lp)->hwndItem == s_legend && s_legend) { DrawLegend((DRAWITEMSTRUCT*)lp); return TRUE; }
    if (msg == WM_DRAWITEM && ((DRAWITEMSTRUCT*)lp)->hwndItem == s_thead && s_thead) { DrawTaskHead((DRAWITEMSTRUCT*)lp); return TRUE; }
    if (msg == WM_CTLCOLORLISTBOX && (HWND)lp == s_list && s_list) { SetBkColor((HDC)wp, kBg); return (LRESULT)g_brDark; }
    if (DarkMsg(h, msg, wp, lp, dr)) return dr;
    switch (msg) {
    case WM_CREATE: g_settings = h; DarkWindow(h); CreateSettingsControls(h); DarkenChildren(h); Settings_UpdateUi(); return 0;
    case WM_CTLCOLORSTATIC: {
        HDC dc = (HDC)wp; HWND c = (HWND)lp;
        SetBkColor(dc, kBg);
        COLORREF col = kText;
        if (std::find(s_descs.begin(), s_descs.end(), c) != s_descs.end()) col = kMuted;
        if (c == s_pickInfo) col = kSub;
        if (c == s_testRes) col = s_testOk == 0 ? RGB(0xFF, 0x6B, 0x74) : RGB(0x38, 0xD3, 0x9F);
        if (c == s_updRes) col = UpdateColor();
        if (c == s_hint || c == s_empty) col = kMuted;
        if (c == s_pairInfo) col = (s_pairUser.empty() && g_cfg.pairUser.empty()) ? kMuted : RGB(0x38, 0xD3, 0x9F);
        if (std::find(s_lines.begin(), s_lines.end(), c) != s_lines.end()) return (LRESULT)s_brLine;
        SetTextColor(dc, col);
        return (LRESULT)g_brDark;
    }
    case WM_TIMER: if (wp == 7) { KillTimer(h, 7); InvalidateRect(s_list, nullptr, TRUE); ShowConnOptions(); } return 0;   // 웹드라이브 연결 바꾼 뒤 상태 칸
    case WM_APP_TESTED: OnTested((std::pair<int, std::string>*)lp); return 0;
    case WM_NOTIFY: {
        auto* nm = (NMHDR*)lp;
        (void)nm;
        return 0;
    }
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case IDS_TEST: StartTest(h); break;
        case IDS_LIST:
            if (HIWORD(wp) == LBN_SELCHANGE && !s_filling) {
                int i = (int)SendMessageW(s_list, LB_GETCURSEL, 0, 0);
                if (i >= 0 && i < (int)s_conns.size()) { s_sel = i; ShowConnOptions(); }
            }
            if (HIWORD(wp) == LBN_DBLCLK && s_sel >= 0) OpenTask(h, s_sel);
            break;
        case IDS_SELBROWSE: BrowseFolder(h, s_selLocal); break;   // 칸이 바뀌면 아래 EN_CHANGE 로 짝에 반영
        case IDS_SELLOCAL:
            if (HIWORD(wp) == EN_CHANGE && !s_filling && s_sel >= 0 && s_sel < (int)s_conns.size()) {
                s_conns[s_sel].local = GetText(s_selLocal);
                InvalidateRect(s_list, nullptr, FALSE);
            }
            break;
        case IDS_ADDPAIR: OpenTask(h, -1); break;
        case IDS_WEBTOGGLE:
            if (s_sel >= 0 && s_sel < (int)s_conns.size() && s_conns[s_sel].web) {
                Conn& c = s_conns[s_sel];
                auto it = std::find_if(g_webdrives.begin(), g_webdrives.end(), [&](auto& w) { return w->cfg().name == c.name; });
                bool saved = false;
                for (auto& gc : g_cfg.conns) if (gc.name == c.name) saved = true;
                if (it != g_webdrives.end()) {
                    SetCursor(LoadCursorW(nullptr, IDC_WAIT));
                    (*it)->Stop();   // 고치던 파일은 올리고 떼어 냄
                    g_webdrives.erase(it);
                    c.off = true;
                } else {
                    c.off = false;
                    if (saved) StartWebDrive(c);
                    else MessageBoxW(h, L"새로 만든 작업은 [저장]을 누르면 붙습니다.", L"Mydrive", MB_ICONINFORMATION);
                }
                for (auto& gc : g_cfg.conns) if (gc.name == c.name) gc.off = c.off;   // 바로 적용: 다른 칸은 [저장] 때까지 그대로
                if (saved) SaveCfg();
                ShowConnOptions(); FillConnList();
                SetTimer(h, 7, 2500, nullptr);   // 붙는 데 1~2초 — 상태 칸 다시 그리기
            }
            break;
        case IDS_REMOVE: {
            int i = s_sel;
            if (i >= 0 && i < (int)s_conns.size()) {
                s_conns.erase(s_conns.begin() + i);
                s_sel = s_conns.empty() ? -1 : std::min(i, (int)s_conns.size() - 1);
                FillConnList(); ShowConnOptions();
            }
            break;
        }
        case IDS_PICK: case IDS_PICK2: if (s_sel >= 0) OpenTask(h, s_sel); break;
        case IDS_STARTUP: ChkSet(s_startup, !ChkGet(s_startup)); break;
        case IDS_AUTOUPD: ChkSet(s_autoUpd, !ChkGet(s_autoUpd)); break;
        case IDS_SKTMP: case IDS_SKLOCK: case IDS_SKINI: case IDS_SKTHUMB: case IDS_SKDOT: { HWND b = s_sk[LOWORD(wp) - IDS_SKTMP]; ChkSet(b, !ChkGet(b)); break; }
        case IDS_UPDCHECK: UpdateCheck(true); break;
        case IDS_UPDINST: UpdateInstallNow(); break;
        case 900: case 901: case 902: case 903: case 904: case 905: ShowPage(LOWORD(wp) - 900); if (LOWORD(wp) == 904) Settings_UpdateUi(); break;
        case IDS_PAIR: OpenPair(h); break;
        case IDS_ONDEMAND: case IDS_MODEALL:
            if (!s_filling && s_sel >= 0 && s_sel < (int)s_conns.size()) {
                s_conns[s_sel].onDemand = LOWORD(wp) == IDS_ONDEMAND;
                ShowConnOptions(); FillConnList();
            }
            break;
        case IDS_MOVEUP: case IDS_MOVEDN: {
            int j = s_sel + (LOWORD(wp) == IDS_MOVEUP ? -1 : 1);
            if (s_sel >= 0 && j >= 0 && j < (int)s_conns.size()) {
                std::swap(s_conns[s_sel], s_conns[j]); s_sel = j;
                FillConnList(); ShowConnOptions();
            }
            break;
        }
        case IDS_SAVE: case IDOK: SaveSettings(h); break;
        case IDS_CANCEL: case IDCANCEL: DestroyWindow(h); break;
        }
        return 0;
    case WM_CLOSE: DestroyWindow(h); return 0;
    case WM_DESTROY: if (p_win) DestroyWindow(p_win); if (q_win) DestroyWindow(q_win); g_settings = nullptr; s_updRes = s_updCheck = s_updInst = s_autoUpd = nullptr; return 0;
    }
    return DefWindowProcW(h, msg, wp, lp);
}

static void OpenSettings() {
    if (g_settings) { ShowWindow(g_settings, SW_SHOW); SetForegroundWindow(g_settings); return; }
    HWND h = CreateWindowExW(WS_EX_DLGMODALFRAME | WS_EX_CONTROLPARENT, L"MydriveSettings", L"Mydrive 설정", WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU,
                             CW_USEDEFAULT, CW_USEDEFAULT, S(660), S(700), nullptr, nullptr, g_inst, nullptr);
    RECT r; GetWindowRect(h, &r);
    HMONITOR mon = MonitorFromWindow(IsWindowVisible(g_main) ? g_main : h, MONITOR_DEFAULTTOPRIMARY);
    MONITORINFO mi{sizeof mi}; GetMonitorInfoW(mon, &mi);
    int w = r.right - r.left, hh = r.bottom - r.top;
    SetWindowPos(h, nullptr, (mi.rcWork.left + mi.rcWork.right - w) / 2, (mi.rcWork.top + mi.rcWork.bottom - hh) / 2, 0, 0, SWP_NOSIZE | SWP_NOZORDER);
    ShowWindow(h, SW_SHOW); SetForegroundWindow(h);
}

// ---------- 제거 ----------
static std::wstring KnownFolder(REFKNOWNFOLDERID id) {
    PWSTR p = nullptr; std::wstring s;
    if (SUCCEEDED(SHGetKnownFolderPath(id, 0, nullptr, &p))) s = p;
    CoTaskMemFree(p); return s;
}
#include "tools.inc"

// 탐색기 연동 폴더에서 아직 PC로 받지 않은(온라인 전용) 파일 수
static int CountOnlineOnly(const std::wstring& dir, int limit) {
    int n = 0; std::vector<std::wstring> st{dir};
    while (!st.empty() && n < limit) {
        std::wstring d = st.back(); st.pop_back();
        WIN32_FIND_DATAW fd; HANDLE f = FindFirstFileExW((d + L"\\*").c_str(), FindExInfoBasic, &fd, FindExSearchNameMatch, nullptr, 0);
        if (f == INVALID_HANDLE_VALUE) continue;
        do {
            if (!wcscmp(fd.cFileName, L".") || !wcscmp(fd.cFileName, L"..")) continue;
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) st.push_back(d + L"\\" + fd.cFileName);
            else if (cloud::IsDehydrated(fd.dwFileAttributes)) n++;
        } while (n < limit && FindNextFileW(f, &fd));
        FindClose(f);
    }
    return n;
}
static int Uninstall(bool quiet) {
    // 온라인 전용 파일이 남아 있으면: 제거하면 그 파일은 PC에서 열 수 없게 됨 (NAS에는 있음)
    if (g_dataDir.empty()) g_dataDir = KnownFolder(FOLDERID_LocalAppData) + L"\\Mydrive";
    LoadCfg();
    int online = 0;
    for (auto& c : g_cfg.conns) online += CountOnlineOnly(c.local, 100000);
    if (online > 0 && !quiet) {
        int r = MessageBoxW(nullptr, Fmt(L"아직 PC로 받지 않은 온라인 전용 파일이 %d개 있습니다.\n\n"
                                         L"지금 제거하면 이 파일들은 PC에서 열 수 없게 됩니다 (NAS에는 그대로 있음).\n"
                                         L"먼저 설정에서 '탐색기 연동'을 끄면 모두 받은 뒤 풀립니다.\n\n그래도 지금 제거할까요?", online).c_str(),
                            L"Mydrive 제거", MB_YESNO | MB_ICONWARNING | MB_DEFBUTTON2);
        if (r != IDYES) return 1;
    } else if (!quiet && MessageBoxW(nullptr, L"Mydrive를 이 PC에서 제거할까요?\n\n동기화하던 파일은 PC와 NAS 양쪽에 그대로 남습니다.",
                              L"Mydrive 제거", MB_YESNO | MB_ICONQUESTION) != IDYES) return 1;
    RemoveOldUserVerbs();
    {   // 탐색기 메뉴는 PC 전체에 들어 있어 빼는 데 관리자 승인이 필요
        HKEY t;
        if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, kVerbKeys[0], 0, KEY_READ, &t) == ERROR_SUCCESS) { RegCloseKey(t); RunElevated(L"--unregister-shell"); }
    }
    // 실행 중인 Mydrive 끄기
    if (HWND w = FindWindowW(L"MydriveMain", nullptr)) {
        DWORD pid = 0; GetWindowThreadProcessId(w, &pid);
        HANDLE ph = OpenProcess(SYNCHRONIZE, FALSE, pid);
        PostMessageW(w, WM_APP_QUIT, 0, 0);
        if (ph) { WaitForSingleObject(ph, 15000); CloseHandle(ph); }
    }
    HKEY k;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Run", 0, KEY_SET_VALUE, &k) == ERROR_SUCCESS) {
        RegDeleteValueW(k, L"Mydrive"); RegCloseKey(k);
    }
    RegDeleteTreeW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Mydrive");
    // 탐색기 연동 풀기: 받아 둔 파일은 보통 파일로 되돌리고 등록을 지움
    for (auto& id : cloud::RegisteredIds()) {
        for (auto& c : g_cfg.conns) if (W(c.name) == id) {
            std::vector<std::pair<std::wstring, bool>> all; std::vector<std::wstring> st{c.local};
            while (!st.empty()) {
                std::wstring d = st.back(); st.pop_back();
                WIN32_FIND_DATAW fd; HANDLE f = FindFirstFileExW((d + L"\\*").c_str(), FindExInfoBasic, &fd, FindExSearchNameMatch, nullptr, 0);
                if (f == INVALID_HANDLE_VALUE) continue;
                do {
                    if (!wcscmp(fd.cFileName, L".") || !wcscmp(fd.cFileName, L"..")) continue;
                    bool dir = (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
                    if (dir) st.push_back(d + L"\\" + fd.cFileName);
                    if (!cloud::IsDehydrated(fd.dwFileAttributes)) all.push_back({d + L"\\" + fd.cFileName, dir});
                } while (FindNextFileW(f, &fd));
                FindClose(f);
            }
            for (auto it = all.rbegin(); it != all.rend(); ++it) cloud::Revert(it->first, it->second);
        }
        cloud::Unregister(id);
    }
    cloud::NavHubRemove(g_dataDir + L"\\탐색기");
    DeleteFileW((KnownFolder(FOLDERID_Programs) + L"\\Mydrive.lnk").c_str());
    // 실행 파일이 든 폴더는 이 프로세스가 끝난 뒤 지움
    wchar_t exe[MAX_PATH]; GetModuleFileNameW(nullptr, exe, MAX_PATH);
    std::wstring dir = exe; dir.resize(dir.find_last_of(L'\\'));
    std::wstring cmd = L"cmd.exe /c ping -n 3 127.0.0.1 >nul & rmdir /s /q \"" + dir + L"\"";
    STARTUPINFOW si{sizeof si}; PROCESS_INFORMATION pi{};
    if (CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, KnownFolder(FOLDERID_LocalAppData).c_str(), &si, &pi)) {
        CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    }
    if (!quiet) MessageBoxW(nullptr, L"Mydrive를 제거했습니다.\n설정과 기록은 %LOCALAPPDATA%\\Mydrive 에 남아 있으니, 필요 없으면 지워도 됩니다.", L"Mydrive 제거", MB_ICONINFORMATION);
    return 0;
}

// ---------- 시작 ----------
int WINAPI wWinMain(HINSTANCE inst, HINSTANCE, PWSTR cmdLine, int) {
    g_inst = inst;
    std::wstring cl = cmdLine ? cmdLine : L"";
    bool tray = cl.find(L"--tray") != std::wstring::npos;
    bool quiet = cl.find(L"--quiet") != std::wstring::npos;
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (cl.find(L"--uninstall") != std::wstring::npos) return Uninstall(quiet);
    // 탐색기 오른쪽 클릭 메뉴 넣기/빼기 (설치·제거 때 관리자 승인을 받아 이것만 따로 실행됨)
    if (cl.find(L"--register-shell") != std::wstring::npos) return RegisterShellVerbs() ? 0 : 1;
    if (cl.find(L"--unregister-shell") != std::wstring::npos) return RemoveShellVerbs() ? 0 : 1;
    if (cl.find(L"--shell-current") != std::wstring::npos) return ShellVerbsCurrent() ? 0 : 1;

    // 탐색기 오른쪽 클릭 메뉴: Mydrive.exe --versions|--deleted|--copy-nas "경로" → 그 일만 하고 끝 (앱 본체와 따로)
    {
        int argc = 0; LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
        std::wstring cmd = argc >= 3 ? argv[1] : L"", path = argc >= 3 ? argv[2] : L"";
        LocalFree(argv);
        // 동기화 명령은 떠 있는 Mydrive(동기화를 하는 쪽)에 넘김. 안 떠 있으면 먼저 켬.
        if (cmd == L"--sync-now" || cmd == L"--pause30" || cmd == L"--pause60" || cmd == L"--pause180" || cmd == L"--resume" ||
            cmd == L"--toggle-skip" || cmd == L"--status" || cmd == L"--keep" || cmd == L"--free" || cmd == L"--move-to") {
            HWND w = FindWindowW(L"MydriveMain", nullptr);
            if (!w) {
                ShellExecuteW(nullptr, L"open", ExePath().c_str(), L"--tray", nullptr, SW_SHOWNORMAL);
                for (int i = 0; i < 100 && !(w = FindWindowW(L"MydriveMain", nullptr)); i++) Sleep(100);
                Sleep(1500);   // 연결을 시작할 시간
            }
            if (!w) { MessageBoxW(nullptr, L"Mydrive를 시작하지 못했습니다. 시작 메뉴에서 Mydrive를 실행한 뒤 다시 해 보세요.", L"Mydrive", MB_ICONWARNING); return 1; }
            DWORD pid = 0; GetWindowThreadProcessId(w, &pid); AllowSetForegroundWindow(pid);   // 상태 창이 앞에 뜨게
            std::wstring msg = cmd.substr(2) + L"\n" + path;
            COPYDATASTRUCT cd{0x4D44, (DWORD)(msg.size() * sizeof(wchar_t)), (PVOID)msg.c_str()};
            DWORD_PTR res = 0;
            SendMessageTimeoutW(w, WM_COPYDATA, 0, (LPARAM)&cd, SMTO_ABORTIFHUNG, 5000, &res);
            return res ? 0 : 1;
        }
        if (cmd == L"--versions" || cmd == L"--deleted" || cmd == L"--copy-nas") {
            g_dataDir = KnownFolder(FOLDERID_LocalAppData) + L"\\Mydrive";
            LoadCfg();
            if (!g_cfg.serverAlt.empty() && !Reachable(g_cfg.server) && Reachable(g_cfg.serverAlt)) g_activeServer = g_cfg.serverAlt;   // 집 밖
            if (cmd == L"--copy-nas") return CopyNasPath(path);
            INITCOMMONCONTROLSEX icc{sizeof icc, ICC_LISTVIEW_CLASSES | ICC_STANDARD_CLASSES | ICC_WIN95_CLASSES};
            InitCommonControlsEx(&icc);
            g_brWhite = CreateSolidBrush(RGB(255, 255, 255));
            g_brDark = CreateSolidBrush(kBg); g_brEdit = CreateSolidBrush(kEdit);
            g_dpi = GetDpiForSystem(); MakeFonts();
            return RunTool(cmd == L"--deleted", path);
        }
    }

    // 한 번만 실행: 이미 떠 있으면 그 창을 보여 주고 끝
    HANDLE mtx = CreateMutexW(nullptr, TRUE, L"Local\\Mydrive.App");
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        if (HWND w = FindWindowW(L"MydriveMain", nullptr)) PostMessageW(w, WM_APP_SHOW, 0, 0);
        return 0;
    }
    g_dataDir = KnownFolder(FOLDERID_LocalAppData) + L"\\Mydrive";
    CreateDirectoryW(g_dataDir.c_str(), nullptr);
    Log::I().Open(g_dataDir + L"\\mydrive.log");
    LOG("Mydrive 시작");
    DarkMenus();
    LoadCfg();
    ApplyStartup();   // 설치 위치가 바뀌었으면 자동 실행 경로도 맞춤

    INITCOMMONCONTROLSEX icc{sizeof icc, ICC_LISTVIEW_CLASSES | ICC_STANDARD_CLASSES | ICC_WIN95_CLASSES};
    InitCommonControlsEx(&icc);
    g_brWhite = CreateSolidBrush(RGB(255, 255, 255));
    g_brDark = CreateSolidBrush(kBg); g_brEdit = CreateSolidBrush(kEdit);
    MakeTrayIcons();
    g_msgTaskbarCreated = RegisterWindowMessageW(L"TaskbarCreated");

    WNDCLASSEXW wc{sizeof wc};
    wc.lpfnWndProc = MainProc; wc.hInstance = inst; wc.lpszClassName = L"MydriveMain";
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW); wc.hbrBackground = g_brWhite;
    wc.hIcon = LoadIconW(inst, MAKEINTRESOURCEW(1));
    wc.hIconSm = (HICON)LoadImageW(inst, MAKEINTRESOURCEW(1), IMAGE_ICON, GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), 0);
    RegisterClassExW(&wc);
    wc.lpfnWndProc = SettingsProc; wc.lpszClassName = L"MydriveSettings";
    RegisterClassExW(&wc);
    wc.lpfnWndProc = PickProc; wc.lpszClassName = L"MydrivePick";
    RegisterClassExW(&wc);
    wc.lpfnWndProc = PairProc; wc.lpszClassName = L"MydrivePair";
    RegisterClassExW(&wc);
    wc.lpfnWndProc = PanelProc; wc.lpszClassName = L"MydrivePanel";
    RegisterClassExW(&wc);

    HWND h = CreateWindowExW(WS_EX_TOOLWINDOW, L"MydriveMain", L"Mydrive", WS_POPUP, 0, 0, 0, 0, nullptr, nullptr, inst, nullptr);   // 보이지 않는 창 (트레이용)
    if (!h) return 1;
    g_dpi = GetDpiForSystem(); MakeFonts();
    Popup_Create();
    LoadActivity();
    LoadAlerts();
    TrayAdd();
    StartEngines();
    AddAlert(A_START, Fmt(L"Mydrive 시작 · 연결 %d개", (int)g_engines.size()));
    Tick();
    if (!tray) Popup_Show();
    if (g_cfg.conns.empty() || g_cfg.token.empty()) OpenSettings();

    MSG m;
    while (GetMessageW(&m, nullptr, 0, 0) > 0) {
        if (p_win && IsDialogMessageW(p_win, &m)) continue;
        if (q_win && IsDialogMessageW(q_win, &m)) continue;
        if (g_settings && IsDialogMessageW(g_settings, &m)) continue;
        TranslateMessage(&m); DispatchMessageW(&m);
    }
    LOG("Mydrive 끝");
    ReleaseMutex(mtx); CloseHandle(mtx);
    return 0;
}
