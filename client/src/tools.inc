// tools.inc — 탐색기 오른쪽 클릭 메뉴와 그 창 (이전 버전 / 지운 파일 되살리기 / NAS 경로 복사)
//   app.cpp 안에 포함됨. 메뉴를 누르면 Mydrive.exe --versions "경로" 처럼 새로 실행되어 이 창만 띄움.

// ---------- 오른쪽 클릭 메뉴 등록 (Mydrive 폴더 안에서만 보이게) ----------
// Windows 11 탐색기는 사용자별(HKCU) 메뉴 등록을 무시한다 (시험으로 확인: HKCU의 어느 자리에 넣어도 안 보임).
// 그래서 PC 전체(HKLM)에 넣고, 넣고 빼는 일만 관리자 승인을 받아 따로 실행한다:
//   Mydrive.exe --register-shell / --unregister-shell
// 어느 폴더에서 보일지는 경로 대신 'Mydrive가 관리하는 항목' 속성으로 고른다 → 연결이 바뀌어도 다시 등록할 필요 없음.
static const wchar_t* kVerbKeys[] = {L"Software\\Classes\\AllFilesystemObjects\\shell\\Mydrive", L"Software\\Classes\\Directory\\Background\\shell\\Mydrive"};
static HKEY g_verbHive = HKEY_LOCAL_MACHINE;
static void RegSz(const std::wstring& key, const wchar_t* name, const std::wstring& v) {
    RegSetKeyValueW(g_verbHive, key.c_str(), name, REG_SZ, v.c_str(), (DWORD)((v.size() + 1) * sizeof(wchar_t)));
}
static void RemoveOldUserVerbs() { for (auto k : kVerbKeys) RegDeleteTreeW(HKEY_CURRENT_USER, k); }   // 예전 판이 넣던 것

// ---------- 탐색기 왼쪽 목록 (Mydrive · 연결이름) ----------
// 탐색 연동을 켜면 Windows가 사용자 영역(HKCU)에 목록 항목을 만들지만, 이 Windows 11은 새로 생긴 HKCU 항목을 무시함
// (시험: HKCU만으로는 안 보이고, 같은 항목을 PC 전체(HKLM)에도 넣으니 보임). 그래서 관리자 승인 때 HKLM에 옮겨 적음.
static const wchar_t* kNsKey = L"Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Desktop\\NameSpace";
static std::vector<std::wstring> MydriveNavIds() {   // Windows가 만든 Mydrive 연결 항목 (이름이 "Mydrive!" 로 시작)
    std::vector<std::wstring> v; HKEY k;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, kNsKey, 0, KEY_READ, &k) != ERROR_SUCCESS) return v;
    wchar_t name[128];
    for (DWORD i = 0;; i++) {
        DWORD n = 128; if (RegEnumKeyExW(k, i, name, &n, nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS) break;
        wchar_t def[512]; DWORD dn = sizeof def;
        if (RegGetValueW(k, name, nullptr, RRF_RT_REG_SZ, nullptr, def, &dn) == ERROR_SUCCESS && wcsncmp(def, L"Mydrive!", 8) == 0) v.push_back(name);
    }
    RegCloseKey(k);
    return v;
}
static std::vector<std::wstring> MirroredNavIds() {   // 우리가 HKLM에 옮겨 적은 것 (표시값 MydriveNav)
    std::vector<std::wstring> v; HKEY k;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, kNsKey, 0, KEY_READ, &k) != ERROR_SUCCESS) return v;
    wchar_t name[128];
    for (DWORD i = 0;; i++) {
        DWORD n = 128; if (RegEnumKeyExW(k, i, name, &n, nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS) break;
        DWORD mark = 0, mn = sizeof mark;
        if (RegGetValueW(k, name, L"MydriveNav", RRF_RT_REG_DWORD, nullptr, &mark, &mn) == ERROR_SUCCESS && mark == 1) v.push_back(name);
    }
    RegCloseKey(k);
    return v;
}
static void RemoveNavMirror(const std::wstring& id) {
    RegDeleteTreeW(HKEY_LOCAL_MACHINE, (std::wstring(kNsKey) + L"\\" + id).c_str());
    RegDeleteTreeW(HKEY_LOCAL_MACHINE, (L"Software\\Classes\\CLSID\\" + id).c_str());
}
static bool MirrorNav() {   // 예전 판이 HKLM 에 만든 탐색 창 사본을 지움 (지금은 앱이 'Mydrive' 하나만 직접 만듦)
    for (auto& id : MirroredNavIds()) RemoveNavMirror(id);
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
    return true;
}
static bool NavMirrorsCurrent() {
    auto want = MydriveNavIds(), have = MirroredNavIds();
    std::sort(want.begin(), want.end()); std::sort(have.begin(), have.end());
    return want == have;
}

static bool RemoveShellVerbs() {
    bool ok = true;
    for (auto k : kVerbKeys) { LSTATUS s = RegDeleteTreeW(HKEY_LOCAL_MACHINE, k); if (s != ERROR_SUCCESS && s != ERROR_FILE_NOT_FOUND) ok = false; }
    for (auto& id : MirroredNavIds()) RemoveNavMirror(id);
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
    return ok;
}
// 메뉴 구성이 바뀌면 이 숫자를 올림 → 업데이트 설치 때 다시 등록 (관리자 승인 한 번)
static const DWORD kMenuVersion = 5;   // 3: 항목마다 아이콘, 4: 항상 유지/공간 확보
// 지금 등록된 메뉴가 이 실행 파일·이 구성인지
static bool ShellVerbsCurrent() {
    wchar_t buf[1024]; DWORD n = sizeof buf, ver = 0, vn = sizeof ver;
    if (RegGetValueW(HKEY_LOCAL_MACHINE, kVerbKeys[0], L"MydriveMenuVersion", RRF_RT_REG_DWORD, nullptr, &ver, &vn) != ERROR_SUCCESS || ver != kMenuVersion) return false;
    std::wstring k = std::wstring(kVerbKeys[0]) + L"\\shell\\99open\\command";
    if (RegGetValueW(HKEY_LOCAL_MACHINE, k.c_str(), nullptr, RRF_RT_REG_SZ, nullptr, buf, &n) != ERROR_SUCCESS) return false;
    return _wcsicmp(buf, (L"\"" + ExePath() + L"\"").c_str()) == 0 && MirroredNavIds().empty();
}
static bool RegisterShellVerbs() {
    for (auto k : kVerbKeys) RegDeleteTreeW(HKEY_LOCAL_MACHINE, k);
    HKEY t;
    if (RegCreateKeyExW(HKEY_LOCAL_MACHINE, kVerbKeys[0], 0, nullptr, 0, KEY_SET_VALUE, nullptr, &t, nullptr) != ERROR_SUCCESS) return false;   // 관리자 아님
    RegCloseKey(t);
    std::wstring applies = L"System.StorageProviderId:=\"Mydrive\"";
    std::wstring exe = ExePath(), q = L"\"" + exe + L"\"";
    for (int bg = 0; bg < 2; bg++) {
        std::wstring k = kVerbKeys[bg];
        RegSz(k, L"MUIVerb", L"Mydrive");
        RegSz(k, L"Icon", exe + L",0");
        RegSz(k, L"SubCommands", L"");
        RegSz(k, L"AppliesTo", applies);
        if (bg == 0) { DWORD ver = kMenuVersion; RegSetKeyValueW(HKEY_LOCAL_MACHINE, k.c_str(), L"MydriveMenuVersion", REG_DWORD, &ver, sizeof ver); }
        std::wstring arg = bg ? L"\"%V\"" : L"\"%1\"";
        // 원드라이브처럼: 파일에 하는 일(유지·공간 확보·버전·경로) 먼저, 동기화 제어는 '동기화' 아래로
        //   키 이름 순서대로 나옴. sep: 위에 구분선. file: 파일·폴더를 눌렀을 때만 (빈 곳 메뉴에는 없음)
        struct V { const wchar_t* id; const wchar_t* name; const wchar_t* sw; const wchar_t* icon; bool sep, file; };
        static const V top[] = {
            {L"01keep", L"항상 이 장치에 유지", L"--keep", L"-209", false, false},
            {L"02free", L"공간 확보", L"--free", L"-210", false, false},
            {L"11versions", L"버전 기록…", L"--versions", L"-206", true, true},
            {L"12deleted", L"지운 파일 되살리기…", L"--deleted", L"-207", false, false},
            {L"13copy", L"NAS 경로 복사", L"--copy-nas", L"-208", false, false},
            {L"21status", L"동기화 상태 보기…", L"--status", L"-205", true, false},
            {L"22sync", L"동기화", nullptr, L"-201", false, false},   // 하위 메뉴 (아래)
            {L"99open", L"Mydrive 열기", L"", L"0", true, false},
        };
        static const V sub[] = {
            {L"1now", L"지금 동기화", L"--sync-now", L"-201", false, false},
            {L"2p30", L"30분 일시 중지", L"--pause30", L"-202", true, false},
            {L"3p60", L"1시간 일시 중지", L"--pause60", L"-202", false, false},
            {L"4p180", L"3시간 일시 중지", L"--pause180", L"-202", false, false},
            {L"5resume", L"다시 시작", L"--resume", L"-203", false, false},
            {L"6skip", L"이 폴더 동기화에서 제외 / 다시 포함", L"--toggle-skip", L"-204", true, true},
        };
        auto put = [&](const std::wstring& base, const V& v, bool firstShown) {
            std::wstring vk = base + L"\\shell\\" + v.id;
            RegSz(vk, L"MUIVerb", v.name);
            if (*v.icon) RegSz(vk, L"Icon", exe + L"," + v.icon);   // Mydrive.exe 안의 아이콘
            if (v.sep && !firstShown) { DWORD f = 0x20; RegSetKeyValueW(HKEY_LOCAL_MACHINE, vk.c_str(), L"CommandFlags", REG_DWORD, &f, sizeof f); }   // ECF_SEPARATORBEFORE
            if (v.sw) RegSz(vk + L"\\command", nullptr, *v.sw ? q + L" " + v.sw + L" " + arg : q);
            return vk;
        };
        bool first = true;
        for (auto& v : top) {
            if (bg && v.file) continue;
            bool sepHere = v.sep || (bg && wcscmp(v.id, L"12deleted") == 0);   // 빈 곳 메뉴에는 버전 기록이 없으니 구분선을 여기에
            V vv = v; vv.sep = sepHere;
            std::wstring vk = put(k, vv, first); first = false;
            if (!v.sw) {   // '동기화' 하위 메뉴
                RegSz(vk, L"SubCommands", L"");
                bool f2 = true;
                for (auto& c : sub) { if (bg && c.file) continue; put(vk, c, f2); f2 = false; }
            }
        }
    }
    MirrorNav();   // 탐색기 왼쪽 목록도 함께
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
    return true;
}
// 관리자 승인 창을 띄워 --register-shell / --unregister-shell 을 실행하고 끝날 때까지 기다림
static bool RunElevated(const wchar_t* arg) {
    std::wstring exe = ExePath();
    SHELLEXECUTEINFOW se{sizeof se};
    se.fMask = SEE_MASK_NOCLOSEPROCESS; se.lpVerb = L"runas"; se.lpFile = exe.c_str(); se.lpParameters = arg; se.nShow = SW_HIDE;
    if (!ShellExecuteExW(&se) || !se.hProcess) return false;   // 승인 거절
    WaitForSingleObject(se.hProcess, 30000);
    DWORD code = 1; GetExitCodeProcess(se.hProcess, &code); CloseHandle(se.hProcess);
    return code == 0;
}

// ---------- 경로 → 연결 ----------
struct ConnPath { int conn = -1; std::string rel; };
static ConnPath FindConn(std::wstring path) {
    if (path.compare(0, 4, L"\\\\?\\") == 0) path = path.substr(4);
    while (path.size() > 3 && path.back() == L'\\') path.pop_back();
    for (int i = 0; i < (int)g_cfg.conns.size(); i++) {
        std::wstring root = g_cfg.conns[i].local; while (root.size() > 3 && root.back() == L'\\') root.pop_back();
        if (root.empty() || path.size() < root.size() || _wcsnicmp(path.c_str(), root.c_str(), root.size()) != 0) continue;
        if (path.size() == root.size()) return {i, ""};
        if (path[root.size()] != L'\\') continue;
        std::wstring r = path.substr(root.size() + 1);
        for (auto& ch : r) if (ch == L'\\') ch = L'/';
        return {i, U(r)};
    }
    return {};
}
static std::wstring NsText(int64_t ns) {
    if (ns <= 0) return L"";
    FILETIME ft = NsToFt(ns), lt; FileTimeToLocalFileTime(&ft, &lt);
    SYSTEMTIME st; FileTimeToSystemTime(&lt, &st);
    return Fmt(L"%04d-%02d-%02d %02d:%02d", st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute);
}
static std::wstring SizeW(int64_t b) {
    const wchar_t* u[] = {L"B", L"KB", L"MB", L"GB", L"TB"}; double v = (double)b; int i = 0;
    while (v >= 1024 && i < 4) { v /= 1024; i++; }
    return i == 0 ? Fmt(L"%.0f B", v) : Fmt(v < 10 ? L"%.1f %s" : L"%.0f %s", v, u[i]);
}

// ---------- NAS 경로 복사 ----------
static int CopyNasPath(const std::wstring& path) {
    ConnPath cp = FindConn(path);
    if (cp.conn < 0) { MessageBoxW(nullptr, L"Mydrive로 맞추는 폴더 안의 파일이 아닙니다.", L"Mydrive", MB_ICONINFORMATION); return 1; }
    auto& c = g_cfg.conns[cp.conn];
    // 서버가 알려 주는 NAS 공유 폴더 경로가 있으면 \\NAS\공유\... 로, 없으면 "연결이름/경로"
    std::wstring out;
    Http http(ActiveServer(), g_cfg.token);
    Resp r = http.Req(L"GET", "/v1/roots", {}, nullptr, 10);
    json j = r.ok() ? json::parse(r.body, nullptr, false) : json();
    if (j.is_array()) for (auto& e : j) if (e.value("name", std::string()) == c.name) {
        std::string share = e.value("share", std::string());   // 예: /DESKTOP/작업
        if (!share.empty()) {
            URL_COMPONENTS uc{sizeof uc}; wchar_t host[256] = {}; uc.lpszHostName = host; uc.dwHostNameLength = 256;
            WinHttpCrackUrl(ActiveServer().c_str(), 0, 0, &uc);
            out = L"\\\\" + std::wstring(host) + W(share);
            if (!cp.rel.empty()) out += L"/" + W(cp.rel);
            for (auto& ch : out) if (ch == L'/') ch = L'\\';
        }
    }
    if (out.empty()) out = L"NAS " + W(c.name) + (cp.rel.empty() ? L"" : L"/" + W(cp.rel));
    // 클립보드에는 주인 창이 있어야 함 (없으면 넣기가 실패)
    if (!g_main) g_main = CreateWindowExW(0, L"STATIC", L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, g_inst, nullptr);
    CopyText(out);
    return 0;
}

// ---------- 오른쪽 클릭의 동기화 명령 (떠 있는 Mydrive 안에서 실행) ----------
static std::shared_ptr<Engine> EngineOf(int conn) {
    if (conn < 0 || conn >= (int)g_cfg.conns.size()) return nullptr;
    for (auto& e : g_engines) if (e->cfg().name == g_cfg.conns[conn].name) return e;
    return nullptr;
}
static std::wstring ShowRel(int conn, const std::string& rel) {
    return W(g_cfg.conns[conn].name) + (rel.empty() ? L"" : L" / " + W(rel));
}

// 상태 창: 계산이 잠깐 걸릴 수 있어 따로 스레드에서 (Mydrive 창이 멈추지 않게)
static void StatusDialog(std::shared_ptr<Engine> e, std::wstring where, std::string rel, std::wstring abs) {
    ItemStatus s = e->Status(rel);
    bool paused = e->Paused(), online = e->stats.online.load();
    int waitN = s.upWait + s.downWait + s.delWait + s.both + s.checkWait;
    std::wstring head;
    if (!s.found) head = L"아직 Mydrive 목록에 없습니다";
    else if (s.excluded) head = L"동기화에서 제외한 항목입니다";
    else if (s.failing || s.locked) head = L"막힌 파일이 있습니다";
    else if (waitN || s.transferring) head = paused ? L"일시 중지 — 맞출 것이 남아 있습니다" : L"맞추는 중입니다";
    else if (!online) head = L"NAS에 연결되어 있지 않습니다";
    else head = paused ? L"일시 중지 중" : L"NAS와 모두 맞춰져 있습니다";
    std::wstring c = L"위치: " + abs + L"\n";
    if (!s.found) c += L"\n방금 만든 항목이면 몇 초 뒤 반영됩니다. 제외 규칙(설정의 '제외할 이름')에 걸린 이름일 수도 있습니다.\n";
    else if (s.excluded) c += L"\nPC와 NAS의 내용은 그대로 두고, 서로 맞추지 않습니다.\n다시 맞추려면 오른쪽 클릭 → Mydrive → '동기화에서 제외 / 다시 포함'.\n";
    else {
        if (s.dir) c += Fmt(L"파일 %d개 · 폴더 %d개 · %s\n", s.files, s.dirs > 0 ? s.dirs - (rel.empty() ? 0 : 1) : 0, SizeW(s.bytes).c_str());
        c += Fmt(L"\nNAS와 맞춰진 파일: %d개\n", s.synced);
        if (s.upWait) c += Fmt(L"PC → NAS 올릴 것: %d개\n", s.upWait);
        if (s.downWait) c += Fmt(L"NAS → PC 받을 것: %d개\n", s.downWait);
        if (s.delWait) c += Fmt(L"지운 것을 반대쪽에도 지울 것: %d개\n", s.delWait);
        if (s.both) c += Fmt(L"양쪽에서 바뀌어 둘 다 남길 것: %d개\n", s.both);
        if (s.checkWait) c += Fmt(L"내용 비교할 것: %d개\n", s.checkWait);
        if (s.transferring) c += Fmt(L"지금 옮기는 중: %d개\n", s.transferring);
        if (s.locked) c += Fmt(L"다른 프로그램이 쓰는 중이라 기다림: %d개\n", s.locked);
        if (s.failing) c += Fmt(L"옮기기 실패(다시 시도 중): %d개\n", s.failing);
        if (s.excludedN) c += Fmt(L"제외된 항목: %d개\n", s.excludedN);
        if (s.files) c += Fmt(L"\nPC에 받아 둔 용량: %s · 온라인 전용(내용은 NAS에만): %d개\n", SizeW(s.localBytes).c_str(), s.onlineOnly);
    }
    c += std::wstring(L"\n연결: ") + (online ? L"NAS에 연결됨" : L"연결 안 됨 (다시 연결을 시도하는 중)");
    if (paused) c += L" · 일시 중지 (" + ClockAfter(e->PausedLeftMs()) + L"까지)\n멈춘 동안 PC나 NAS에서 바뀐 것은 아직 이 숫자에 들어 있지 않습니다. 다시 시작하면 확인해 맞춥니다.";
    std::wstring more;
    for (auto& [p, why] : s.issues) more += W(p) + L"\n    " + W(why) + L"\n";
    TASKDIALOG_BUTTON btns[] = {{100, L"지금 동기화"}};
    TASKDIALOGCONFIG tc{sizeof tc};
    tc.dwFlags = TDF_ALLOW_DIALOG_CANCELLATION | TDF_SIZE_TO_CONTENT;
    tc.dwCommonButtons = TDCBF_CLOSE_BUTTON;
    tc.pszWindowTitle = L"Mydrive — 동기화 상태";
    tc.hMainIcon = LoadIconW(g_inst, MAKEINTRESOURCEW(1)); tc.dwFlags |= TDF_USE_HICON_MAIN;
    tc.pszMainInstruction = head.c_str();
    tc.pszContent = c.c_str();
    if (!more.empty()) { tc.pszExpandedInformation = more.c_str(); tc.pszExpandedControlText = L"맞추지 못한 항목 접기"; tc.pszCollapsedControlText = L"맞추지 못한 항목 보기"; }
    if (s.found && !s.excluded) { tc.pButtons = btns; tc.cButtons = 1; }
    tc.nDefaultButton = IDCLOSE;
    int btn = 0;
    TaskDialogIndirect(&tc, &btn, nullptr, nullptr);
    if (btn == 100) { if (e->Paused()) e->Pause(0); e->Kick(); }
}

static void ShellCmd(const std::wstring& cmd, const std::wstring& path) {
    ConnPath cp = FindConn(path);
    if (cmd == L"pause30" || cmd == L"pause60" || cmd == L"pause180") {
        int m = cmd == L"pause30" ? 30 : cmd == L"pause60" ? 60 : 180;
        PauseAll(m);
        TrayBalloon(L"동기화 일시 중지", MinText(m) + L" 동안 멈춥니다 (" + ClockAfter((uint64_t)m * 60000) + L"까지). 다시 시작하려면 오른쪽 클릭 → Mydrive → '동기화 다시 시작'.", NIIF_INFO);
        return;
    }
    if (cmd == L"resume") {
        bool was = AnyPaused();
        PauseAll(0);
        for (auto& e : g_engines) e->Kick();
        TrayBalloon(L"동기화 다시 시작", was ? L"동기화를 다시 시작했습니다." : L"멈춰 있지 않았습니다. 지금 한 번 맞춰 봅니다.", NIIF_INFO);
        return;
    }
    if (cp.conn < 0) { TrayBalloon(L"Mydrive", L"Mydrive로 맞추는 폴더 밖의 항목입니다:\n" + path, NIIF_WARNING); return; }
    auto e = EngineOf(cp.conn);
    if (cmd == L"sync-now") {
        if (!e) return;
        bool was = AnyPaused();
        if (was) PauseAll(0);
        e->Kick();
        TrayBalloon(L"지금 동기화", ShowRel(cp.conn, cp.rel) + L"\n" + (was ? L"일시 중지를 풀고 NAS와 맞춥니다." : L"NAS와 바로 맞춥니다."), NIIF_INFO);
        return;
    }
    if (cmd == L"keep" || cmd == L"free") {   // 여러 개를 고르면 하나씩 불림 → 실패할 때만 알림
        bool keep = cmd == L"keep";
        if (!cloud::SetKeep(path, keep))
            TrayBalloon(keep ? L"항상 이 장치에 유지" : L"공간 확보", ShowRel(cp.conn, cp.rel) + L"\n바꾸지 못했습니다 (파일이 열려 있거나 아직 맞추는 중).", NIIF_WARNING);
        if (e) e->Kick();
        return;
    }
    if (cmd == L"toggle-skip") {
        auto& c = g_cfg.conns[cp.conn];
        if (cp.rel.empty()) { TrayBalloon(L"동기화에서 제외", L"연결 폴더 전체는 여기서 뺄 수 없습니다. 설정에서 연결을 끄거나 지우세요.", NIIF_WARNING); return; }
        auto it = std::find(c.skip.begin(), c.skip.end(), cp.rel);
        if (it != c.skip.end()) {
            c.skip.erase(it);
            SaveCfg(); StartEngines();
            TrayBalloon(L"동기화에 다시 포함", W(cp.rel) + L"\n다시 NAS와 맞춥니다.", NIIF_INFO);
            return;
        }
        for (auto& s : c.skip)
            if (cp.rel.size() > s.size() && cp.rel.compare(0, s.size(), s) == 0 && cp.rel[s.size()] == '/') {
                TrayBalloon(L"동기화에서 제외", L"위 폴더 '" + W(s) + L"'가 이미 제외되어 있습니다. 그 폴더에서 '다시 포함'을 고르세요.", NIIF_INFO);
                return;
            }
        // 이 안에 따로 제외해 둔 것은 하나로 합침
        c.skip.erase(std::remove_if(c.skip.begin(), c.skip.end(), [&](const std::string& s) {
            return s.size() > cp.rel.size() && s.compare(0, cp.rel.size(), cp.rel) == 0 && s[cp.rel.size()] == '/'; }), c.skip.end());
        c.skip.push_back(cp.rel);
        SaveCfg(); StartEngines();
        TrayBalloon(L"동기화에서 제외", W(cp.rel) + L"\nPC와 NAS의 내용은 그대로 두고, 이제 서로 맞추지 않습니다.", NIIF_INFO);
        return;
    }
    if (cmd == L"status") {
        if (!e) { TrayBalloon(L"Mydrive", L"이 연결은 지금 꺼져 있습니다.", NIIF_WARNING); return; }
        std::wstring where = ShowRel(cp.conn, cp.rel);
        std::thread(StatusDialog, e, where, cp.rel, path).detach();
        return;
    }
}

// ---------- 이전 버전 / 지운 파일 창 ----------
enum { IDT_LIST = 800, IDT_OPEN, IDT_RESTORE, IDT_SAVE, IDT_CLOSE, IDT_INFO, IDT_PATH };
#define WM_APP_TLOADED (WM_APP + 20)
#define WM_APP_TDONE (WM_APP + 21)
struct ToolItem { std::string p, id; int64_t replaced = 0, s = 0, m = 0; int n = 0; };
static struct {
    bool deleted = false;          // false: 한 파일의 이전 버전 / true: 폴더의 지운 파일
    int conn = -1; std::string rel; std::wstring abs;
    HWND win = nullptr, list = nullptr, info = nullptr;
    std::vector<ToolItem> items;
    bool busy = false;
} T;

static void ToolLoad() {
    T.busy = true;
    SetWindowTextW(T.info, L"NAS에서 목록을 가져오는 중…");
    HWND h = T.win; std::wstring server = ActiveServer(); std::string token = g_cfg.token, root = g_cfg.conns[T.conn].name, rel = T.rel; bool del = T.deleted;
    std::thread([=] {
        Http http(server, token);
        Resp r = del ? http.Req(L"GET", "/v1/deleted", {{"root", root}}, nullptr, 120) : http.Req(L"GET", "/v1/versions", {{"root", root}, {"path", rel}}, nullptr, 60);
        auto* s = new std::string(r.ok() ? r.body : "\x01" + (r.error.empty() ? std::to_string(r.status) + " " + r.body : r.error));
        if (!PostMessageW(h, WM_APP_TLOADED, 0, (LPARAM)s)) delete s;
    }).detach();
}
static void ToolFill(const std::string& body) {
    T.items.clear();
    ListView_DeleteAllItems(T.list);
    json j = json::parse(body, nullptr, false);
    if (j.is_array()) for (auto& e : j) {
        ToolItem it;
        if (T.deleted) {
            it.p = e.value("p", std::string());
            if (!T.rel.empty() && !(it.p.size() > T.rel.size() && it.p.compare(0, T.rel.size(), T.rel) == 0 && it.p[T.rel.size()] == '/')) continue;   // 이 폴더 안만
            auto& v = e["v"]; it.id = v.value("id", std::string()); it.replaced = v.value("replaced", (int64_t)0); it.s = v.value("s", (int64_t)0); it.m = v.value("m", (int64_t)0);
            it.n = e.value("n", 0);
        } else {
            it.p = T.rel; it.id = e.value("id", std::string()); it.replaced = e.value("replaced", (int64_t)0); it.s = e.value("s", (int64_t)0); it.m = e.value("m", (int64_t)0);
        }
        T.items.push_back(it);
    }
    for (int i = 0; i < (int)T.items.size(); i++) {
        auto& it = T.items[i];
        std::wstring c0 = T.deleted ? W(it.p.substr(T.rel.empty() ? 0 : T.rel.size() + 1)) : NsText(it.replaced) + L" 전의 판";
        LVITEMW li{}; li.mask = LVIF_TEXT; li.iItem = i; li.pszText = (LPWSTR)c0.c_str();
        ListView_InsertItem(T.list, &li);
        std::wstring c1 = T.deleted ? NsText(it.replaced) : SizeW(it.s), c2 = T.deleted ? SizeW(it.s) : NsText(it.m);
        ListView_SetItemText(T.list, i, 1, (LPWSTR)c1.c_str());
        ListView_SetItemText(T.list, i, 2, (LPWSTR)c2.c_str());
        if (T.deleted) { std::wstring c3 = Fmt(L"%d개", it.n); ListView_SetItemText(T.list, i, 3, (LPWSTR)c3.c_str()); }
    }
    if (!T.items.empty()) ListView_SetItemState(T.list, 0, LVIS_SELECTED | LVIS_FOCUSED, LVIS_SELECTED | LVIS_FOCUSED);
    SetWindowTextW(T.info, T.items.empty()
        ? (T.deleted ? L"이 폴더에는 되살릴 수 있는 지운 파일이 없습니다." : L"이 파일은 아직 이전 버전이 없습니다. 고치거나 덮어쓰면 그 전 판이 NAS에 남습니다.")
        : (T.deleted ? Fmt(L"지운 파일 %d개 · 고른 파일을 [되살리기]하면 원래 자리에 다시 생깁니다.", (int)T.items.size()).c_str()
                     : Fmt(L"이전 버전 %d개 · 가장 최근 것이 위에 있습니다.", (int)T.items.size()).c_str()));
    T.busy = false;
}
static std::vector<int> ToolSel() {
    std::vector<int> v;
    for (int i = -1; (i = ListView_GetNextItem(T.list, i, LVNI_SELECTED)) >= 0;) v.push_back(i);
    return v;
}
// 한 판을 받아 파일로 (숨김 속성 없이)
static bool ToolDownload(const ToolItem& it, const std::wstring& out, std::string& err) {
    Http http(ActiveServer(), g_cfg.token);
    Resp r = http.Download("/v1/version", {{"root", g_cfg.conns[T.conn].name}, {"path", it.p}, {"id", it.id}}, out);
    if (!r.ok()) { err = r.error.empty() ? std::to_string(r.status) : r.error; return false; }
    SetFileAttributesW(out.c_str(), FILE_ATTRIBUTE_NORMAL);
    if (it.m) { HANDLE f = CreateFileW(out.c_str(), FILE_WRITE_ATTRIBUTES, 0, nullptr, OPEN_EXISTING, 0, nullptr); if (f != INVALID_HANDLE_VALUE) { FILETIME ft = NsToFt(it.m); SetFileTime(f, nullptr, nullptr, &ft); CloseHandle(f); } }
    return true;
}
static std::wstring VersionName(const ToolItem& it) {
    std::wstring leaf = W(Leaf(it.p)); size_t dot = leaf.rfind(L'.');
    std::wstring t = NsText(it.replaced); for (auto& ch : t) if (ch == L':') ch = L'.';
    std::wstring tag = L" (" + t + L" 판)";
    return dot == std::wstring::npos || dot == 0 ? leaf + tag : leaf.substr(0, dot) + tag + leaf.substr(dot);
}
static void ToolOpen() {
    auto sel = ToolSel(); if (sel.empty() || T.busy) return;
    ToolItem it = T.items[sel[0]];
    std::wstring dir = KnownFolder(FOLDERID_LocalAppData) + L"\\Temp\\Mydrive 이전 버전";
    SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr);
    std::wstring out = dir + L"\\" + VersionName(it);
    T.busy = true; SetWindowTextW(T.info, L"그 판을 받는 중…");
    HWND h = T.win;
    std::thread([=] {
        std::string err; bool ok = ToolDownload(it, out, err);
        if (ok) ShellExecuteW(nullptr, L"open", out.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
        auto* s = new std::wstring(ok ? L"열었습니다 (읽기용 사본: " + out + L")" : L"받지 못했습니다: " + W(err));
        if (!PostMessageW(h, WM_APP_TDONE, 0, (LPARAM)s)) delete s;
    }).detach();
}
static void ToolSaveAs() {
    auto sel = ToolSel(); if (sel.empty() || T.busy) return;
    ToolItem it = T.items[sel[0]];
    wchar_t file[MAX_PATH]; wcsncpy_s(file, VersionName(it).c_str(), _TRUNCATE);
    OPENFILENAMEW of{sizeof of}; of.hwndOwner = T.win; of.lpstrFile = file; of.nMaxFile = MAX_PATH;
    of.lpstrFilter = L"모든 파일\0*.*\0"; of.Flags = OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST;
    if (!GetSaveFileNameW(&of)) return;
    std::wstring out = file;
    T.busy = true; SetWindowTextW(T.info, L"저장하는 중…");
    HWND h = T.win;
    std::thread([=] {
        std::string err; bool ok = ToolDownload(it, out, err);
        auto* s = new std::wstring(ok ? L"저장했습니다: " + out : L"저장하지 못했습니다: " + W(err));
        if (!PostMessageW(h, WM_APP_TDONE, 0, (LPARAM)s)) delete s;
    }).detach();
}
static void ToolRestore() {
    auto sel = ToolSel(); if (sel.empty() || T.busy) return;
    std::wstring q = T.deleted
        ? Fmt(L"고른 파일 %d개를 원래 자리에 되살릴까요?\n\nNAS에 다시 만들고, 곧 이 PC에도 나타납니다.", (int)sel.size())
        : L"이 파일을 고른 판(" + NsText(T.items[sel[0]].replaced) + L" 전)으로 되돌릴까요?\n\n지금 판은 지우지 않고 이전 버전으로 남겨 두므로, 다시 되돌릴 수 있습니다.";
    if (MessageBoxW(T.win, q.c_str(), L"Mydrive", MB_OKCANCEL | MB_ICONQUESTION) != IDOK) return;
    std::vector<ToolItem> its; for (int i : sel) its.push_back(T.items[i]);
    T.busy = true; SetWindowTextW(T.info, L"NAS에서 되돌리는 중…");
    HWND h = T.win; std::string root = g_cfg.conns[T.conn].name; bool del = T.deleted;
    std::thread([=] {
        Http http(ActiveServer(), g_cfg.token);
        int ok = 0; std::string err;
        for (auto& it : its) {
            Resp r = http.Req(L"POST", "/v1/restore", {{"root", root}, {"path", it.p}, {"id", it.id}}, nullptr, 300);
            if (r.ok()) ok++; else err = r.error.empty() ? std::to_string(r.status) + " " + r.body : r.error;
        }
        std::wstring msg = ok == (int)its.size()
            ? (del ? Fmt(L"%d개를 되살렸습니다. 곧 이 PC에도 나타납니다.", ok) : std::wstring(L"되돌렸습니다. 곧 이 PC의 파일도 그 판으로 바뀝니다."))
            : Fmt(L"%d개 중 %d개만 되살렸습니다: ", (int)its.size(), ok) + W(err);
        auto* s = new std::wstring(msg);
        if (!PostMessageW(h, WM_APP_TDONE, 1, (LPARAM)s)) delete s;
    }).detach();
}

static LRESULT CALLBACK ToolProc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    LRESULT dr;
    if (DarkMsg(h, msg, wp, lp, dr)) return dr;
    switch (msg) {
    case WM_CREATE: {
        T.win = h;
        std::wstring name = T.rel.empty() ? g_cfg.conns[T.conn].local : W(Leaf(T.rel));
        HWND l1 = Ctl(h, L"STATIC", ((T.deleted ? L"지운 파일 되살리기 — " : L"이전 버전 — ") + name).c_str(), SS_LEFT | SS_NOPREFIX | SS_ENDELLIPSIS, 0);
        SendMessageW(l1, WM_SETFONT, (WPARAM)g_fontBig, TRUE);
        MoveWindow(l1, S(16), S(12), S(600), S(26), TRUE);
        HWND l2 = Ctl(h, L"STATIC", T.abs.c_str(), SS_LEFT | SS_NOPREFIX | SS_PATHELLIPSIS, IDT_PATH);
        SendMessageW(l2, WM_SETFONT, (WPARAM)g_fontSmall, TRUE);
        MoveWindow(l2, S(16), S(40), S(600), S(18), TRUE);
        T.list = Ctl(h, WC_LISTVIEWW, L"", LVS_REPORT | LVS_SHOWSELALWAYS | WS_TABSTOP | (T.deleted ? 0 : LVS_SINGLESEL), IDT_LIST, WS_EX_CLIENTEDGE);
        ListView_SetExtendedListViewStyle(T.list, LVS_EX_FULLROWSELECT | LVS_EX_DOUBLEBUFFER);
        if (T.deleted) { AddCol(T.list, 0, L"파일", 290); AddCol(T.list, 1, L"지운 시각", 140); AddCol(T.list, 2, L"크기", 80); AddCol(T.list, 3, L"남은 판", 70); }
        else { AddCol(T.list, 0, L"판 (이 시각에 바뀌기 전)", 260); AddCol(T.list, 1, L"크기", 90); AddCol(T.list, 2, L"고친 시각", 160); }
        MoveWindow(T.list, S(16), S(66), S(600), S(330), TRUE);
        T.info = Ctl(h, L"STATIC", L"", SS_LEFT | SS_NOPREFIX, IDT_INFO);
        SendMessageW(T.info, WM_SETFONT, (WPARAM)g_fontSmall, TRUE);
        MoveWindow(T.info, S(16), S(402), S(600), S(34), TRUE);
        struct B { int id; const wchar_t* t; const wchar_t* tip; } bs[] = {
            {IDT_OPEN, L"열어 보기", L"그 판을 임시 폴더에 받아 엽니다 (지금 파일은 그대로)"},
            {IDT_SAVE, L"다른 이름으로 저장…", L"그 판을 원하는 곳에 따로 저장합니다"},
            {IDT_RESTORE, L"이 판으로 되돌리기", L"NAS의 파일을 그 판으로 바꿉니다. 지금 판은 이전 버전으로 남습니다"},
            {IDT_CLOSE, L"닫기", L""},
        };
        HWND tip = MakeTip(h);
        int x = S(16);
        for (auto& b : bs) {
            if (T.deleted && (b.id == IDT_OPEN || b.id == IDT_SAVE)) continue;
            const wchar_t* t = (T.deleted && b.id == IDT_RESTORE) ? L"되살리기" : b.t;
            int w = b.id == IDT_SAVE ? S(150) : b.id == IDT_RESTORE ? S(150) : S(100);
            if (b.id == IDT_CLOSE) x = S(616) - w;
            HWND bt = Ctl(h, L"BUTTON", t, (b.id == IDT_RESTORE ? BS_DEFPUSHBUTTON : BS_PUSHBUTTON) | WS_TABSTOP, b.id);
            MoveWindow(bt, x, S(442), w, S(30), TRUE);
            if (*b.tip) AddTip(tip, bt, (T.deleted && b.id == IDT_RESTORE) ? L"고른 파일을 원래 자리에 다시 만듭니다 (가장 최근 판으로)" : b.tip);
            x += w + S(8);
        }
        DarkWindow(h); DarkenChildren(h);
        ToolLoad();
        return 0;
    }
    case WM_APP_TLOADED: {
        auto* s = (std::string*)lp;
        if (!s->empty() && (*s)[0] == '\x01') { SetWindowTextW(T.info, (L"목록을 가져오지 못했습니다: " + W(s->substr(1))).c_str()); T.busy = false; }
        else ToolFill(*s);
        delete s;
        return 0;
    }
    case WM_APP_TDONE: {
        auto* s = (std::wstring*)lp;
        T.busy = false;
        if (wp == 1) ToolLoad();   // 되돌린 뒤 목록 새로
        SetWindowTextW(T.info, s->c_str());
        delete s;
        return 0;
    }
    case WM_CTLCOLORSTATIC: {
        HDC dc = (HDC)wp; SetBkColor(dc, kBg);
        SetTextColor(dc, ((HWND)lp == T.info || GetDlgCtrlID((HWND)lp) == IDT_PATH) ? kMuted : kText);
        return (LRESULT)g_brDark;
    }
    case WM_NOTIFY: {
        auto* nm = (NMHDR*)lp;
        if (nm->hwndFrom == T.list && nm->code == NM_DBLCLK) { if (T.deleted) ToolRestore(); else ToolOpen(); }
        return 0;
    }
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case IDT_OPEN: ToolOpen(); break;
        case IDT_SAVE: ToolSaveAs(); break;
        case IDT_RESTORE: case IDOK: ToolRestore(); break;
        case IDT_CLOSE: case IDCANCEL: DestroyWindow(h); break;
        }
        return 0;
    case WM_CLOSE: DestroyWindow(h); return 0;
    case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(h, msg, wp, lp);
}

// Mydrive.exe --versions/--deleted "경로" : 이 창만 띄우고 닫으면 끝
static int RunTool(bool deleted, std::wstring path) {
    if (path.compare(0, 4, L"\\\\?\\") == 0) path = path.substr(4);
    DWORD a = GetFileAttributesW(path.c_str());
    bool isDir = a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY);
    if (!deleted && isDir) deleted = true;                     // 폴더의 '이전 버전' = 그 폴더에서 지운 파일
    if (deleted && !isDir) path = path.substr(0, path.find_last_of(L'\\'));   // 파일에서 고르면 그 파일이 있는 폴더
    ConnPath cp = FindConn(path);
    if (cp.conn < 0) { MessageBoxW(nullptr, L"Mydrive로 맞추는 폴더 안이 아닙니다.", L"Mydrive", MB_ICONINFORMATION); return 1; }
    T.deleted = deleted; T.conn = cp.conn; T.rel = cp.rel; T.abs = path;
    WNDCLASSEXW wc{sizeof wc};
    wc.lpfnWndProc = ToolProc; wc.hInstance = g_inst; wc.lpszClassName = L"MydriveTool";
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW); wc.hbrBackground = g_brDark;
    wc.hIcon = LoadIconW(g_inst, MAKEINTRESOURCEW(1));
    RegisterClassExW(&wc);
    RECT rc{0, 0, S(632), S(486)};
    AdjustWindowRectEx(&rc, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, FALSE, 0);
    POINT pt; GetCursorPos(&pt);
    HMONITOR mon = MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST); MONITORINFO mi{sizeof mi}; GetMonitorInfoW(mon, &mi);
    int w = rc.right - rc.left, hh = rc.bottom - rc.top;
    int x = mi.rcWork.left + (mi.rcWork.right - mi.rcWork.left - w) / 2, y = mi.rcWork.top + (mi.rcWork.bottom - mi.rcWork.top - hh) / 2;
    HWND h = CreateWindowExW(0, L"MydriveTool", deleted ? L"Mydrive — 지운 파일 되살리기" : L"Mydrive — 이전 버전",
                             WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, x, y, w, hh, nullptr, nullptr, g_inst, nullptr);
    ShowWindow(h, SW_SHOW); SetForegroundWindow(h);
    MSG m;
    while (GetMessageW(&m, nullptr, 0, 0) > 0) {
        if (IsDialogMessageW(h, &m)) continue;
        TranslateMessage(&m); DispatchMessageW(&m);
    }
    return 0;
}
