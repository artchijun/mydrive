// popup.inc — SynoWatch 와 같은 디자인의 트레이 창 (현황 · 요약 · 기록), 트레이 아이콘, 막힘 알림
//   app.cpp 안에 포함됨 (g_cfg, g_engines, Fmt 등을 그대로 씀)
#include "ui.h"
using ui::Rect; using ui::Color; using ui::Hex; using ui::TextOpt; using ui::Alpha;
namespace col = ui::col;

// ---------- 활동 분류 ----------
enum Cat { C_UP, C_DOWN, C_MOVE, C_DEL, C_CONFLICT, C_ERR, C_WAIT, C_RESUME, C_HELD, C_INFO, C_CLOUD };
static int Classify(const std::wstring& k) {
    if (k.find(L"실패") != std::wstring::npos) return C_ERR;
    if (k.rfind(L"☁", 0) == 0 || k.rfind(L"📌", 0) == 0) return C_CLOUD;   // 온라인 전용으로 둠 / 공간 확보 / 항상 둘 폴더
    if (k.find(L"충돌") != std::wstring::npos) return C_CONFLICT;
    if (k.find(L"이름 바꿈") != std::wstring::npos) return C_MOVE;
    if (k.rfind(L"↑", 0) == 0) return C_UP;
    if (k.rfind(L"↓", 0) == 0) return C_DOWN;
    if (k.rfind(L"✕", 0) == 0) return C_DEL;
    if (k.find(L"기다림") != std::wstring::npos || k.find(L"보류") != std::wstring::npos) return k.find(L"대량") != std::wstring::npos ? C_HELD : C_WAIT;
    if (k.find(L"이어서") != std::wstring::npos) return C_RESUME;
    return C_INFO;
}
static wchar_t CatGlyph(int c) {
    switch (c) {
    case C_UP: return 0xE898; case C_DOWN: return 0xE896; case C_MOVE: return 0xE8DE; case C_DEL: return 0xE74D;
    case C_CONFLICT: return 0xE7BA; case C_ERR: return 0xEA39; case C_WAIT: return 0xE916; case C_RESUME: return 0xE768;
    case C_HELD: return 0xE769; case C_CLOUD: return 0xE753;
    }
    return 0xE946;
}
static Color CatColor(int c) {
    switch (c) {
    case C_UP: return col::Up(); case C_DOWN: return col::Green(); case C_MOVE: return col::Sub(); case C_DEL: return Hex(0xFFE58A8F);
    case C_CONFLICT: return col::Orange(); case C_ERR: return col::Red(); case C_WAIT: return col::Purple(); case C_RESUME: return col::Green();
    case C_HELD: return col::Orange(); case C_CLOUD: return Hex(0xFF7FB2E5);
    }
    return col::Muted();
}
static const wchar_t* CatName(int c) {
    static const wchar_t* n[] = {L"올림 (PC → NAS)", L"받음 (NAS → PC)", L"이름 바꿈·옮김", L"휴지통으로", L"충돌 — 둘 다 남김", L"오류", L"다른 프로그램이 쓰는 중이라 기다림", L"이어서 진행", L"대량 삭제 보류", L"알림", L"온라인 전용 (열 때 받음)"};
    return n[c];
}
static int64_t TsOf(const std::wstring& s) {   // "YYYY-MM-DD HH:MM:SS" → 초
    SYSTEMTIME st{}; if (swscanf(s.c_str(), L"%hu-%hu-%hu %hu:%hu:%hu", &st.wYear, &st.wMonth, &st.wDay, &st.wHour, &st.wMinute, &st.wSecond) != 6) return 0;
    FILETIME ft; SystemTimeToFileTime(&st, &ft);
    return (int64_t)((((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime) / 10000000ULL);
}
static int64_t NowTs() { SYSTEMTIME st; GetLocalTime(&st); FILETIME ft; SystemTimeToFileTime(&st, &ft); return (int64_t)((((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime) / 10000000ULL); }
static std::wstring NowStamp() {
    SYSTEMTIME st; GetLocalTime(&st);
    return Fmt(L"%04d-%02d-%02d %02d:%02d:%02d", st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
}

// ---------- 활동 기록 (최근 작업) ----------
static FILE* g_actFile;
static std::wstring ActPath() { return g_dataDir + L"\\activity.log"; }
static std::wstring NiceExtra(const std::wstring& x) {
    // "209715200 B" → "200 MB"
    if (x.size() > 2 && x.compare(x.size() - 2, 2, L" B") == 0) {
        wchar_t* end = nullptr; double v = wcstod(x.c_str(), &end);
        if (end && *end == L' ') {
            const wchar_t* u[] = {L"B", L"KB", L"MB", L"GB", L"TB"}; int i = 0;
            while (v >= 1024 && i < 4) { v /= 1024; i++; }
            return i == 0 ? Fmt(L"%.0f B", v) : Fmt(v < 10 ? L"%.1f %s" : L"%.0f %s", v, u[i]);
        }
    }
    return x;
}
static std::wstring SizeText(int64_t b) {
    const wchar_t* u[] = {L"B", L"KB", L"MB", L"GB", L"TB"}; double v = (double)b; int i = 0;
    while (v >= 1024 && i < 4) { v /= 1024; i++; }
    return i == 0 ? Fmt(L"%.0f B", v) : Fmt(v < 10 ? L"%.1f %s" : L"%.0f %s", v, u[i]);
}
static std::wstring ShortTime(const std::wstring& full) {
    if (full.size() < 19) return full;
    SYSTEMTIME st; GetLocalTime(&st);
    wchar_t today[16]; swprintf(today, 16, L"%04d-%02d-%02d", st.wYear, st.wMonth, st.wDay);
    return full.compare(0, 10, today) == 0 ? full.substr(11, 8) : full.substr(5, 11);
}
static void SplitTabs(const std::wstring& w, std::vector<std::wstring>& c, size_t n) {
    size_t i = 0;
    while (c.size() + 1 < n) { size_t j = w.find(L'\t', i); if (j == std::wstring::npos) break; c.push_back(w.substr(i, j - i)); i = j + 1; }
    c.push_back(w.substr(i));
}
static std::vector<std::string> ReadLines(const std::wstring& path) {
    std::vector<std::string> lines;
    FILE* f = _wfopen(path.c_str(), L"rb");
    if (!f) return lines;
    std::string s; char b[65536]; size_t n;
    while ((n = fread(b, 1, sizeof b, f)) > 0) s.append(b, n);
    fclose(f);
    size_t i = 0;
    while (i < s.size()) { size_t j = s.find('\n', i); if (j == std::string::npos) j = s.size(); if (j > i) lines.push_back(s.substr(i, j - i)); i = j + 1; }
    return lines;
}
static void Popup_Invalidate();

static std::deque<std::pair<int64_t, int>> g_minute;   // (시각, 종류) 최근 1분
static std::wstring g_lastFile, g_lastConn;   // 마지막으로 처리한 파일
static size_t g_actLines = 0;   // activity.log 줄 수 (대략)
// 요약(최대 6시간)에 쓰도록 넉넉히 두되, 너무 커지면 최근 것만 남김 — 시작할 때와 켜져 있는 동안 모두
static void TrimActivity(std::vector<std::string>& lines) {
    if (lines.size() > 60000) {
        lines.erase(lines.begin(), lines.end() - 30000);
        FILE* w = _wfopen(ActPath().c_str(), L"wb");
        if (w) { for (auto& l : lines) { fputs(l.c_str(), w); fputc('\n', w); } fclose(w); }
    }
    g_actLines = lines.size();
}
static void LoadActivity() {
    auto lines = ReadLines(ActPath());
    TrimActivity(lines);
    size_t from = lines.size() > MAX_EVENTS ? lines.size() - MAX_EVENTS : 0;
    for (size_t k = lines.size(); k-- > from;) {
        std::vector<std::wstring> c; SplitTabs(W(lines[k]), c, 5);
        if (c.size() < 5) continue;
        EvRow r{c[0], c[1], c[2], c[3], c[4]};
        r.ts = TsOf(r.time); r.cat = Classify(r.kind);
        g_events.push_back(std::move(r));
    }
    for (auto& e : g_events) if (e.cat == C_UP || e.cat == C_DOWN || e.cat == C_MOVE) { g_lastFile = e.path; g_lastConn = e.conn; break; }
    for (auto it = g_events.rbegin(); it != g_events.rend(); ++it) if (it->ts >= NowTs() - 61) g_minute.push_back({it->ts, it->cat});
    g_actFile = _wfopen(ActPath().c_str(), L"ab");
}
static void AddEvent(EvRow* r, bool flush = true) {
    r->extra = NiceExtra(r->extra);
    r->ts = TsOf(r->time); r->cat = Classify(r->kind);
    if (g_actFile) {
        std::string line = U(r->time + L"\t" + r->conn + L"\t" + r->kind + L"\t" + r->path + L"\t" + r->extra) + "\n";
        fputs(line.c_str(), g_actFile); if (flush) fflush(g_actFile);
        g_actLines++;
    }
    if (r->cat == C_UP || r->cat == C_DOWN || r->cat == C_MOVE) { g_lastFile = r->path; g_lastConn = r->conn; }
    g_minute.push_back({r->ts, r->cat});   // 1분 수는 목록 길이 제한과 따로 셈
    while (!g_minute.empty() && g_minute.front().first < r->ts - 61) g_minute.pop_front();
    g_events.push_front(std::move(*r));
    while (g_events.size() > MAX_EVENTS) g_events.pop_back();
    if (flush) Popup_Invalidate();
}
// 엔진이 모아 둔 기록을 한 번에 처리 (한 번에 최대 5000건 — 더 있으면 다음 차례에, 그 사이 창은 계속 반응)
static void DrainEvents() {
    std::vector<EvRow> batch;
    {
        std::lock_guard<std::mutex> g(g_evMu);
        size_t n = std::min<size_t>(g_evPending.size(), 5000);
        batch.assign(std::make_move_iterator(g_evPending.begin()), std::make_move_iterator(g_evPending.begin() + n));
        g_evPending.erase(g_evPending.begin(), g_evPending.begin() + n);
        g_evPosted = !g_evPending.empty() && PostMessageW(g_main, WM_APP_EVENT, 0, 0) != FALSE;
    }
    for (auto& r : batch) AddEvent(&r, false);
    if (g_actFile) fflush(g_actFile);
    if (g_actFile && g_actLines > 60000) {   // 오래 켜 두면 끝없이 커지지 않게 (요약 계산이 파일 전체를 읽음)
        fclose(g_actFile);
        auto lines = ReadLines(ActPath());
        TrimActivity(lines);
        g_actFile = _wfopen(ActPath().c_str(), L"ab");
    }
    if (!batch.empty()) Popup_Invalidate();
}

static std::wstring LocalPathOf(const std::wstring& conn, const std::wstring& rel) {
    for (auto& c : g_cfg.conns) if (W(c.name) == conn) {
        std::wstring p = c.local;
        if (!rel.empty()) { p += L"\\"; for (wchar_t ch : rel) p += ch == L'/' ? L'\\' : ch; }
        return p;
    }
    return L"";
}
// 탐색기에서 파일을 선택해 보여 줌 (없으면 있는 가장 가까운 상위 폴더를 엶)
static void RevealInExplorer(std::wstring p) {
    while (!p.empty() && GetFileAttributesW(p.c_str()) == INVALID_FILE_ATTRIBUTES) {
        size_t s = p.find_last_of(L'\\'); if (s == std::wstring::npos) return; p.resize(s);
    }
    if (p.empty()) return;
    PIDLIST_ABSOLUTE pidl = nullptr;
    if (SUCCEEDED(SHParseDisplayName(p.c_str(), nullptr, &pidl, 0, nullptr))) {
        SHOpenFolderAndSelectItems(pidl, 0, nullptr, 0);
        CoTaskMemFree(pidl);
    }
}
static void CopyText(const std::wstring& s) {
    if (!OpenClipboard(g_main)) return;
    EmptyClipboard();
    HGLOBAL h = GlobalAlloc(GMEM_MOVEABLE, (s.size() + 1) * 2);
    if (h) { memcpy(GlobalLock(h), s.c_str(), (s.size() + 1) * 2); GlobalUnlock(h); SetClipboardData(CF_UNICODETEXT, h); }
    CloseClipboard();
}

// ---------- 알림 기록 (기록 탭): 막힘·풀림·보류·멈춤·연결·설정 등 ----------
enum AlertKind { A_STUCK, A_RESOLVED, A_HELD, A_CONFIRM, A_PAUSE, A_RESUME, A_CONFLICT, A_ERROR, A_NET, A_NETOK, A_START, A_SETTINGS, A_KILL };
struct Alert { std::wstring time; int kind; std::wstring msg, path; };
static std::deque<Alert> g_alerts;   // 최근 것이 앞
static std::wstring AlertPath() { return g_dataDir + L"\\alerts.log"; }
static int AlertGroup(int k) {   // 거르기: 1 막힘·오류 / 2 삭제·충돌 / 3 그 밖
    if (k == A_STUCK || k == A_RESOLVED || k == A_ERROR || k == A_NET || k == A_NETOK || k == A_KILL) return 1;
    if (k == A_HELD || k == A_CONFIRM || k == A_CONFLICT) return 2;
    return 3;
}
static wchar_t AlertGlyph(int k) {
    switch (k) {
    case A_STUCK: return 0xE7BA; case A_RESOLVED: return 0xE73E; case A_HELD: return 0xE769; case A_CONFIRM: return 0xE74D;
    case A_PAUSE: return 0xE769; case A_RESUME: return 0xE768; case A_CONFLICT: return 0xE8C8; case A_ERROR: return 0xEA39;
    case A_NET: return 0xE871; case A_NETOK: return 0xE839; case A_START: return 0xE7E8; case A_SETTINGS: return 0xE713; case A_KILL: return 0xE711;
    }
    return 0xE946;
}
static Color AlertColor(int k) {
    switch (k) {
    case A_STUCK: case A_ERROR: case A_KILL: return col::Red();
    case A_RESOLVED: case A_RESUME: case A_NETOK: return col::Green();
    case A_HELD: case A_NET: case A_CONFLICT: return col::Orange();
    case A_PAUSE: return col::Hold();
    }
    return col::Muted();
}
static void AddAlert(int kind, const std::wstring& msg, const std::wstring& path = L"") {
    Alert a{NowStamp(), kind, msg, path};
    FILE* f = _wfopen(AlertPath().c_str(), L"ab");
    if (f) { std::string l = U(a.time + L"\t" + std::to_wstring(kind) + L"\t" + msg + L"\t" + path) + "\n"; fputs(l.c_str(), f); fclose(f); }
    g_alerts.push_front(a);
    while (g_alerts.size() > 500) g_alerts.pop_back();
    Popup_Invalidate();
}
static void LoadAlerts() {
    auto lines = ReadLines(AlertPath());
    if (lines.size() > 3000) {
        lines.erase(lines.begin(), lines.end() - 1000);
        FILE* w = _wfopen(AlertPath().c_str(), L"wb");
        if (w) { for (auto& l : lines) { fputs(l.c_str(), w); fputc('\n', w); } fclose(w); }
    }
    for (size_t k = lines.size(); k-- > 0 && g_alerts.size() < 500;) {
        std::vector<std::wstring> c; SplitTabs(W(lines[k]), c, 4);
        if (c.size() < 4) continue;
        g_alerts.push_back({c[0], _wtoi(c[1].c_str()), c[2], c[3]});
    }
}

// ---------- 잠시 멈춤 ----------
static bool AnyPaused() { for (auto& e : g_engines) if (e->Paused()) return true; return false; }
static bool AllPaused() { if (g_engines.empty()) return false; for (auto& e : g_engines) if (!e->Paused()) return false; return true; }
// 작업 하나만 멈춤/다시 시작 ('다시 시작할 때까지'는 설정에 남겨 앱을 다시 켜도 유지)
// 설정에 남김: minutes > 0 이면 끝나는 시각, < 0 이면 '다시 시작할 때까지', 0 이면 지움
static void SetConnPause(Conn& c, int minutes) {
    c.paused = minutes < 0;
    c.pauseUntil = minutes > 0 ? NowEpochMs() + (int64_t)minutes * 60000 : 0;
}
static std::wstring MinText(int m);
static std::wstring ClockAfter(uint64_t ms);
static void PauseConn(int i, int minutes) {
    if (i < 0 || i >= (int)g_engines.size()) return;
    Engine& e = *g_engines[i];
    std::wstring nm = W(e.cfg().name);
    bool was = e.Paused();
    e.Pause(minutes);
    for (auto& c : g_cfg.conns) if (c.name == e.cfg().name) SetConnPause(c, minutes);
    SaveCfg();
    if (minutes > 0) AddAlert(A_PAUSE, L"'" + nm + L"' 작업만 " + MinText(minutes) + L" 동안 멈춤 (" + ClockAfter((uint64_t)minutes * 60000) + L"까지)", e.cfg().localRoot);
    else if (minutes < 0) AddAlert(A_PAUSE, L"'" + nm + L"' 작업만 다시 시작할 때까지 멈춤", e.cfg().localRoot);
    else if (was) AddAlert(A_RESUME, L"'" + nm + L"' 작업을 다시 시작함", e.cfg().localRoot);
}
static uint64_t PausedLeft() { uint64_t m = 0; for (auto& e : g_engines) m = std::max(m, e->PausedLeftMs()); return m; }
static bool AnyPausedForever() { for (auto& e : g_engines) if (e->PausedForever()) return true; return false; }
static std::wstring ClockAfter(uint64_t ms) {
    SYSTEMTIME st; GetLocalTime(&st); FILETIME ft; SystemTimeToFileTime(&st, &ft);
    uint64_t v = (((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime) + ms * 10000ULL;
    ft.dwLowDateTime = (DWORD)v; ft.dwHighDateTime = (DWORD)(v >> 32); FileTimeToSystemTime(&ft, &st);
    return Fmt(L"%02d:%02d", st.wHour, st.wMinute);
}
static std::wstring MinText(int m) { return m % 60 == 0 ? Fmt(L"%d시간", m / 60) : Fmt(L"%d분", m); }
static void PauseAll(int minutes) {
    bool was = AnyPaused();
    for (auto& e : g_engines) e->Pause(minutes);
    for (auto& c : g_cfg.conns) if (!c.web) SetConnPause(c, minutes);   // 전체 멈춤·다시 시작이 작업별 멈춤보다 우선, 앱을 다시 켜도 유지
    SaveCfg();
    if (minutes > 0) AddAlert(A_PAUSE, MinText(minutes) + L" 동안 동기화를 멈춤 (" + ClockAfter((uint64_t)minutes * 60000) + L"까지)");
    else if (was) AddAlert(A_RESUME, L"동기화를 다시 시작함");
    Popup_Invalidate();
}
static void SyncNow() {
    if (AnyPaused()) PauseAll(0);
    for (auto& e : g_engines) { e->ClearCanceled(); e->RunNow(); }   // 'N시간마다'·'원할 때만' 작업도 지금
}
// 왼쪽 위 Mydrive 아이콘: NAS 다시 확인
//   NAS 서버는 바뀐 것을 실시간(inotify)으로 알지만, 폴더가 아주 많으면 일부 폴더는 감시하지 못해 15분마다 비교로만 알게 됨.
//   File Station·SMB 로 그런 폴더를 바꿨을 때 누르면 서버가 지금 전체를 비교하고, PC 앱은 곧바로 맞춤.
static HWND g_pop = nullptr;   // 이 창 (아래 그리기·누르기에서 씀)
static std::atomic<bool> g_rescanning{false};
static void RescanNow() {
    if (g_rescanning.exchange(true)) return;
    std::vector<std::string> roots;
    for (auto& e : g_engines) roots.push_back(e->cfg().name);
    for (auto& w : g_webdrives) roots.push_back(w->cfg().name);
    std::wstring server = ActiveServer(); std::string token = g_cfg.token;
    InvalidateRect(g_pop, nullptr, FALSE);
    std::thread([roots, server, token] {
        Http http(server, token);
        int changed = 0, fail = 0;
        for (auto& r : roots) {
            Resp res = http.Req(L"POST", "/v1/rescan", {{"root", r}}, nullptr, 300);
            json j = res.ok() ? json::parse(res.body, nullptr, false) : json();
            if (j.is_object()) changed += j.value("changed", 0); else fail++;
        }
        for (auto& e : g_engines) e->ResyncRemote();   // 서버 쪽을 다시 훑은 뒤, PC 가 들고 있는 NAS 목록도 통째로 새로 받음
        SYSTEMTIME st; GetLocalTime(&st);
        wchar_t t[32]; swprintf(t, 32, L"%04d-%02d-%02d %02d:%02d:%02d", st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
        LOG("NAS 다시 확인 (아이콘): %d개 연결, %d개 바뀜, %d개 실패", (int)roots.size(), changed, fail);
        std::wstring extra = fail ? Fmt(L"%d개 연결은 확인 못 함 (서버가 0.6.4 보다 예전이거나 연결 안 됨)", fail) : changed ? Fmt(L"%d개 바뀐 것을 찾아 맞춤", changed) : L"바뀐 것 없음";
        {
            std::lock_guard<std::mutex> g(g_evMu);
            g_evPending.push_back(EvRow{t, L"NAS", L"🔄 NAS 다시 확인", L"", extra});
            if (!g_evPosted) g_evPosted = PostMessageW(g_main, WM_APP_EVENT, 0, 0) != FALSE;
        }
        g_rescanning = false;
        if (g_pop) InvalidateRect(g_pop, nullptr, FALSE);
    }).detach();
}

// ---------- 전체 상태 (트레이 색 · 상태 알약) ----------
enum TrayState { T_IDLE, T_SYNC, T_STUCK, T_NET, T_HOLD };
struct Overall {
    int state = T_IDLE; std::wstring text; Color color;
    int64_t pend = 0, held = 0, wait = 0, xfers = 0;
    std::vector<std::pair<int, StuckInfo>> stuck;   // (연결 번호, 막힌 파일)
    std::vector<std::pair<int, Transfer>> xf;
    std::vector<std::pair<int, Engine::HeldDir>> heldDirs;   // (연결 번호, 폴더) 많은 순 상위 3곳
    int heldGroups = 0;
    int heldMoved = 0; std::wstring heldMovedTo;   // 보류 중 다른 동기화 작업으로 옮겨진 것으로 보이는 파일 수와 그 작업
    bool offline = false;
};
static uint64_t g_offlineSince = 0;
static Overall GetOverall() {
    Overall o;
    bool anyOffline = false, busy = false;
    for (int i = 0; i < (int)g_engines.size(); i++) {
        Engine& e = *g_engines[i];
        o.pend += e.stats.pending; o.held += e.stats.heldDeletes; o.wait += e.stats.waiting;
        if (!e.stats.online && !e.Paused()) anyOffline = true;
        if (e.busy || e.stats.pending > 0) busy = true;
        for (auto& s : e.StuckList()) o.stuck.push_back({i, s});
        for (auto& t : e.Transfers()) o.xf.push_back({i, t});
        if (e.stats.heldDeletes > 0) {
            int g = 0; for (auto& d : e.HeldDirs(&g)) o.heldDirs.push_back({i, d}); o.heldGroups += g;
            std::string to; int mv = e.HeldMoved(&to);
            if (mv > 0) { o.heldMoved += mv; if (o.heldMovedTo.empty()) o.heldMovedTo = W(to); }
        }
    }
    std::sort(o.heldDirs.begin(), o.heldDirs.end(), [](const auto& a, const auto& b) { return a.second.n > b.second.n; });
    if (o.heldDirs.size() > 3) o.heldDirs.resize(3);
    o.xfers = (int64_t)o.xf.size();
    uint64_t now = GetTickCount64();
    if (anyOffline) { if (!g_offlineSince) g_offlineSince = now; } else g_offlineSince = 0;
    o.offline = g_offlineSince && now - g_offlineSince > 20000;   // 잠깐 끊긴 것은 넘김
    if (g_engines.empty()) { o.state = T_NET; o.text = L"연결 없음"; o.color = col::Orange(); }
    else if (!o.stuck.empty() || o.held > 0) { o.state = T_STUCK; o.text = o.held > 0 && o.stuck.empty() ? L"확인 필요" : L"막힘"; o.color = col::Red(); }
    else if (AllPaused()) { o.state = T_HOLD; o.text = L"동기화 멈춤"; o.color = col::Hold(); }
    else if (o.offline) { o.state = T_NET; o.text = L"연결 안 됨"; o.color = col::Orange(); }
    else if (busy || o.xfers) { o.state = T_SYNC; o.text = L"동기화 중"; o.color = col::Blue(); }
    else { o.state = T_IDLE; o.text = L"대기 중"; o.color = col::Green(); }
    return o;
}

// ---------- 트레이 아이콘 ----------
static HICON g_ico[5];
static int g_icoState = -1;
static NOTIFYICONDATAW g_nid{};
static void MakeTrayIcons() {
    int px = GetSystemMetricsForDpi(SM_CXSMICON, GetDpiForSystem());
    const uint32_t c[5] = {0xFF10B981, 0xFF3B82F6, 0xFFEF4444, 0xFFF59E0B, 0xFF8B5CF6};
    const wchar_t g[5] = {0xE73E, 0xE895, 0xE7BA, 0xE871, 0xE769};
    for (int i = 0; i < 5; i++) { if (g_ico[i]) DestroyIcon(g_ico[i]); g_ico[i] = ui::MakeStatusIcon(c[i], g[i], px); }
}
static void TrayAdd() {
    g_nid = {sizeof g_nid};
    g_nid.hWnd = g_main; g_nid.uID = 1;
    g_nid.uFlags = NIF_ICON | NIF_TIP | NIF_MESSAGE | NIF_SHOWTIP;
    g_nid.uCallbackMessage = WM_APP_TRAY;
    g_nid.hIcon = g_ico[g_icoState < 0 ? 0 : g_icoState];
    wcscpy_s(g_nid.szTip, L"Mydrive");
    Shell_NotifyIconW(NIM_ADD, &g_nid);
    g_nid.uVersion = NOTIFYICON_VERSION_4;
    Shell_NotifyIconW(NIM_SETVERSION, &g_nid);
}
static void TraySet(int state, std::wstring tip) {
    if (tip.size() > 63) tip = tip.substr(0, 62) + L"…";
    if (state == g_icoState && tip == g_nid.szTip) return;
    g_nid.uFlags = NIF_ICON | NIF_TIP | NIF_SHOWTIP;
    g_nid.hIcon = g_ico[state];
    wcsncpy_s(g_nid.szTip, tip.c_str(), _TRUNCATE);
    Shell_NotifyIconW(NIM_MODIFY, &g_nid);
    g_icoState = state;
}
static std::wstring g_balloonReveal;   // 알림을 누르면 탐색기에서 보여 줄 파일 (비면 창을 엶)
static void TrayBalloon(const std::wstring& title, const std::wstring& text, DWORD icon, const std::wstring& reveal = L"") {
    g_balloonReveal = reveal;
    NOTIFYICONDATAW n = g_nid;
    n.uFlags = NIF_INFO;
    wcsncpy_s(n.szInfoTitle, title.c_str(), _TRUNCATE);
    wcsncpy_s(n.szInfo, text.c_str(), _TRUNCATE);
    n.dwInfoFlags = icon | NIIF_RESPECT_QUIET_TIME;
    Shell_NotifyIconW(NIM_MODIFY, &n);
}
static std::wstring Leaf(const std::wstring& p) { size_t s = p.find_last_of(L"/\\"); return s == std::wstring::npos ? p : p.substr(s + 1); }
static std::wstring ParentOf(const std::wstring& p) { size_t s = p.find_last_of(L"/\\"); return s == std::wstring::npos ? L"" : p.substr(0, s); }
static std::wstring ConnName(int i) { return i >= 0 && i < (int)g_engines.size() ? W(g_engines[i]->cfg().name) : L""; }

// ---------- 막힘 알림: 처음 막힐 때, 풀리지 않으면 10분마다, 풀리면 한 번 ----------
struct StuckMark { uint64_t alertedAt = 0; int n = 0; bool seen = true; std::wstring why; };
static std::map<std::wstring, StuckMark> g_stuckMarks;
static int64_t g_prevHeld = 0;
static bool g_prevOffline = false;
static void WatchAlerts(const Overall& o) {
    uint64_t now = GetTickCount64();
    for (auto& [k, m] : g_stuckMarks) m.seen = false;
    for (auto& [i, s] : o.stuck) {
        std::wstring key = ConnName(i) + L"|" + W(s.p);
        auto& m = g_stuckMarks[key];
        m.seen = true; m.why = W(s.why);
        if (!m.alertedAt || now - m.alertedAt >= 10 * 60000) {
            m.n++; m.alertedAt = now;
            uint64_t mins = (now - s.since) / 60000;
            std::wstring t = m.n == 1 ? L"동기화가 막혔습니다" : Fmt(L"아직 막혀 있습니다 (%d번째 알림)", m.n);
            std::wstring body = Leaf(W(s.p)) + L" — " + W(s.why) + Fmt(L" (%llu분째)", mins);
            TrayBalloon(L"Mydrive · " + t, body, NIIF_ERROR);
            AddAlert(A_STUCK, t + L": " + W(s.why), LocalPathOf(ConnName(i), W(s.p)));
        }
    }
    for (auto it = g_stuckMarks.begin(); it != g_stuckMarks.end();) {
        if (!it->second.seen) {
            std::wstring path = it->first.substr(it->first.find(L'|') + 1);
            TrayBalloon(L"Mydrive · 막힘이 풀렸습니다", Leaf(path) + L" — 이어서 맞췄습니다", NIIF_INFO);
            AddAlert(A_RESOLVED, L"막힘이 풀림", LocalPathOf(it->first.substr(0, it->first.find(L'|')), path));
            it = g_stuckMarks.erase(it);
        } else ++it;
    }
    if (o.held > 0 && g_prevHeld == 0) {
        TrayBalloon(L"Mydrive · 확인 필요", Fmt(L"한 번에 %lld개가 지워져서 삭제를 멈췄습니다. 창을 열어 확인하세요.", o.held), NIIF_WARNING);
        AddAlert(A_HELD, Fmt(L"한 번에 %lld개가 지워져서 삭제를 멈춤 — 확인을 기다림", o.held));
    }
    g_prevHeld = o.held;
    // 충돌 사본: 기록에 하나씩 남기고, 알림은 1분에 한 번 (그사이 생긴 것은 모아서)
    static int s_cfPend = 0; static std::wstring s_cfFirst; static uint64_t s_cfAt = 0;
    for (size_t i = 0; i < g_engines.size(); i++) {
        for (auto& rel : g_engines[i]->TakeConflicts()) {
            std::wstring abs = LocalPathOf(ConnName((int)i), W(rel));
            AddAlert(A_CONFLICT, L"충돌 사본 — 두 곳에서 바뀌어 둘 다 남김: " + Leaf(W(rel)), abs);
            if (!s_cfPend++) s_cfFirst = abs;
        }
    }
    if (s_cfPend && (!s_cfAt || now - s_cfAt >= 60000)) {
        std::wstring body = s_cfPend == 1 ? Leaf(s_cfFirst) + L"\n원본과 사본을 둘 다 남겼습니다. 눌러서 위치 보기"
                                          : Fmt(L"%d개 — ", s_cfPend) + Leaf(s_cfFirst) + L" 등\n원본과 사본을 둘 다 남겼습니다. 눌러서 첫 파일 위치 보기 (전체는 기록 탭)";
        TrayBalloon(L"Mydrive · 충돌 사본이 생겼습니다", body, NIIF_WARNING, s_cfFirst);
        s_cfPend = 0; s_cfFirst.clear(); s_cfAt = now;
    }
    if (o.offline != g_prevOffline) {
        if (o.offline) { AddAlert(A_NET, L"NAS에 연결할 수 없음 — 다시 시도하는 중 (" + ServerHost() + L")"); }
        else AddAlert(A_NETOK, L"NAS에 다시 연결됨");
        g_prevOffline = o.offline;
    }
}

// ---------- 트레이 창 ----------
enum Hit {
    H_NONE = 0, H_SET = 1, H_HELP, H_PIN, H_CLOSE, H_TAB0, H_TAB1, H_TAB2, H_SYNCNOW, H_P30, H_P60, H_P180, H_RESUME,
    H_NOWOPEN, H_NOWCOPY, H_CONFIRMDEL, H_SUMREF, H_LOGOPEN, H_LEGEND, H_RESTOREDEL, H_RESCAN, H_HELDLIST,
    H_HELDDIR = 90,        // + 0..2 (대량 삭제가 일어난 폴더)
    H_CONNPAUSE = 7800,    // + 연결 번호 (이 작업만 멈춤/다시 시작)
    H_HELDMOVED = 7900,    // [옮긴 것으로 처리]
    H_CONNSYNC = 8000,     // + 연결 번호 (이 작업만 지금 동기화)
    H_SUMCHIP = 100,       // + 0..3
    H_LOGF = 120,          // + 0..3
    H_CONN = 200,          // + 연결 번호
    H_STUCKCOPY = 300, H_STUCKOPEN = 400, H_STUCKFIND = 500,   // + 막힌 항목 번호
    H_KILL = 600,          // + 막힌 항목*10 + 앱 번호
    H_RECENT = 1000,       // + 줄 번호 (최근 작업)
    H_RECENTCOPY = 3000,
    H_SUMROW = 5000,       // + 줄 번호 (요약 폴더)
    H_LOGROW = 6000,       // + 줄 번호 (기록)
    H_XFER = 7000,         // + 줄 번호 (지금 처리 중)
    H_XCANCEL = 7500,      // + 줄 번호 (지금 처리 중인 파일 취소)
};
static HWND g_popTip;
static ui::Canvas g_cv;
static int g_tab = 0, g_sumMins = 60, g_logFilter = 0;
static bool g_pinned = false, g_legend = false;
static uint64_t g_hiddenAt = 0;
static float g_scrollRecent = 0, g_scrollLog = 0;
static Rect g_rcRecent, g_rcLog, g_rcHeader;
static std::wstring g_hoverTip;
// 막힌 항목의 잡고 있는 앱 (UI 스레드에서 몇 초마다 다시 찾음)
struct StuckView { int conn; StuckInfo s; std::wstring abs; std::vector<Holder> holders; };
static std::vector<StuckView> g_stuckView;
static uint64_t g_stuckViewAt = 0;
// 줄을 누르면 쓸 경로
static std::vector<std::wstring> g_recentPaths, g_sumPaths, g_logPaths, g_xferPaths;
static std::vector<std::pair<int, std::string>> g_xferRefs;   // 처리 중인 줄 → (연결 번호, NAS 경로)

static void Popup_Invalidate() { if (g_pop && IsWindowVisible(g_pop)) InvalidateRect(g_pop, nullptr, FALSE); }

// 버튼 그리기 (style: 0 보통 / 1 테두리 없음 / 2 고른 칩 / 3 빨강)
static void Btn(int id, Rect r, const std::wstring& text, wchar_t glyph = 0, const std::wstring& tip = L"", int style = 0, float fs = 12) {
    bool hot = g_cv.hover == id, down = g_cv.pressed == id && hot;
    Color bg = style == 1 ? col::White(0) : style == 2 ? Hex(0x2E5B9BFF) : style == 3 ? Hex(0x66E5484D) : col::White(0.086f);
    if (hot) bg = style == 3 ? Hex(0x99E5484D) : col::White(down ? 0.27f : 0.17f);
    g_cv.Fill(r, bg, 7);
    float tw = text.empty() ? 0 : g_cv.TextWidth(text, {fs});
    float gw = glyph ? fs + (text.empty() ? 0 : 6) : 0;
    float x = r.l + (r.w() - tw - gw) / 2;
    Color fg = style == 2 ? col::Text() : col::Text();
    if (glyph) g_cv.Icon(glyph, {x, r.t, x + fs + 1, r.b}, fg, fs - 0.5f);
    if (!text.empty()) g_cv.Text(text, {x + gw, r.t, r.r, r.b}, fg, {fs});
    g_cv.AddHit(r, id, tip);
}
static float BtnW(const std::wstring& text, wchar_t glyph, float fs = 12, float pad = 11) {
    return (text.empty() ? 0 : g_cv.TextWidth(text, {fs})) + (glyph ? fs + (text.empty() ? 0 : 6) : 0) + pad * 2;
}
static void Card(Rect r, Color bg = col::White(0.07f), float radius = 9) { g_cv.Fill(r, bg, radius); }
static Rect Inset(Rect r, float a) { return r.inset(a, a); }

// 지금 하는 일 (동시에 처리 중인 파일마다 한 줄)
static const int kNowRows = 5;
static const float kNowRowH = 46;
static void DrawNow(Rect r, const Overall& o) {
    g_cv.Fill(r, col::Card(), 12);
    g_cv.Stroke(r, col::White(0.08f), 12);
    Rect in = r.inset(14, 12);
    Color c = o.color; Color tile = Alpha(c, 0.14f);
    wchar_t g = 0xE895; std::wstring label, where, extra;
    g_xferPaths.clear(); g_xferRefs.clear();
    std::vector<std::pair<int, Transfer>> xs = o.xf;   // 시작한 순서로 (줄이 뛰지 않게)
    std::sort(xs.begin(), xs.end(), [](auto& a, auto& b) { return a.second.startTick < b.second.startTick; });
    int ups = 0, downs = 0;
    for (auto& [i, t] : xs) (t.up ? ups : downs)++;
    if (o.state == T_HOLD) { g = 0xE769; label = L"동기화를 멈췄습니다"; where = AnyPausedForever() ? std::wstring(L"다시 시작할 때까지 멈춤 — [지금 동기화]로 다시 시작") : ClockAfter(PausedLeft()) + L"에 저절로 다시 시작"; }
    else if (o.offline) { g = 0xE871; label = L"NAS에 연결할 수 없습니다"; where = ServerHost() + L" — 다시 시도하는 중"; }
    else if (!xs.empty()) {
        g = (ups && downs) ? 0xE895 : ups ? 0xE898 : 0xE896; c = (ups && !downs) ? col::Up() : col::Green(); tile = Alpha(c, 0.14f);
        label = (ups && downs) ? Fmt(L"올리고 받는 중 · %d개 동시에", ups + downs) : ups ? Fmt(L"NAS로 올리는 중 · %d개 동시에", ups) : Fmt(L"NAS에서 받는 중 · %d개 동시에", downs);
        where = o.pend > 0 ? Fmt(L"남은 일 %lld개 · 작은 파일 3개, 큰 파일 2개씩 따로", o.pend) : L"작은 파일 3개, 큰 파일(8 MB 이상) 2개씩 따로 처리";
    } else if (o.pend > 0) { label = Fmt(L"맞추는 중 · %lld개 남음", o.pend); }
    else if (o.wait > 0) { g = 0xE916; c = col::Purple(); tile = Alpha(c, 0.14f); label = Fmt(L"다른 프로그램이 쓰는 파일 %lld개를 기다리는 중", o.wait); where = L"그 프로그램이 파일을 닫으면 바로 이어서 맞춥니다"; }
    else {
        g = 0xE73E; label = L"모두 맞춰져 있습니다";
        int sched = 0; for (auto& e : g_engines) if (e->cfg().everyHours != 0 || e->cfg().dailyAt >= 0) sched++;
        where = sched == 0 ? Fmt(L"연결 %d개 · 바뀌면 1~2초 안에 맞춥니다", (int)g_engines.size())
              : sched == (int)g_engines.size() ? Fmt(L"연결 %d개 · 정한 때에 맞춥니다 (지금 맞추려면 [지금 동기화])", (int)g_engines.size())
              : Fmt(L"연결 %d개 · 실시간 %d개는 바뀌면 바로, 나머지는 정한 때에", (int)g_engines.size(), (int)g_engines.size() - sched);
    }
    Rect ic{in.l, in.t, in.l + 42, in.t + 42};
    g_cv.Fill(ic, tile, 11); g_cv.Icon(g, ic, c, 18);
    float x = in.l + 55, y = in.t;
    g_cv.Text(label, {x, y, in.r, y + 18}, c, {13, DWRITE_FONT_WEIGHT_SEMI_BOLD}); y += 20;
    if (!where.empty()) { g_cv.Text(where, {x, y, in.r, y + 16}, col::Sub(), {11.5f}); y += 18; }
    y = std::max(y, in.t + 46) + 4;
    if (xs.empty()) return;
    // 지금 처리 중인 파일마다 한 줄 (막대 하나씩)
    g_cv.Line(in.l, y, in.r, y, col::White(0.09f)); y += 8;
    int shown = std::min<int>((int)xs.size(), kNowRows);
    uint64_t now = GetTickCount64();
    for (int k = 0; k < shown; k++) {
        auto& [ci, t] = xs[k];
        Color tc = t.up ? col::Up() : col::Green();
        std::wstring rel = W(t.p);
        size_t sl = rel.find_last_of(L'/');
        std::wstring leaf = sl == std::wstring::npos ? rel : rel.substr(sl + 1);
        std::wstring dir = ParentOf(LocalPathOf(ConnName(ci), rel));   // 실제 폴더 경로 (C:\...)
        float f = t.total > 0 ? std::min(1.f, (float)t.done / (float)t.total) : 0.f;
        double secs = (now - t.startTick) / 1000.0;
        std::wstring right = Fmt(L"%d%%", (int)(f * 100));
        if (secs > 0.5 && t.done > 0) right = SizeText((int64_t)(t.done / secs)) + L"/s · " + right;
        float rw = g_cv.TextWidth(right, {11});
        const float cb = 26;   // [취소] 단추 자리
        g_cv.Icon(t.up ? 0xE898 : 0xE896, {in.l, y, in.l + 16, y + 18}, tc, 12);
        g_cv.Text(leaf, {in.l + 22, y, in.r - cb - rw - 14, y + 18}, col::Text(), {12.5f});
        g_cv.Text(right, {in.r - cb - 6 - rw, y + 1, in.r - cb - 6, y + 18}, col::Sub(), {11});
        Rect bar{in.l + 22, y + 22, in.r - cb - 6, y + 25};
        g_cv.Fill(bar, col::White(0.1f), 1.5f);
        if (f > 0) g_cv.Fill({bar.l, bar.t, bar.l + bar.w() * f, bar.b}, tc, 1.5f);
        std::wstring sz = SizeText(t.done) + L" / " + SizeText(t.total);
        float sw = g_cv.TextWidth(sz, {10.5f});
        g_cv.Text(dir, {in.l + 22, y + 27, in.r - sw - 10, y + 41}, col::Sub(), {10.5f});
        g_cv.Text(sz, {in.r - cb - 6 - sw, y + 27, in.r - cb - 6, y + 41}, col::Muted(), {10.5f});
        g_cv.AddHit({in.l, y, in.r - cb - 4, y + 42}, H_XFER + k, dir + L"\\" + leaf + L"\n두 번 눌러 열기 · 오른쪽 클릭 메뉴");
        Btn(H_XCANCEL + k, {in.r - cb, y + 6, in.r, y + 32}, L"", 0xE711,
            t.up ? L"이 파일 올리기 취소\n지금 옮기던 것을 멈추고, 이 파일은 고치거나 [지금 동기화]를 누를 때까지 다시 올리지 않습니다.\nPC 파일은 그대로 남습니다."
                 : L"이 파일 받기 취소\n지금 받던 것을 멈추고, NAS 파일이 바뀌거나 [지금 동기화]를 누를 때까지 다시 받지 않습니다.\nNAS 파일은 그대로 남습니다.", 1);
        g_xferPaths.push_back(dir + L"\\" + leaf);
        g_xferRefs.push_back({ci, t.p});
        y += kNowRowH;
    }
    if ((int)xs.size() > shown)
        g_cv.Text(Fmt(L"외 %d개 더 처리 중", (int)xs.size() - shown), {in.l + 22, y, in.r, y + 16}, col::Muted(), {11});
}
static float NowHeight(const Overall& o) {
    float h = 12 + std::max(20.f + 18, 46.f) + 4 + 12;
    if (!o.xf.empty()) {
        int n = (int)o.xf.size();
        h += 8 + std::min(n, kNowRows) * kNowRowH + (n > kNowRows ? 18 : 0);
    }
    return h;
}

// 막힌 항목의 잡고 있는 앱을 다시 찾음 (3초마다)
static void RefreshStuckView(const Overall& o, bool force) {
    if (!force && GetTickCount64() - g_stuckViewAt < 3000 && g_stuckView.size() == o.stuck.size()) return;
    g_stuckViewAt = GetTickCount64();
    g_stuckView.clear();
    for (auto& [i, s] : o.stuck) {
        StuckView v{i, s, LocalPathOf(ConnName(i), W(s.p)), {}};
        if (s.locked) {
            DWORD me = GetCurrentProcessId();
            for (auto& h : FileHolders(v.abs)) if (h.pid != me) v.holders.push_back(h);
        }
        g_stuckView.push_back(std::move(v));
    }
}
static bool Killable(const std::wstring& name, DWORD pid) {
    if (pid <= 4) return false;
    std::wstring n = name; CharLowerBuffW(n.data(), (DWORD)n.size());
    for (const wchar_t* s : {L"explorer", L"windows 탐색기", L"시스템", L"system", L"defender", L"antimalware", L"synology"}) if (n.find(s) != std::wstring::npos) return false;
    return true;
}
static float StuckHeight() {
    float h = 12 + 22 + 6;
    for (auto& v : g_stuckView) h += 9 + 18 + 16 + 18 + (v.holders.empty() ? 0 : 6 + (float)v.holders.size() * 40) + 9 + 6;
    return h + 6;
}
static void DrawStuck(Rect r) {
    g_cv.Fill(r, Hex(0x22FF5A64), 12); g_cv.Stroke(r, Hex(0x70FF5A64), 12);
    Rect in = r.inset(14, 12);
    g_cv.Icon(0xE7BA, {in.l, in.t, in.l + 16, in.t + 20}, col::Red(), 14);
    g_cv.Text(L"동기화가 막혔습니다", {in.l + 24, in.t, in.r, in.t + 20}, Hex(0xFFFF9AA1), {13, DWRITE_FONT_WEIGHT_SEMI_BOLD});
    float y = in.t + 28;
    for (int k = 0; k < (int)g_stuckView.size(); k++) {
        auto& v = g_stuckView[k];
        float ih = 9 + 18 + 16 + 18 + (v.holders.empty() ? 0 : 6 + (float)v.holders.size() * 40) + 9;
        Rect it{in.l, y, in.r, y + ih};
        g_cv.Fill(it, Hex(0x30000000), 9);
        Rect ii = it.inset(11, 9);
        float bx = ii.r - 30;
        Btn(H_STUCKOPEN + k, {bx, ii.t, bx + 30, ii.t + 26}, L"", 0xE838, L"폴더 열기");
        float cw = BtnW(L"경로 복사", 0xE8C8, 12, 9); bx -= cw + 5;
        Btn(H_STUCKCOPY + k, {bx, ii.t, bx + cw, ii.t + 26}, L"경로 복사", 0xE8C8, L"막힌 경로를 클립보드에 복사");
        bx -= 35;
        Btn(H_STUCKFIND + k, {bx, ii.t, bx + 30, ii.t + 26}, L"", 0xE721, L"잡고 있는 앱 다시 찾기");
        float tx = ii.l, tr = bx - 8;
        uint64_t mins = (GetTickCount64() - v.s.since) / 60000;
        g_cv.Text(Leaf(W(v.s.p)), {tx, ii.t, tr, ii.t + 18}, col::Text(), {12.5f, DWRITE_FONT_WEIGHT_SEMI_BOLD});
        g_cv.Text(v.abs, {tx, ii.t + 18, tr, ii.t + 34}, col::Sub(), {11});
        g_cv.Text(W(v.s.why) + (v.s.fails ? Fmt(L" · %d번 실패", v.s.fails) : L"") + Fmt(L" · %llu분째", mins), {tx, ii.t + 36, ii.r, ii.t + 52}, Hex(0xFFFF9AA1), {11});
        float hy = ii.t + 58;
        for (int j = 0; j < (int)v.holders.size() && j < 10; j++) {
            auto& hd = v.holders[j];
            Rect hr{ii.l, hy, ii.r, hy + 34};
            g_cv.Fill(hr, col::White(0.12f), 7);
            g_cv.Icon(0xE7EF, {hr.l + 6, hr.t, hr.l + 26, hr.b}, col::Orange(), 13);
            g_cv.Text(hd.name, {hr.l + 32, hr.t + 2, hr.r - 100, hr.t + 18}, col::Text(), {12, DWRITE_FONT_WEIGHT_SEMI_BOLD});
            g_cv.Text(Fmt(L"이 파일을 열어 둠 · 프로세스 %u", hd.pid), {hr.l + 32, hr.t + 17, hr.r - 100, hr.b - 2}, Hex(0xFF9AA1AE), {10.5f});
            if (Killable(hd.name, hd.pid)) {
                float kw = BtnW(L"강제 종료", 0xE711, 11.5f, 10);
                Btn(H_KILL + k * 10 + j, {hr.r - kw - 6, hr.t + 4, hr.r - 6, hr.b - 4}, L"강제 종료", 0xE711,
                    L"이 프로그램을 강제로 끕니다. 저장하지 않은 내용은 사라집니다 (확인 창을 거침).", 3, 11.5f);
            }
            hy += 40;
        }
        y += ih + 6;
    }
}
static std::vector<std::wstring> g_heldDirAbs;   // [H_HELDDIR + k] 누르면 탐색기에서 보여 줄 폴더
static float HeldHeight(const Overall& o) { return 110.f + 22.f * (float)o.heldDirs.size() + (o.heldMoved > 0 ? 26.f : 0.f); }
static void DrawHeld(Rect r, const Overall& o) {
    int64_t held = o.held;
    g_cv.Fill(r, Hex(0x22F5B544), 12); g_cv.Stroke(r, Hex(0x70F5B544), 12);
    Rect in = r.inset(14, 12);
    // 사라진 폴더 (많은 순 상위 3곳): 누르면 탐색기에서 그 자리를 보여 줌
    g_heldDirAbs.clear();
    float ry = in.t + 56;
    for (size_t k = 0; k < o.heldDirs.size(); k++) {
        auto& [ci, d] = o.heldDirs[k];
        std::wstring abs = LocalPathOf(ConnName(ci), W(d.rel));
        g_heldDirAbs.push_back(abs);
        std::wstring right = Fmt(L"%d개 · ", d.n) + (d.lostOnPC ? L"PC에서 사라짐" : L"NAS에서 사라짐");
        Rect row{in.l + 20, ry, in.r, ry + 22};
        if (g_cv.hover == H_HELDDIR + (int)k) g_cv.Fill(row, Hex(0x18FFFFFF), 6);
        g_cv.Icon(0xE8B7, {row.l + 4, row.t + 2, row.l + 20, row.b - 2}, col::Orange(), 12);
        float rw = 170;
        // 길면 앞쪽을 줄여 끝 폴더 이름이 보이게: C:\…\4.WORKING\.20250524
        std::wstring shown = abs; float maxw = row.w() - rw - 32;
        for (size_t cut = 3; g_cv.TextWidth(shown, {11.5f}) > maxw;) {
            size_t q = abs.find(L'\\', cut);
            if (q == std::wstring::npos) break;
            shown = abs.substr(0, 3) + L"…" + abs.substr(q); cut = q + 1;
        }
        g_cv.Text(shown, {row.l + 24, row.t, row.r - rw - 8, row.b}, col::Text(), {11.5f});
        g_cv.Text(right, {row.r - rw, row.t, row.r - 6, row.b}, col::Sub(), {11, DWRITE_FONT_WEIGHT_NORMAL, ui::Right});
        g_cv.AddHit(row, H_HELDDIR + (int)k, abs + L"\n누르면 탐색기에서 이 자리를 보여 줍니다", false);
        ry += 22;
    }
    float off = 22.f * (float)o.heldDirs.size();
    if (o.heldMoved > 0) {   // DSM 에서 다른 공유 폴더로 옮긴 것으로 보임
        Rect mr{in.l + 20, in.t + 56 + off, in.r, in.t + 78 + off};
        g_cv.Icon(0xE8DE, {mr.l + 4, mr.t + 2, mr.l + 20, mr.b - 2}, col::Green(), 12);
        g_cv.Text(Fmt(L"그중 %d개는 '", o.heldMoved) + o.heldMovedTo + L"' 작업으로 옮겨진 것으로 보입니다",
                  {mr.l + 24, mr.t, mr.r, mr.b}, Hex(0xFF9FE3B0), {11.5f});
        g_cv.AddHit(mr, 0, L"DSM 등에서 다른 공유 폴더로 옮긴 경우입니다. 사라진 파일과 이름·크기·수정 시각이 같은 파일이 '" + o.heldMovedTo + L"' 작업의 NAS 쪽에 있습니다.\n[옮긴 것으로 처리]를 누르면 이 파일들만 옛 자리에서 지웁니다 (휴지통으로).", false);
        off += 26;
    }
    g_cv.Icon(0xE769, {in.l, in.t, in.l + 16, in.t + 20}, col::Orange(), 14);
    g_cv.Text(Fmt(L"한 번에 %lld개가 지워져서 삭제를 멈췄습니다", held), {in.l + 24, in.t, in.r, in.t + 20}, Hex(0xFFFFD18A), {13, DWRITE_FONT_WEIGHT_SEMI_BOLD});
    g_cv.Text(L"직접 지운 적이 없으면 [되살리기], 일부러 지웠으면 [지우기]. 다른 동기화는 계속합니다.",
              {in.l + 24, in.t + 22, in.r, in.t + 54}, col::Sub(), {11, DWRITE_FONT_WEIGHT_NORMAL, ui::Left, true, true});
    float w1 = BtnW(L"되살리기", 0xE777), w2 = BtnW(L"지우기", 0xE74D), w3 = BtnW(L"목록 보기", 0xE8FD);
    float x0 = in.l + 24;
    if (o.heldMoved > 0) {
        float w0 = BtnW(L"옮긴 것으로 처리", 0xE8DE);
        Btn(H_HELDMOVED, {x0, in.t + 58 + off, x0 + w0, in.t + 86 + off}, L"옮긴 것으로 처리", 0xE8DE,
            L"다른 동기화 작업에서 같은 파일이 확인된 것만 이 PC의 옛 자리에서 지웁니다 (휴지통으로).\n새 자리에는 이미 들어와 있습니다. 짝을 찾지 못한 파일은 계속 보류합니다.", 2);
        x0 += w0 + 8; in.l = x0 - 24;
    }
    Btn(H_HELDLIST, {in.l + 40 + w1 + w2, in.t + 58 + off, in.l + 40 + w1 + w2 + w3, in.t + 86 + off}, L"목록 보기", 0xE8FD,
        L"지워지려는 파일·폴더 목록을 메모장으로 엽니다. 어느 쪽에서 사라졌는지도 적혀 있습니다.");
    Btn(H_RESTOREDEL, {in.l + 24, in.t + 58 + off, in.l + 24 + w1, in.t + 86 + off}, L"되살리기", 0xE777,
        L"아무것도 지우지 않고, 사라진 쪽에 다시 만듭니다.\nPC에서 사라졌으면 NAS 기준으로 PC에 다시 만들고(필요할 때 받기면 온라인 전용으로), NAS에서 사라졌으면 PC 파일을 다시 올립니다.");
    Btn(H_CONFIRMDEL, {in.l + 32 + w1, in.t + 58 + off, in.l + 32 + w1 + w2, in.t + 86 + off}, L"지우기", 0xE74D,
        L"반대쪽에서도 지웁니다. PC 에서는 휴지통, NAS 에서는 #recycle 로 가므로 나중에 되살릴 수 있습니다.");
}

// 최근 작업 목록 (오른쪽에 시각, 마우스를 올리면 복사 버튼)
static void DrawRecent(Rect r) {
    g_rcRecent = r;
    g_cv.PushClip(r);
    g_recentPaths.clear();
    const float rh = 44;
    std::vector<const EvRow*> rows;
    for (auto& e : g_events) if (e.cat != C_INFO) rows.push_back(&e);
    float maxScroll = std::max(0.f, rows.size() * rh - r.h());
    g_scrollRecent = std::clamp(g_scrollRecent, 0.f, maxScroll);
    int first = (int)(g_scrollRecent / rh);
    for (int i = first; i < (int)rows.size(); i++) {
        float y = r.t + i * rh - g_scrollRecent;
        if (y > r.b) break;
        const EvRow& e = *rows[i];
        int idx = (int)g_recentPaths.size();
        g_recentPaths.push_back(LocalPathOf(e.conn, e.path));
        Rect row{r.l, y, r.r, y + rh - 2};
        bool hot = g_cv.hover == H_RECENT + idx || g_cv.hover == H_RECENTCOPY + idx;
        if (hot) g_cv.Fill(row, col::White(0.07f), 8);
        g_cv.AddHit(row, H_RECENT + idx, CatName(e.cat) + std::wstring(L"\n") + g_recentPaths.back() + (e.extra.empty() ? L"" : L"\n" + e.extra), false);
        g_cv.Icon(CatGlyph(e.cat), {row.l + 6, row.t, row.l + 30, row.b}, CatColor(e.cat), 14);
        std::wstring folder = ParentOf(g_recentPaths.back());   // 실제 폴더 경로 (C:\...)
        if (e.cat == C_MOVE || e.cat == C_ERR || e.cat == C_WAIT || e.cat == C_CONFLICT || e.cat == C_HELD) folder = e.extra.empty() ? folder : e.extra;
        float tr = row.r - 78;
        g_cv.Text(Leaf(e.path), {row.l + 36, row.t + 5, tr - (hot ? 30 : 0), row.t + 22}, col::Text(), {12.5f});
        g_cv.Text(folder, {row.l + 36, row.t + 22, tr - (hot ? 30 : 0), row.t + 38}, col::Muted(), {11});
        if (hot) Btn(H_RECENTCOPY + idx, {tr - 28, row.t + 9, tr, row.t + 33}, L"", 0xE8C8, L"경로 복사", 1);
        g_cv.Text(ShortTime(e.time), {tr, row.t + 5, row.r - 6, row.t + 22}, col::Muted(), {11, DWRITE_FONT_WEIGHT_NORMAL, ui::Right});
        std::wstring ex = (e.cat == C_UP || e.cat == C_DOWN || e.cat == C_CLOUD) ? e.extra : L"";
        if (!ex.empty()) g_cv.Text(ex, {tr, row.t + 22, row.r - 6, row.t + 38}, Hex(0xFF6E7585), {10.5f, DWRITE_FONT_WEIGHT_NORMAL, ui::Right});
    }
    if (rows.empty()) g_cv.Text(L"아직 기록이 없습니다", r, col::Muted(), {12, DWRITE_FONT_WEIGHT_NORMAL, ui::Center});
    g_cv.PopClip();
}

// 1분 동안의 수 (현황 카드)
static void CountLastMinute(int64_t& down, int64_t& up, int64_t& retry, int64_t& err) {
    down = up = retry = err = 0;
    int64_t from = NowTs() - 60;
    for (auto& [ts, cat] : g_minute) {
        if (ts < from) continue;
        if (cat == C_DOWN) down++; else if (cat == C_UP) up++;
        else if (cat == C_WAIT || cat == C_CONFLICT) retry++; else if (cat == C_ERR) err++;
    }
}

// 동기화 줄: 상태 · [지금 동기화] · 잠시 멈춤 칩 · 연결 목록
static float BarHeight() { return 11 + 34 + 8 + (float)g_engines.size() * 24 + 9; }
static void DrawBar(Rect r, const Overall& o) {
    Card(r);
    Rect in{r.l + 11, r.t + 9, r.r - 11, r.b - 9};
    bool paused = AllPaused();   // 몇 작업만 멈췄으면 그 줄에만 표시
    Color ic = paused ? col::Hold() : o.offline ? col::Orange() : col::Muted();
    g_cv.Icon(paused ? 0xE769 : 0xE895, {in.l, in.t, in.l + 18, in.t + 34}, ic, 15);
    std::wstring title = paused ? L"동기화 멈춤" : L"동기화";
    std::wstring text;
    if (paused) text = (AnyPausedForever() ? std::wstring(L"다시 시작할 때까지") : ClockAfter(PausedLeft()) + L"까지") + L" 멈춤 · 그동안 바뀐 것은 다시 시작할 때 한꺼번에 맞춤";
    else if (o.offline) text = L"NAS에 연결할 수 없음 — 15초마다 다시 시도";
    else if (o.pend > 0) text = Fmt(L"맞출 것 %lld개 남음", o.pend);
    else {
        int sched = 0; for (auto& e : g_engines) if (e->cfg().everyHours != 0 || e->cfg().dailyAt >= 0) sched++;
        text = sched == 0 ? L"바뀌면 바로 맞춤" : sched == (int)g_engines.size() ? L"정한 때에 맞춤" : L"몇 작업은 정한 때에";
    }
    // 오른쪽: 평소에는 '⏸ 30분 1시간 3시간'(잠시 멈춤), 멈춰 있을 때만 [지금 동기화] (멈춤을 풀고 바로 맞춤)
    float right = in.r;
    if (paused) {
        float bw = BtnW(L"지금 동기화", 0xE768, 12, 9);
        Btn(H_RESUME, {in.r - bw, in.t + 4, in.r, in.t + 30}, L"지금 동기화", 0xE768, L"멈춤을 풀고 지금 바로 맞춥니다 (모든 연결)");
        right = in.r - bw - 8;
    } else {
        struct { int id, m; } chips[] = {{H_P180, 180}, {H_P60, 60}, {H_P30, 30}};   // 오른쪽부터
        float x = in.r;
        {   // [지금 동기화] 늘 보이게 — '지정 시간마다'·'매일'·'원할 때만' 작업도 지금 바로
            float bw = BtnW(L"지금 동기화", 0xE895, 11.5f, 9);
            Btn(H_SYNCNOW, {x - bw, in.t + 5, x, in.t + 29}, L"지금 동기화", 0xE895, L"모든 작업을 지금 바로 맞춥니다 ('지정 시간마다'·'매일'·'원할 때만' 작업도, PC·드라이브 유휴 조건과 상관없이)", 0, 11.5f);
            x -= bw + 10;
        }
        for (auto& c : chips) {
            std::wstring ct = MinText(c.m); float w = BtnW(ct, 0, 11.5f, 9);
            Btn(c.id, {x - w, in.t + 5, x, in.t + 29}, ct, 0, MinText(c.m) + L" 동안 모든 연결의 동기화를 잠시 멈춤 — 시간이 되면 저절로 다시 맞춤", 0, 11.5f);
            x -= w + 5;
        }
        g_cv.Icon(0xE769, {x - 20, in.t + 5, x, in.t + 29}, col::Muted(), 12);   // ⏸ 잠시 멈춤
        right = x - 26;
    }
    g_cv.Text(title, {in.l + 26, in.t, right, in.t + 17}, col::Text(), {12, DWRITE_FONT_WEIGHT_SEMI_BOLD});
    g_cv.Text(text, {in.l + 26, in.t + 17, right, in.t + 34}, col::Muted(), {10.5f});
    float y = in.t + 42;
    for (int i = 0; i < (int)g_engines.size(); i++) {
        Engine& e = *g_engines[i];
        int st = EngineState(e);
        Color dc = st == 0 ? col::Green() : st == 1 ? col::Blue() : st == 2 ? col::Hold() : st == 3 ? col::Red() : col::Orange();
        Rect row{in.l, y, in.r, y + 22};
        if (g_cv.hover == H_CONN + i) g_cv.Fill(row, col::White(0.06f), 6);
        g_cv.Dot(in.l + 9, y + 11, 3.5f, dc);
        std::wstring nm = W(e.cfg().name);
        float nw = g_cv.TextWidth(nm, {12, DWRITE_FONT_WEIGHT_SEMI_BOLD});
        g_cv.Text(nm, {in.l + 26, y, in.l + 26 + nw + 2, y + 22}, col::Text(), {12, DWRITE_FONT_WEIGHT_SEMI_BOLD});
        std::wstring s = StateText(st);
        if (st == 2) s = e.PausedForever() ? L"멈춤 · 다시 시작할 때까지" : L"멈춤 · " + ClockAfter(e.PausedLeftMs()) + L"까지";
        if (e.stats.pending > 0 && st == 1) s += Fmt(L" · %d개", (int)e.stats.pending);
        if (e.stats.waiting > 0) s += Fmt(L" · 기다림 %d", (int)e.stats.waiting);
        if (st == 0) {   // 맞춰진 채 기다리는 중: 다음에 맞출 때 / 조건 기다림
            if (int w = e.WaitingFor()) s = w == 1 ? L"PC 사용 중 · 기다림" : L"드라이브 바쁨 · 기다림";
            else if (e.cfg().everyHours < 0 && e.cfg().dailyAt < 0) s = L"맞춰짐 · 누를 때";
            else if (int64_t nx = e.NextPassMs()) {
                SYSTEMTIME u, lt, nowL; FILETIME ft; uint64_t v = ((uint64_t)(nx + 11644473600000LL)) * 10000ULL; ft.dwLowDateTime = (DWORD)v; ft.dwHighDateTime = (DWORD)(v >> 32);
                FileTimeToSystemTime(&ft, &u); SystemTimeToTzSpecificLocalTime(nullptr, &u, &lt); GetLocalTime(&nowL);
                s = lt.wDay == nowL.wDay ? Fmt(L"맞춰짐 · 다음 %02d:%02d", lt.wHour, lt.wMinute) : Fmt(L"맞춰짐 · 다음 %d/%d %02d:%02d", lt.wMonth, lt.wDay, lt.wHour, lt.wMinute);
            }
        }
        float sw = g_cv.TextWidth(s, {11}) + 4;
        // 동기화 방식: 모두 받기 / 요청 시 받기 (+ 항상 둘 폴더 수)
        std::wstring mode = !e.cfg().onDemand ? L"모두 받아 두기" : e.cfg().keepDirs.empty() ? L"필요할 때 받기" : Fmt(L"필요할 때 · 항상 %d", (int)e.cfg().keepDirs.size());
        float mw = 0;
        if (!mode.empty()) {
            mw = g_cv.TextWidth(mode, {10.5f}) + 14;
            Rect chip{in.l + 32 + nw, y + 3, in.l + 32 + nw + mw, y + 19};
            g_cv.Fill(chip, Hex(0x337FB2E5), 8);
            g_cv.Text(mode, chip, Hex(0xFF9CC7F0), {10.5f, DWRITE_FONT_WEIGHT_NORMAL, ui::Center});
            mw += 6;
        }
        float pbw = 48;   // 오른쪽 끝: 이 작업만 지금 동기화 [⟳] · 멈춤 [⏸] / 다시 시작 [▶]
        g_cv.Text(L"↔  " + e.cfg().localRoot, {in.l + 32 + nw + mw, y, in.r - pbw - sw - 6, y + 22}, col::Muted(), {11});
        g_cv.Text(s, {in.r - pbw - sw, y, in.r - pbw - 2, y + 22}, dc, {11, DWRITE_FONT_WEIGHT_NORMAL, ui::Right});
        std::wstring tip = L"NAS '" + nm + L"' ↔ " + e.cfg().localRoot;
        if (e.cfg().onDemand) {
            tip += L"\n필요할 때 받기: 목록만 두고 열 때 받음";
            if (!e.cfg().keepDirs.empty()) { tip += L"\n항상 이 PC에 둘 폴더:"; for (auto& k : e.cfg().keepDirs) tip += L"\n  · " + W(k); }
            if (e.cfg().autoFreeDays > 0) tip += Fmt(L"\n%d일 동안 안 연 파일은 자동으로 온라인 전용", e.cfg().autoFreeDays);
        } else tip += L"\n모두 받아 두기: 모든 파일을 이 PC에 둠";
        g_cv.AddHit(row, H_CONN + i, tip + L"\n누르면 PC 폴더를 엽니다 · 오른쪽 클릭: 이 작업만 멈춤");
        {
            Rect sb{in.r - pbw + 2, y + 1, in.r - 24, y + 21};
            bool shot = g_cv.hover == H_CONNSYNC + i;
            if (shot) g_cv.Fill(sb, col::White(0.17f), 6);
            g_cv.Icon(0xE895, sb, shot ? col::Text() : col::Muted(), 11);
            g_cv.AddHit(sb, H_CONNSYNC + i, L"이 작업만 지금 동기화 (정한 때·유휴 조건과 상관없이 바로)");
        }
        {
            Rect pb{in.r - 22, y + 1, in.r, y + 21};
            bool hot = g_cv.hover == H_CONNPAUSE + i;
            if (hot) g_cv.Fill(pb, col::White(0.17f), 6);
            g_cv.Icon(st == 2 ? 0xE768 : 0xE769, pb, st == 2 ? col::Hold() : (hot ? col::Text() : col::Muted()), 11);
            g_cv.AddHit(pb, H_CONNPAUSE + i, st == 2 ? L"이 작업을 다시 시작합니다 (그동안 바뀐 것을 맞춤)" : L"이 작업만 잠시 멈춥니다 (30분·1시간·3시간·다시 시작할 때까지). 다른 작업은 계속 맞춤");
        }
        y += 24;
    }
}

static void DrawStats(Rect r) {
    int64_t d, u, rt, er; CountLastMinute(d, u, rt, er);
    struct { int64_t v; const wchar_t* l; Color c; const wchar_t* tip; } s[] = {
        {d, L"↓ 받기 / 분", col::Green(), L"최근 1분 동안 NAS 에서 이 PC 로 받은 파일·폴더"},
        {u, L"↑ 올리기 / 분", col::Up(), L"최근 1분 동안 이 PC 에서 NAS 로 올린 파일·폴더"},
        {rt, L"기다림 / 분", col::Purple(), L"최근 1분 동안 다른 프로그램이 쓰는 중이거나 그사이 NAS 에서도 바뀌어서 잠시 미룬 것"},
        {er, L"오류 / 분", col::Red(), L"최근 1분 동안 난 오류 (30초 뒤 저절로 다시 시도)"}};
    float w = (r.w() - 18) / 4;
    for (int i = 0; i < 4; i++) {
        Rect c{r.l + i * (w + 6), r.t, r.l + i * (w + 6) + w, r.b};
        Card(c);
        g_cv.Text(Fmt(L"%lld", s[i].v), {c.l + 11, c.t + 7, c.r - 6, c.t + 31}, s[i].c, {18, DWRITE_FONT_WEIGHT_SEMI_BOLD});
        g_cv.Text(s[i].l, {c.l + 11, c.t + 31, c.r - 4, c.b - 6}, col::Muted(), {10.5f});
        g_cv.AddHit(c, 0, s[i].tip, false);
    }
}

static void DrawNowTab(Rect r, const Overall& o) {
    float y = r.t;
    DrawStats({r.l, y, r.r, y + 56}); y += 64;
    float bh = BarHeight();
    DrawBar({r.l, y, r.r, y + bh}, o); y += bh + 10;
    float nh = NowHeight(o);
    DrawNow({r.l, y, r.r, y + nh}, o); y += nh + 10;
    if (o.held > 0) { float hh = HeldHeight(o); DrawHeld({r.l, y, r.r, y + hh}, o); y += hh + 10; }
    RefreshStuckView(o, false);
    if (!g_stuckView.empty()) { float sh = StuckHeight(); DrawStuck({r.l, y, r.r, y + sh}); y += sh + 10; }
    g_cv.Text(L"최근 작업", {r.l + 2, y, r.r, y + 20}, col::Text(), {12.5f, DWRITE_FONT_WEIGHT_SEMI_BOLD});
    g_cv.Text(L"두 번 눌러 열기 · 오른쪽 클릭 메뉴", {r.l, y, r.r, y + 20}, col::Muted(), {10.5f, DWRITE_FONT_WEIGHT_NORMAL, ui::Right});
    y += 24;
    DrawRecent({r.l - 6, y, r.r + 6, r.b});
}

// ---------- 요약 탭 ----------
struct SumData {
    int mins = 0; int64_t since = 0, at = 0; size_t lines = 0;
    int64_t n[10] = {};
    int64_t bytesUp = 0, bytesDown = 0;
    std::vector<std::pair<int64_t, int64_t>> buckets;   // (올림, 받음)
    struct Row { std::wstring name, path, note; int64_t count; Color c; };
    std::vector<Row> folders, errors, conflicts;
};
static SumData g_sum;
static int64_t ParseBytes(const std::wstring& x) {   // "200 MB" → 바이트
    wchar_t* e = nullptr; double v = wcstod(x.c_str(), &e);
    if (!e || e == x.c_str()) return 0;
    std::wstring u = e; while (!u.empty() && u[0] == L' ') u.erase(0, 1);
    double m = u == L"KB" ? 1024 : u == L"MB" ? 1048576 : u == L"GB" ? 1073741824.0 : u == L"TB" ? 1099511627776.0 : 1;
    return (int64_t)(v * m);
}
static std::wstring TopFolder(const std::wstring& p, int n) {
    std::wstring o; int k = 0;
    for (size_t i = 0; i < p.size() && k < n; ) {
        size_t j = p.find(L'/', i);
        if (j == std::wstring::npos) break;   // 파일 이름 앞까지만
        if (!o.empty()) o += L"/";
        o += p.substr(i, j - i); k++; i = j + 1;
    }
    return o;
}
static void ComputeSummary(int mins) {
    SumData s; s.mins = mins; s.at = NowTs(); s.since = s.at - mins * 60;
    const int NB = 12; s.buckets.assign(NB, {0, 0});
    std::map<std::wstring, std::pair<int64_t, std::wstring>> folders, errs;
    std::map<std::wstring, std::pair<int64_t, std::wstring>> confl;
    auto lines = ReadLines(ActPath());
    for (size_t k = lines.size(); k-- > 0;) {
        std::vector<std::wstring> c; SplitTabs(W(lines[k]), c, 5);
        if (c.size() < 5) continue;
        int64_t ts = TsOf(c[0]);
        if (ts < s.since) break;
        s.lines++;
        int cat = Classify(c[2]);
        s.n[cat]++;
        if (cat == C_UP || cat == C_DOWN) {
            int b = (int)std::clamp<int64_t>((ts - s.since) * NB / std::max(1, mins * 60), 0, NB - 1);
            (cat == C_UP ? s.buckets[b].first : s.buckets[b].second)++;
            (cat == C_UP ? s.bytesUp : s.bytesDown) += ParseBytes(c[4]);
            std::wstring f = c[1] + L" › " + (TopFolder(c[3], 2).empty() ? L"(맨 위)" : TopFolder(c[3], 2));
            auto& v = folders[f]; v.first++; v.second = LocalPathOf(c[1], TopFolder(c[3], 2));
        }
        if (cat == C_ERR) { auto& v = errs[c[1] + L"|" + c[3]]; v.first++; v.second = c[2] + (c[4].empty() ? L"" : L" — " + c[4]); }
        if (cat == C_CONFLICT) { auto& v = confl[c[1] + L"|" + c[3]]; v.first++; v.second = c[4]; }
    }
    auto top = [](auto& m, size_t n) {
        std::vector<std::pair<std::wstring, std::pair<int64_t, std::wstring>>> v(m.begin(), m.end());
        std::sort(v.begin(), v.end(), [](auto& a, auto& b) { return a.second.first > b.second.first; });
        if (v.size() > n) v.resize(n);
        return v;
    };
    for (auto& [k, v] : top(folders, 6)) s.folders.push_back({k, v.second, L"", v.first, col::Up()});
    for (auto& [k, v] : top(errs, 5)) { auto bar = k.find(L'|'); s.errors.push_back({Leaf(k.substr(bar + 1)), LocalPathOf(k.substr(0, bar), k.substr(bar + 1)), v.second, v.first, col::Red()}); }
    for (auto& [k, v] : top(confl, 4)) { auto bar = k.find(L'|'); s.conflicts.push_back({Leaf(k.substr(bar + 1)), LocalPathOf(k.substr(0, bar), k.substr(bar + 1)), v.second, v.first, col::Orange()}); }
    g_sum = std::move(s);
}
static void DrawSumList(float& y, Rect r, const wchar_t* title, const std::vector<SumData::Row>& rows, int64_t maxv, bool bars) {
    if (rows.empty()) return;
    g_cv.Text(title, {r.l + 2, y, r.r, y + 20}, col::Text(), {12.5f, DWRITE_FONT_WEIGHT_SEMI_BOLD}); y += 24;
    for (auto& row : rows) {
        int idx = (int)g_sumPaths.size(); g_sumPaths.push_back(row.path);
        Rect rr{r.l - 6, y, r.r + 6, y + (row.note.empty() ? 30.f : 40.f)};
        if (g_cv.hover == H_SUMROW + idx) g_cv.Fill(rr, col::White(0.07f), 8);
        g_cv.AddHit(rr, H_SUMROW + idx, row.path + L"\n두 번 누르면 열기 · 오른쪽 클릭 메뉴", false);
        std::wstring cnt = Fmt(L"%lld", row.count);
        float cw = g_cv.TextWidth(cnt, {12, DWRITE_FONT_WEIGHT_SEMI_BOLD}) + 4;
        if (bars && maxv > 0) {
            float f = (float)row.count / maxv;
            g_cv.Fill({r.l, y + 22, r.l + (r.w() - cw - 10) * f, y + 25}, Alpha(row.c, 0.6f), 1.5f);
        }
        g_cv.Text(row.name, {r.l, y + 2, r.r - cw - 10, y + 20}, col::Text(), {12});
        g_cv.Text(cnt, {r.r - cw, y + 2, r.r, y + 20}, row.c, {12, DWRITE_FONT_WEIGHT_SEMI_BOLD, ui::Right});
        if (!row.note.empty()) g_cv.Text(row.note, {r.l, y + 20, r.r, y + 36}, col::Muted(), {10.5f});
        y += rr.h() + 2;
    }
    y += 8;
}
static void DrawSumTab(Rect r) {
    g_sumPaths.clear();
    float y = r.t, x = r.l;
    int ms[] = {10, 30, 60, 360};
    g_cv.Text(L"최근", {x, y, x + 34, y + 26}, col::Muted(), {11.5f}); x += 34;
    for (int i = 0; i < 4; i++) {
        std::wstring t = MinText(ms[i]); float w = BtnW(t, 0, 11.5f, 11);
        Btn(H_SUMCHIP + i, {x, y, x + w, y + 26}, t, 0, L"최근 " + t + L" 동안의 활동을 모아 봅니다", g_sumMins == ms[i] ? 2 : 0, 11.5f);
        x += w + 6;
    }
    Btn(H_SUMREF, {r.r - 30, y, r.r, y + 26}, L"", 0xE72C, L"다시 계산", 1);
    y += 34;
    if (g_sum.mins != g_sumMins || NowTs() - g_sum.at > 30) ComputeSummary(g_sumMins);
    SYSTEMTIME st; FILETIME ft; uint64_t v = (uint64_t)g_sum.since * 10000000ULL;
    ft.dwLowDateTime = (DWORD)v; ft.dwHighDateTime = (DWORD)(v >> 32); FileTimeToSystemTime(&ft, &st);
    g_cv.Text(Fmt(L"최근 %s (%02d:%02d 이후) · 활동 %zu건 · 올림 %s · 받음 %s", MinText(g_sumMins).c_str(), st.wHour, st.wMinute, g_sum.lines,
                  SizeText(g_sum.bytesUp).c_str(), SizeText(g_sum.bytesDown).c_str()), {r.l, y, r.r, y + 16}, col::Muted(), {10.5f});
    y += 24;
    struct { int c; const wchar_t* l; } cards[] = {{C_DOWN, L"↓ 받음"}, {C_UP, L"↑ 올림"}, {C_ERR, L"오류"}, {C_CONFLICT, L"충돌 사본"}, {C_DEL, L"휴지통으로"}, {C_MOVE, L"이름 바꿈·옮김"}};
    float w = (r.w() - 12) / 3;
    for (int i = 0; i < 6; i++) {
        Rect c{r.l + (i % 3) * (w + 6), y + (i / 3) * 62, r.l + (i % 3) * (w + 6) + w, y + (i / 3) * 62 + 56};
        Card(c);
        g_cv.Text(Fmt(L"%lld", g_sum.n[cards[i].c]), {c.l + 11, c.t + 7, c.r - 6, c.t + 31}, CatColor(cards[i].c), {18, DWRITE_FONT_WEIGHT_SEMI_BOLD});
        g_cv.Text(cards[i].l, {c.l + 11, c.t + 31, c.r - 4, c.b - 6}, col::Muted(), {10.5f});
    }
    y += 130;
    // 시간대별 막대 (올림 파랑 · 받음 초록)
    Rect ch{r.l, y, r.r, y + 64};
    Card(ch);
    int64_t mx = 1; for (auto& b : g_sum.buckets) mx = std::max(mx, b.first + b.second);
    float bw = (ch.w() - 24) / g_sum.buckets.size();
    for (size_t i = 0; i < g_sum.buckets.size(); i++) {
        float bx = ch.l + 12 + i * bw, base = ch.b - 18, hmax = ch.h() - 30;
        float hu = hmax * g_sum.buckets[i].first / mx, hd = hmax * g_sum.buckets[i].second / mx;
        if (hd > 0) g_cv.Fill({bx + 2, base - hd, bx + bw - 2, base}, Alpha(col::Green(), 0.8f), 2);
        if (hu > 0) g_cv.Fill({bx + 2, base - hd - hu, bx + bw - 2, base - hd}, Alpha(col::Up(), 0.8f), 2);
    }
    g_cv.Text(L"시간대별 · 파랑 올림 / 초록 받음", {ch.l + 12, ch.b - 17, ch.r - 12, ch.b - 3}, col::Muted(), {10});
    y += 74;
    int64_t fm = g_sum.folders.empty() ? 1 : g_sum.folders[0].count;
    g_cv.PushClip(r);
    DrawSumList(y, r, L"가장 많이 움직인 폴더", g_sum.folders, fm, true);
    DrawSumList(y, r, L"오류가 난 곳", g_sum.errors, 0, false);
    DrawSumList(y, r, L"충돌 사본을 만든 파일", g_sum.conflicts, 0, false);
    if (g_sum.lines == 0) g_cv.Text(L"이 기간에는 활동이 없습니다", {r.l, y, r.r, y + 30}, col::Muted(), {12, DWRITE_FONT_WEIGHT_NORMAL, ui::Center});
    g_cv.PopClip();
}

// ---------- 기록 탭 ----------
static void DrawLogTab(Rect r) {
    float y = r.t, x = r.l;
    const wchar_t* fl[] = {L"전체", L"막힘·오류", L"삭제·충돌", L"그 밖"};
    const wchar_t* ft[] = {L"모든 알림과 한 일", L"막힘·풀림·오류·연결 끊김·강제 종료", L"대량 삭제 보류·확인, 충돌", L"멈춤·다시 시작·설정·시작"};
    for (int i = 0; i < 4; i++) {
        float w = BtnW(fl[i], 0, 11.5f, 11);
        Btn(H_LOGF + i, {x, y, x + w, y + 26}, fl[i], 0, ft[i], g_logFilter == i ? 2 : 0, 11.5f);
        x += w + 6;
    }
    float ow = BtnW(L"파일로 열기", 0xE8A7, 11.5f, 10);
    Btn(H_LOGOPEN, {r.r - ow, y, r.r, y + 26}, L"파일로 열기", 0xE8A7, L"알림 기록 파일 전체를 메모장으로 엽니다", 0, 11.5f);
    y += 36;
    Rect lr{r.l - 6, y, r.r + 6, r.b};
    g_rcLog = lr;
    g_logPaths.clear();
    std::vector<const Alert*> sel;
    for (auto& a : g_alerts) if (g_logFilter == 0 || AlertGroup(a.kind) == g_logFilter) sel.push_back(&a);
    const float rh = 50;
    g_scrollLog = std::clamp(g_scrollLog, 0.f, std::max(0.f, sel.size() * rh - lr.h()));
    g_cv.PushClip(lr);
    for (int i = (int)(g_scrollLog / rh); i < (int)sel.size(); i++) {
        float yy = lr.t + i * rh - g_scrollLog;
        if (yy > lr.b) break;
        const Alert& a = *sel[i];
        int idx = (int)g_logPaths.size(); g_logPaths.push_back(a.path);
        Rect row{lr.l, yy, lr.r, yy + rh - 2};
        if (g_cv.hover == H_LOGROW + idx) g_cv.Fill(row, col::White(0.07f), 8);
        g_cv.AddHit(row, H_LOGROW + idx, a.msg + (a.path.empty() ? L"" : L"\n" + a.path), false);
        float gy = a.path.empty() ? row.t + (rh - 2 - 22) / 2 : row.t + 4;
        g_cv.Icon(AlertGlyph(a.kind), {row.l + 6, gy, row.l + 30, gy + 22}, AlertColor(a.kind), 13);
        float my = a.path.empty() ? row.t + (rh - 2 - 18) / 2 : row.t + 5;
        g_cv.Text(a.msg, {row.l + 36, my, row.r - 84, my + 18}, col::Text(), {12});
        if (!a.path.empty()) g_cv.Text(a.path, {row.l + 36, row.t + 24, row.r - 10, row.t + 40}, col::Muted(), {11});
        g_cv.Text(ShortTime(a.time), {row.r - 84, my, row.r - 8, my + 18}, col::Muted(), {11, DWRITE_FONT_WEIGHT_NORMAL, ui::Right});
    }
    if (sel.empty()) g_cv.Text(L"해당하는 기록이 없습니다", lr, col::Muted(), {12, DWRITE_FONT_WEIGHT_NORMAL, ui::Center});
    g_cv.PopClip();
}

// ---------- 아이콘 뜻 ----------
static void DrawLegend(Rect r) {
    g_cv.Fill(r, Hex(0xFF1B1E25), 12); g_cv.Stroke(r, col::White(0.12f), 12);
    Rect in = r.inset(16, 14);
    g_cv.Text(L"아이콘 뜻", {in.l, in.t, in.r, in.t + 20}, col::Text(), {13, DWRITE_FONT_WEIGHT_SEMI_BOLD});
    float y = in.t + 30;
    struct { bool tray; uint32_t c; wchar_t g; const wchar_t* t; } rows[] = {
        {true, 0xFF10B981, 0xE73E, L"트레이 초록 — 모두 맞춰져 있음 (대기)"},
        {true, 0xFF3B82F6, 0xE895, L"트레이 파랑 — 맞추는 중"},
        {true, 0xFFEF4444, 0xE7BA, L"트레이 빨강 — 막힘 (1분 넘게 잡혀 있거나 계속 실패) · 대량 삭제 확인 필요"},
        {true, 0xFFF59E0B, 0xE871, L"트레이 주황 — NAS 에 연결할 수 없음"},
        {true, 0xFF8B5CF6, 0xE769, L"트레이 보라 — 잠시 멈춤"},
    };
    for (auto& rw : rows) {
        g_cv.Dot(in.l + 10, y + 11, 9, Hex(rw.c)); g_cv.Icon(rw.g, {in.l + 1, y + 2, in.l + 19, y + 20}, col::White(1), 10);
        g_cv.Text(rw.t, {in.l + 28, y, in.r, y + 22}, col::Sub(), {11.5f}); y += 26;
    }
    y += 6;
    for (int c : {C_UP, C_DOWN, C_MOVE, C_DEL, C_CONFLICT, C_WAIT, C_ERR}) {
        g_cv.Icon(CatGlyph(c), {in.l, y, in.l + 20, y + 22}, CatColor(c), 14);
        g_cv.Text(CatName(c), {in.l + 28, y, in.r, y + 22}, col::Sub(), {11.5f}); y += 25;
    }
    y += 6;
    g_cv.Text(L"목록 줄: 두 번 누르면 탐색기에서 보기 · 오른쪽 클릭하면 경로 복사·폴더 열기", {in.l, y, in.r, y + 34}, col::Muted(), {11, DWRITE_FONT_WEIGHT_NORMAL, ui::Left, true, true});
    g_cv.AddHit(r, H_LEGEND, L"", false);
}

// ---------- 그리기 ----------
static void Render() {
    Overall o = GetOverall();
    g_cv.Begin(Hex(0xFF1B1E25));
    float WW = g_cv.W(), HH = g_cv.H();
    g_cv.Gradient({0, 0, WW, HH}, Hex(0xFF1F232B), Hex(0xFF15171D), 0, false);
    Rect c{18, 15, WW - 18, HH - 14};
    // 머리줄
    g_rcHeader = {0, 0, WW, 64};
    Rect tile{c.l, c.t, c.l + 36, c.t + 36};
    bool tileHot = g_cv.hover == H_RESCAN;
    g_cv.Gradient(tile, Hex(tileHot ? 0xFF6FA3FF : 0xFF4F8CFF), Hex(tileHot ? 0xFFA07CFA : 0xFF8B5CF6), 10, true);
    g_cv.Icon(g_rescanning ? 0xE72C : 0xE895, tile, col::White(1), 17);
    g_cv.AddHit(tile, H_RESCAN, g_rescanning ? L"NAS 다시 확인하는 중…" : L"NAS 다시 확인 — File Station 등으로 NAS에서 바꾼 것을 지금 바로 찾아 맞춥니다 (평소에는 바로 알지만, 폴더가 아주 많으면 일부는 15분마다 확인)", true);
    g_cv.Text(L"Mydrive", {c.l + 48, c.t, c.r - 200, c.t + 20}, col::Text(), {15.5f, DWRITE_FONT_WEIGHT_SEMI_BOLD});
    g_cv.Text(L"NAS " + ServerHost() + Fmt(L" · 연결 %d개", (int)g_engines.size()), {c.l + 48, c.t + 20, c.r - 200, c.t + 36}, col::Muted(), {11});
    float bx = c.r;
    struct { int id; wchar_t g; const wchar_t* tip; } hb[] = {
        {H_CLOSE, 0xE8BB, L"닫기 (트레이에서 계속 동기화)"},
        {H_PIN, (wchar_t)(g_pinned ? 0xE841 : 0xE718), L"창 고정 — 다른 곳을 눌러도 닫히지 않음"},
        {H_HELP, 0xE897, L"아이콘 뜻 보기"},
        {H_SET, 0xE713, L"설정 — 연결, 동기화 규칙, 속도"}};
    for (auto& b : hb) { bx -= 28; Btn(b.id, {bx, c.t + 5, bx + 26, c.t + 31}, L"", b.g, b.tip, b.id == H_PIN && g_pinned ? 2 : 1); bx -= 2; }
    float pw = g_cv.TextWidth(o.text, {11.5f, DWRITE_FONT_WEIGHT_SEMI_BOLD}) + 33;
    Rect pill{bx - 6 - pw, c.t + 7, bx - 6, c.t + 29};
    g_cv.Fill(pill, Alpha(o.color, 0.14f), 11);
    g_cv.Dot(pill.l + 13, pill.t + 11, 3.5f, o.color);
    g_cv.Text(o.text, {pill.l + 22, pill.t, pill.r - 6, pill.b}, o.color, {11.5f, DWRITE_FONT_WEIGHT_SEMI_BOLD});
    // 탭
    Rect tb{c.l, c.t + 50, c.r, c.t + 50 + 36};
    g_cv.Fill(tb, col::White(0.078f), 10);
    const wchar_t* tn[] = {L"현황", L"요약", L"기록"}; wchar_t tg[] = {0xE9D9, 0xE9D2, 0xE81C};
    float tw = (tb.w() - 6) / 3;
    for (int i = 0; i < 3; i++) {
        Rect t{tb.l + 3 + i * tw, tb.t + 3, tb.l + 3 + (i + 1) * tw, tb.b - 3};
        bool sel = g_tab == i, hot = g_cv.hover == H_TAB0 + i;
        if (sel) g_cv.Fill(t, Hex(0x2E5B9BFF), 7); else if (hot) g_cv.Fill(t, col::White(0.07f), 7);
        Color fg = sel ? col::Text() : col::Muted();
        std::wstring lbl = tn[i];
        if (i == 2) { int n = 0; for (auto& a : g_alerts) if (TsOf(a.time) > NowTs() - 3600 && AlertGroup(a.kind) == 1) n++; if (n) lbl += Fmt(L" %d", n); }
        float lw = g_cv.TextWidth(lbl, {12.5f}) + 19;
        float x = t.l + (t.w() - lw) / 2;
        g_cv.Icon(tg[i], {x, t.t, x + 13, t.b}, fg, 12);
        g_cv.Text(lbl, {x + 19, t.t, t.r, t.b}, fg, {12.5f});
        g_cv.AddHit(t, H_TAB0 + i);
    }
    Rect body{c.l, tb.b + 12, c.r, c.b - 22};
    if (g_tab == 0) DrawNowTab(body, o); else if (g_tab == 1) DrawSumTab(body); else DrawLogTab(body);
    std::wstring foot = g_tab == 0 ? L"↑ PC → NAS    ↓ NAS → PC" : g_tab == 1 ? L"Mydrive 활동 기록(activity.log)으로 계산" : L"Mydrive 가 알린 것 · 한 일";
    g_cv.Text(foot, {c.l, c.b - 18, c.r, c.b}, col::Muted(), {10.5f});
    if (g_legend) DrawLegend({c.l + 4, tb.t, c.r - 4, std::min(c.b - 24, tb.t + 418)});
    g_cv.End();
}

// ---------- 창 보이기 ----------
static void OpenSettings();
static void Popup_Show(int tab = -1) {
    if (tab >= 0) g_tab = tab;
    RECT wa; SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
    UINT dpi = GetDpiForWindow(g_pop); float s = dpi / 96.f;
    int w = (int)(490 * s), h = std::min((int)(760 * s), (int)(wa.bottom - wa.top - 16 * s));
    SetWindowPos(g_pop, HWND_TOPMOST, wa.right - w - (int)(12 * s), wa.bottom - h - (int)(12 * s), w, h, SWP_SHOWWINDOW);
    g_cv.Resize();
    SetForegroundWindow(g_pop);
    InvalidateRect(g_pop, nullptr, FALSE);
}
static void Popup_Hide() { g_hiddenAt = GetTickCount64(); ShowWindow(g_pop, SW_HIDE); }
static void Popup_Toggle() {
    if (IsWindowVisible(g_pop)) Popup_Hide();
    else if (GetTickCount64() - g_hiddenAt > 400) Popup_Show();   // 트레이를 눌러 닫힌 직후의 같은 클릭은 무시
}

static bool ConfirmKill(const std::wstring& name, DWORD pid) {
    std::wstring m = L"'" + name + Fmt(L"' (프로세스 %u) 을(를) 강제로 끌까요?\n\n그 프로그램에서 저장하지 않은 내용은 사라집니다.\n가능하면 그 프로그램에서 직접 저장하고 닫는 것이 안전합니다.", pid);
    return MessageBoxW(g_pop, m.c_str(), L"Mydrive · 강제 종료", MB_YESNO | MB_ICONWARNING | MB_DEFBUTTON2 | MB_TOPMOST) == IDYES;
}
static void KillHolder(int k, int j) {
    if (k >= (int)g_stuckView.size() || j >= (int)g_stuckView[k].holders.size()) return;
    Holder h = g_stuckView[k].holders[j];
    if (!ConfirmKill(h.name, h.pid)) return;
    HANDLE p = OpenProcess(PROCESS_TERMINATE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, h.pid);
    if (!p) { MessageBoxW(g_pop, L"끌 수 없습니다 (권한이 없거나 이미 꺼짐).", L"Mydrive", MB_ICONINFORMATION); return; }
    FILETIME c, e, k2, u;
    bool same = GetProcessTimes(p, &c, &e, &k2, &u) && CompareFileTime(&c, &h.start) == 0;   // 같은 번호의 다른 프로그램이 아닌지
    if (same && TerminateProcess(p, 1)) AddAlert(A_KILL, L"'" + h.name + L"' 을(를) 강제 종료함", g_stuckView[k].abs);
    else if (!same) MessageBoxW(g_pop, L"그 프로그램은 이미 다시 시작되었거나 꺼졌습니다. [다시 찾기]를 누르세요.", L"Mydrive", MB_ICONINFORMATION);
    CloseHandle(p);
    for (auto& eng : g_engines) eng->Kick();
    g_stuckViewAt = 0;
}
// 작업 하나: 멈춤 시간 고르기 (오른쪽 끝 [⏸] 또는 줄 오른쪽 클릭)
static void ConnMenu(int i, bool withPath) {
    if (i < 0 || i >= (int)g_engines.size()) return;
    Engine& e = *g_engines[i];
    HMENU m = CreatePopupMenu();
    AppendMenuW(m, MF_STRING, 15, L"이 작업만 지금 동기화");
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    if (e.Paused()) AppendMenuW(m, MF_STRING, 10, L"이 작업 다시 시작");
    else {
        AppendMenuW(m, MF_STRING, 11, L"이 작업만 30분 멈춤");
        AppendMenuW(m, MF_STRING, 12, L"이 작업만 1시간 멈춤");
        AppendMenuW(m, MF_STRING, 13, L"이 작업만 3시간 멈춤");
        AppendMenuW(m, MF_STRING, 14, L"이 작업만 다시 시작할 때까지 멈춤");
    }
    if (withPath) {
        AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(m, MF_STRING, 1, L"PC 폴더 열기");
        AppendMenuW(m, MF_STRING, 2, L"경로 복사");
    }
    POINT pt; GetCursorPos(&pt);
    int c = TrackPopupMenu(m, TPM_RETURNCMD | TPM_RIGHTBUTTON, pt.x, pt.y, 0, g_pop, nullptr);
    DestroyMenu(m);
    if (c == 15) { if (e.Paused()) PauseConn(i, 0); e.ClearCanceled(); e.RunNow(); }
    else if (c == 10) PauseConn(i, 0);
    else if (c == 11) PauseConn(i, 30);
    else if (c == 12) PauseConn(i, 60);
    else if (c == 13) PauseConn(i, 180);
    else if (c == 14) PauseConn(i, -1);
    else if (c == 1) ShellExecuteW(g_pop, L"open", e.cfg().localRoot.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
    else if (c == 2) CopyText(e.cfg().localRoot);
    if (c) Popup_Invalidate();
}
static void PathMenu(const std::wstring& path) {
    if (path.empty()) return;
    HMENU m = CreatePopupMenu();
    AppendMenuW(m, MF_STRING, 1, L"탐색기에서 보기");
    AppendMenuW(m, MF_STRING, 2, L"경로 복사");
    AppendMenuW(m, MF_STRING, 3, L"이 파일·폴더 열기");
    POINT pt; GetCursorPos(&pt);
    int c = TrackPopupMenu(m, TPM_RETURNCMD | TPM_RIGHTBUTTON, pt.x, pt.y, 0, g_pop, nullptr);
    DestroyMenu(m);
    if (c == 1) RevealInExplorer(path);
    else if (c == 2) CopyText(path);
    else if (c == 3) ShellExecuteW(g_pop, L"open", path.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
}
static const std::wstring* PathOfHit(int id) {
    if (id >= H_RECENTCOPY && id - H_RECENTCOPY < (int)g_recentPaths.size()) return &g_recentPaths[id - H_RECENTCOPY];
    if (id >= H_RECENT && id - H_RECENT < (int)g_recentPaths.size() && id < H_RECENTCOPY) return &g_recentPaths[id - H_RECENT];
    if (id >= H_SUMROW && id - H_SUMROW < (int)g_sumPaths.size() && id < H_LOGROW) return &g_sumPaths[id - H_SUMROW];
    if (id >= H_XFER && id - H_XFER < (int)g_xferPaths.size()) return &g_xferPaths[id - H_XFER];
    if (id >= H_LOGROW && id - H_LOGROW < (int)g_logPaths.size() && id < H_XFER) return &g_logPaths[id - H_LOGROW];
    return nullptr;
}
static void OnClick(int id) {
    if (id == H_CLOSE) { Popup_Hide(); return; }
    if (id == H_PIN) g_pinned = !g_pinned;
    else if (id == H_HELP) g_legend = !g_legend;
    else if (id == H_LEGEND) g_legend = false;
    else if (id == H_SET) { g_pinned = true; OpenSettings(); }
    else if (id >= H_TAB0 && id <= H_TAB2) { g_tab = id - H_TAB0; g_legend = false; }
    else if (id == H_SYNCNOW || id == H_RESUME) SyncNow();
    else if (id == H_RESCAN) RescanNow();
    else if (id == H_P30) PauseAll(30); else if (id == H_P60) PauseAll(60); else if (id == H_P180) PauseAll(180);
    else if (id == H_NOWOPEN && !g_lastFile.empty()) RevealInExplorer(LocalPathOf(g_lastConn, g_lastFile));
    else if (id == H_NOWCOPY && !g_lastFile.empty()) CopyText(LocalPathOf(g_lastConn, g_lastFile));
    else if (id >= H_XCANCEL && id < H_XCANCEL + 100 && id - H_XCANCEL < (int)g_xferRefs.size()) {
        auto& ref = g_xferRefs[id - H_XCANCEL];
        if (ref.first >= 0 && ref.first < (int)g_engines.size()) g_engines[ref.first]->CancelTransfer(ref.second);
    }
    else if (id == H_HELDMOVED) {
        int n = 0; for (auto& e : g_engines) { int mv = e->HeldMoved(nullptr); if (mv > 0) { e->ConfirmMoved(); n += mv; } }
        if (n) AddAlert(A_CONFIRM, Fmt(L"대량 삭제 중 %d개를 다른 동기화 작업으로 옮긴 것으로 처리 — 옛 자리는 휴지통으로", n));
    }
    else if (id == H_HELDLIST) {
        for (auto& e : g_engines) if (e->stats.heldDeletes > 0)
            ShellExecuteW(g_pop, L"open", L"notepad.exe", (L"\"" + e->HeldFile() + L"\"").c_str(), nullptr, SW_SHOWNORMAL);
    }
    else if (id == H_RESTOREDEL) {
        int64_t held = 0; for (auto& e : g_engines) held += e->stats.heldDeletes;
        for (auto& e : g_engines) if (e->stats.heldDeletes > 0) e->RestoreMassDelete();
        AddAlert(A_CONFIRM, Fmt(L"대량 삭제 %lld개를 지우지 않고 되살림", held));
    }
    else if (id == H_CONFIRMDEL) {
        int64_t held = 0; for (auto& e : g_engines) held += e->stats.heldDeletes;
        std::wstring main = Fmt(L"한쪽에서 %lld개가 한꺼번에 사라졌습니다", held);
        TASKDIALOG_BUTTON btns[] = {
            {101, L"되살리기 (권장)\n직접 지운 적이 없다면: 아무것도 지우지 않고, 사라진 쪽에 다시 만듭니다."},
            {102, L"반대쪽에서도 지우기\n직접 지운 게 맞다면: PC는 휴지통으로, NAS는 #recycle 폴더로 옮깁니다 (나중에 되살릴 수 있음)."}};
        TASKDIALOGCONFIG tc{sizeof tc};
        tc.hwndParent = g_pop; tc.dwFlags = TDF_USE_COMMAND_LINKS | TDF_ALLOW_DIALOG_CANCELLATION | TDF_POSITION_RELATIVE_TO_WINDOW;
        tc.pszWindowTitle = L"Mydrive · 대량 삭제 확인"; tc.pszMainIcon = TD_WARNING_ICON;
        tc.pszMainInstruction = main.c_str();
        tc.pszContent = L"지우기 전에 멈춰 두었습니다. 탐색기 연동을 다시 등록하거나 폴더를 옮기면, 지우지 않았는데도 이렇게 보일 수 있습니다.";
        tc.pButtons = btns; tc.cButtons = 2; tc.nDefaultButton = 101; tc.dwCommonButtons = TDCBF_CANCEL_BUTTON;
        int btn = 0;
        if (SUCCEEDED(TaskDialogIndirect(&tc, &btn, nullptr, nullptr))) {
            if (btn == 101) {
                for (auto& e : g_engines) if (e->stats.heldDeletes > 0) e->RestoreMassDelete();
                AddAlert(A_CONFIRM, Fmt(L"대량 삭제 %lld개를 지우지 않고 되살림", held));
            } else if (btn == 102) {
                for (auto& e : g_engines) if (e->stats.heldDeletes > 0) e->ConfirmMassDelete();
                AddAlert(A_CONFIRM, Fmt(L"대량 삭제 %lld개를 확인함 — 반대쪽에서도 지움", held));
            }
        }
    }
    else if (id >= H_SUMCHIP && id < H_SUMCHIP + 4) { int ms[] = {10, 30, 60, 360}; g_sumMins = ms[id - H_SUMCHIP]; }
    else if (id == H_SUMREF) g_sum.at = 0;
    else if (id >= H_LOGF && id < H_LOGF + 4) { g_logFilter = id - H_LOGF; g_scrollLog = 0; }
    else if (id == H_LOGOPEN) ShellExecuteW(g_pop, L"open", L"notepad.exe", (L"\"" + AlertPath() + L"\"").c_str(), nullptr, SW_SHOWNORMAL);
    else if (id >= H_CONNSYNC && id < H_CONNSYNC + 100 && id - H_CONNSYNC < (int)g_engines.size()) {
        int k = id - H_CONNSYNC;
        if (g_engines[k]->Paused()) PauseConn(k, 0);
        g_engines[k]->ClearCanceled(); g_engines[k]->RunNow();
    }
    else if (id >= H_CONNPAUSE && id < H_CONNPAUSE + 100 && id - H_CONNPAUSE < (int)g_engines.size()) {
        int k = id - H_CONNPAUSE;
        if (g_engines[k]->Paused()) PauseConn(k, 0); else ConnMenu(k, false);
    }
    else if (id >= H_HELDDIR && id < H_HELDDIR + 3 && id - H_HELDDIR < (int)g_heldDirAbs.size()) RevealInExplorer(g_heldDirAbs[id - H_HELDDIR]);
    else if (id >= H_CONN && id < H_CONN + 100 && id - H_CONN < (int)g_engines.size()) ShellExecuteW(g_pop, L"open", g_engines[id - H_CONN]->cfg().localRoot.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
    else if (id >= H_STUCKCOPY && id < H_STUCKCOPY + 100 && id - H_STUCKCOPY < (int)g_stuckView.size()) CopyText(g_stuckView[id - H_STUCKCOPY].abs);
    else if (id >= H_STUCKOPEN && id < H_STUCKOPEN + 100 && id - H_STUCKOPEN < (int)g_stuckView.size()) RevealInExplorer(g_stuckView[id - H_STUCKOPEN].abs);
    else if (id >= H_STUCKFIND && id < H_STUCKFIND + 100) g_stuckViewAt = 0;
    else if (id >= H_KILL && id < H_KILL + 1000) KillHolder((id - H_KILL) / 10, (id - H_KILL) % 10);
    else if (id >= H_RECENTCOPY && id < H_SUMROW) { if (auto p = PathOfHit(id)) CopyText(*p); }
    InvalidateRect(g_pop, nullptr, FALSE);
}

static int HitIdAt(LPARAM lp) { auto* t = g_cv.HitAt(GET_X_LPARAM(lp) / g_cv.scale, GET_Y_LPARAM(lp) / g_cv.scale); return t ? t->id : 0; }
static LRESULT CALLBACK PopupProc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_CREATE: {
        g_pop = h;
        BOOL dark = TRUE; DwmSetWindowAttribute(h, 20 /*DWMWA_USE_IMMERSIVE_DARK_MODE*/, &dark, sizeof dark);
        int corner = 2 /*DWMWCP_ROUND*/; DwmSetWindowAttribute(h, 33 /*DWMWA_WINDOW_CORNER_PREFERENCE*/, &corner, sizeof corner);
        COLORREF border = RGB(58, 62, 72); DwmSetWindowAttribute(h, 34 /*DWMWA_BORDER_COLOR*/, &border, sizeof border);
        g_cv.Init(h);
        g_popTip = CreateWindowExW(WS_EX_TOPMOST, TOOLTIPS_CLASSW, nullptr, WS_POPUP | TTS_ALWAYSTIP | TTS_NOPREFIX, 0, 0, 0, 0, h, nullptr, g_inst, nullptr);
        TOOLINFOW ti{sizeof ti}; ti.uFlags = TTF_IDISHWND | TTF_SUBCLASS; ti.hwnd = h; ti.uId = (UINT_PTR)h; ti.lpszText = LPSTR_TEXTCALLBACKW;
        SendMessageW(g_popTip, TTM_ADDTOOLW, 0, (LPARAM)&ti);
        SendMessageW(g_popTip, TTM_SETMAXTIPWIDTH, 0, 420);
        SendMessageW(g_popTip, TTM_SETDELAYTIME, TTDT_AUTOPOP, 30000);
        return 0;
    }
    case WM_NOTIFY: {
        auto* nm = (NMHDR*)lp;
        if (nm->hwndFrom == g_popTip && nm->code == TTN_GETDISPINFOW) { ((NMTTDISPINFOW*)lp)->lpszText = (LPWSTR)g_hoverTip.c_str(); return 0; }
        return 0;
    }
    case WM_PAINT: { PAINTSTRUCT ps; BeginPaint(h, &ps); Render(); EndPaint(h, &ps); return 0; }
    case WM_ERASEBKGND: return 1;
    case WM_SIZE: g_cv.Resize(); InvalidateRect(h, nullptr, FALSE); return 0;
    case WM_DPICHANGED: { RECT* r = (RECT*)lp; SetWindowPos(h, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top, SWP_NOZORDER | SWP_NOACTIVATE); g_cv.Resize(); return 0; }
    case WM_ACTIVATE:
        if (LOWORD(wp) == WA_INACTIVE && !g_pinned && IsWindowVisible(h)) {
            HWND to = (HWND)lp;   // 우리 메시지 상자·메뉴로 넘어간 것이면 닫지 않음
            if (!to || GetWindow(to, GW_OWNER) != h) Popup_Hide();
        }
        return 0;
    case WM_NCHITTEST: {
        LRESULT r = DefWindowProcW(h, msg, wp, lp);
        if (r != HTCLIENT) return r;
        POINT pt{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)}; ScreenToClient(h, &pt);
        float x = pt.x / g_cv.scale, y = pt.y / g_cv.scale;
        if (g_rcHeader.in(x, y) && !g_cv.HitAt(x, y)) return HTCAPTION;   // 머리줄을 끌어 창 옮기기
        return HTCLIENT;
    }
    case WM_MOUSEMOVE: {
        float x = GET_X_LPARAM(lp) / g_cv.scale, y = GET_Y_LPARAM(lp) / g_cv.scale;
        auto* hit = g_cv.HitAt(x, y);
        int id = hit ? hit->id : 0;
        std::wstring tip = hit ? hit->tip : L"";
        if (id != g_cv.hover) {
            g_cv.hover = id;
            if (tip != g_hoverTip) { g_hoverTip = tip; SendMessageW(g_popTip, TTM_POP, 0, 0); }
            InvalidateRect(h, nullptr, FALSE);
        }
        TRACKMOUSEEVENT tme{sizeof tme, TME_LEAVE, h, 0}; TrackMouseEvent(&tme);
        return 0;
    }
    case WM_MOUSELEAVE: g_cv.hover = 0; g_hoverTip.clear(); InvalidateRect(h, nullptr, FALSE); return 0;
    case WM_SETCURSOR:
        if (LOWORD(lp) == HTCLIENT) {
            POINT pt; GetCursorPos(&pt); ScreenToClient(h, &pt);
            auto* hit = g_cv.HitAt(pt.x / g_cv.scale, pt.y / g_cv.scale);
            SetCursor(LoadCursorW(nullptr, hit && hit->hand && hit->id ? IDC_HAND : IDC_ARROW));
            return TRUE;
        }
        break;
    case WM_LBUTTONDOWN: g_cv.hover = HitIdAt(lp); g_cv.pressed = g_cv.hover; SetCapture(h); InvalidateRect(h, nullptr, FALSE); return 0;
    case WM_LBUTTONUP: {
        ReleaseCapture();
        int p = g_cv.pressed; g_cv.pressed = 0;
        if (p && p == HitIdAt(lp)) OnClick(p);
        InvalidateRect(h, nullptr, FALSE);
        return 0;
    }
    case WM_LBUTTONDBLCLK:
        g_cv.hover = HitIdAt(lp);
        if (auto p = PathOfHit(g_cv.hover)) RevealInExplorer(*p);
        return 0;
    case WM_RBUTTONUP:
        g_cv.hover = HitIdAt(lp);
        if (auto p = PathOfHit(g_cv.hover)) PathMenu(*p);
        else if (g_cv.hover >= H_CONN && g_cv.hover < H_CONN + 100 && g_cv.hover - H_CONN < (int)g_engines.size()) ConnMenu(g_cv.hover - H_CONN, true);
        else if (g_cv.hover >= H_CONNPAUSE && g_cv.hover < H_CONNPAUSE + 100) ConnMenu(g_cv.hover - H_CONNPAUSE, false);
        return 0;
    case WM_MOUSEWHEEL: {
        POINT pt{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)}; ScreenToClient(h, &pt);
        float x = pt.x / g_cv.scale, y = pt.y / g_cv.scale, d = -GET_WHEEL_DELTA_WPARAM(wp) / 120.f * 66;
        if (g_tab == 0 && g_rcRecent.in(x, y)) g_scrollRecent += d;
        if (g_tab == 2 && g_rcLog.in(x, y)) g_scrollLog += d;
        InvalidateRect(h, nullptr, FALSE);
        return 0;
    }
    case WM_KEYDOWN: if (wp == VK_ESCAPE) Popup_Hide(); return 0;
    case WM_CLOSE: Popup_Hide(); return 0;
    }
    return DefWindowProcW(h, msg, wp, lp);
}
static void Popup_Create() {
    WNDCLASSEXW wc{sizeof wc};
    wc.style = CS_DBLCLKS | CS_DROPSHADOW; wc.lpfnWndProc = PopupProc; wc.hInstance = g_inst; wc.lpszClassName = L"MydrivePopup";
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hIcon = LoadIconW(g_inst, MAKEINTRESOURCEW(1));
    RegisterClassExW(&wc);
    CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_TOPMOST, L"MydrivePopup", L"Mydrive", WS_POPUP, 0, 0, 490, 760, nullptr, nullptr, g_inst, nullptr);
}

// ---------- 트레이 메뉴 (SynoWatch 와 같은 구성) ----------
enum { IDM_OPEN = 300, IDM_COPYNOW, IDM_COPYSTUCK, IDM_SUM, IDM_LOG, IDM_P30, IDM_P60, IDM_P180, IDM_SYNC, IDM_SETTINGS, IDM_UPDATE, IDM_EXIT, IDM_RESCAN };
static std::wstring UpdateMenuLabel();   // update.inc: 새 판이 있으면 메뉴 글자, 없으면 빈 값
static void UpdateInstallNow();
static void TrayMenu() {
    // 묶음: 창 / 동기화 / 경로·보기 / 설정 / 종료 (메뉴 색은 app.cpp DarkMenus 로 어둡게)
    HMENU m = CreatePopupMenu();
    AppendMenuW(m, MF_STRING | MF_DEFAULT, IDM_OPEN, L"Mydrive 창 열기");
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING, IDM_SYNC, AnyPaused() ? L"지금 동기화 (멈춤 풀기)" : L"지금 동기화");
    AppendMenuW(m, MF_STRING | (g_rescanning ? MF_GRAYED : 0), IDM_RESCAN, g_rescanning ? L"NAS 다시 확인하는 중…" : L"NAS 다시 확인 (NAS에서 바꾼 것 바로 찾기)");
    HMENU p = CreatePopupMenu();
    AppendMenuW(p, MF_STRING, IDM_P30, L"30분"); AppendMenuW(p, MF_STRING, IDM_P60, L"1시간"); AppendMenuW(p, MF_STRING, IDM_P180, L"3시간");
    AppendMenuW(m, MF_POPUP, (UINT_PTR)p, L"잠시 멈춤");
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING, IDM_SUM, L"요약 보기");
    AppendMenuW(m, MF_STRING, IDM_LOG, L"기록 보기");
    AppendMenuW(m, MF_STRING | (g_lastFile.empty() ? MF_GRAYED : 0), IDM_COPYNOW, L"지금 위치 경로 복사");
    AppendMenuW(m, MF_STRING, IDM_COPYSTUCK, L"막힌 경로 복사");
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING, IDM_SETTINGS, L"설정…");
    std::wstring upd = UpdateMenuLabel();
    if (!upd.empty()) AppendMenuW(m, MF_STRING, IDM_UPDATE, upd.c_str());
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING, IDM_EXIT, L"Mydrive 종료");
    POINT pt; GetCursorPos(&pt);
    SetForegroundWindow(g_main);
    TrackPopupMenu(m, TPM_RIGHTBUTTON | TPM_BOTTOMALIGN, pt.x, pt.y, 0, g_main, nullptr);
    PostMessageW(g_main, WM_NULL, 0, 0);
    DestroyMenu(m);
}
static void TrayCommand(int id) {
    switch (id) {
    case IDM_OPEN: Popup_Show(); break;
    case IDM_COPYNOW: if (!g_lastFile.empty()) CopyText(LocalPathOf(g_lastConn, g_lastFile)); break;
    case IDM_COPYSTUCK: {
        Overall o = GetOverall();
        if (o.stuck.empty()) TrayBalloon(L"Mydrive", L"지금 막힌 항목이 없습니다", NIIF_INFO);
        else CopyText(LocalPathOf(ConnName(o.stuck[0].first), W(o.stuck[0].second.p)));
        break;
    }
    case IDM_SUM: Popup_Show(1); break;
    case IDM_LOG: Popup_Show(2); break;
    case IDM_P30: PauseAll(30); break;
    case IDM_P60: PauseAll(60); break;
    case IDM_P180: PauseAll(180); break;
    case IDM_SYNC: SyncNow(); break;
    case IDM_RESCAN: RescanNow(); break;
    case IDM_SETTINGS: OpenSettings(); break;
    case IDM_UPDATE: UpdateInstallNow(); break;
    }
}
static void RefreshMain() { Popup_Invalidate(); }
