// setup.cpp — Mydrive 설치 파일 (관리자 권한 없이 사용자 폴더에 설치)
//   Mydrive 설치.exe          화면을 보며 설치
//   Mydrive 설치.exe /quiet   묻지 않고 설치 (/norun: 설치 후 실행 안 함, /tray: 트레이로만 다시 시작)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <string>
#include "../third_party/json.hpp"
#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "advapi32.lib")

#include "version.h"
static const wchar_t* VERSION = MYDRIVE_VERSION_W;
#define WM_APP_QUIT (WM_APP + 4)

static std::wstring Known(REFKNOWNFOLDERID id) {
    PWSTR p = nullptr; std::wstring s;
    if (SUCCEEDED(SHGetKnownFolderPath(id, 0, nullptr, &p))) s = p;
    CoTaskMemFree(p); return s;
}
static void SetStr(HKEY k, const wchar_t* n, const std::wstring& v) { RegSetValueExW(k, n, 0, REG_SZ, (const BYTE*)v.c_str(), (DWORD)((v.size() + 1) * 2)); }
static void SetDw(HKEY k, const wchar_t* n, DWORD v) { RegSetValueExW(k, n, 0, REG_DWORD, (const BYTE*)&v, 4); }

// 떠 있는 Mydrive를 끄고 끝날 때까지 기다림
static void CloseRunning() {
    for (int tries = 0; tries < 3; tries++) {
        HWND w = FindWindowW(L"MydriveMain", nullptr);
        if (!w) return;
        DWORD pid = 0; GetWindowThreadProcessId(w, &pid);
        HANDLE ph = OpenProcess(SYNCHRONIZE, FALSE, pid);
        PostMessageW(w, WM_APP_QUIT, 0, 0);
        if (ph) { WaitForSingleObject(ph, 20000); CloseHandle(ph); }
    }
}

static bool StartupWanted() {
    std::wstring cfg = Known(FOLDERID_LocalAppData) + L"\\Mydrive\\config.json";
    FILE* f = _wfopen(cfg.c_str(), L"rb");
    if (!f) return true;
    std::string s; char b[4096]; size_t n;
    while ((n = fread(b, 1, sizeof b, f)) > 0) s.append(b, n);
    fclose(f);
    auto j = nlohmann::json::parse(s, nullptr, false);
    return j.is_discarded() ? true : j.value("startWithWindows", true);
}

static std::wstring Install(std::wstring& exeOut) {
    std::wstring dir = Known(FOLDERID_LocalAppData) + L"\\Programs\\Mydrive";
    SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr);
    std::wstring exe = dir + L"\\Mydrive.exe", tmp = exe + L".new";
    HRSRC r = FindResourceW(nullptr, MAKEINTRESOURCEW(10), RT_RCDATA);
    if (!r) return L"설치 파일 안에 프로그램이 없습니다.";
    DWORD size = SizeofResource(nullptr, r);
    const void* data = LockResource(LoadResource(nullptr, r));
    HANDLE h = CreateFileW(tmp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return L"설치 폴더에 쓸 수 없습니다: " + dir;
    DWORD w = 0; BOOL ok = WriteFile(h, data, size, &w, nullptr) && w == size;
    FlushFileBuffers(h); CloseHandle(h);
    if (!ok) { DeleteFileW(tmp.c_str()); return L"프로그램 파일을 쓰지 못했습니다."; }
    CloseRunning();
    bool moved = false;
    for (int i = 0; i < 20 && !(moved = MoveFileExW(tmp.c_str(), exe.c_str(), MOVEFILE_REPLACE_EXISTING)); i++) Sleep(500);
    if (!moved) { DeleteFileW(tmp.c_str()); return L"기존 Mydrive.exe를 바꾸지 못했습니다. Mydrive를 끝낸 뒤 다시 설치하세요."; }

    // 시작 메뉴 바로 가기
    IShellLinkW* sl = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&sl)))) {
        sl->SetPath(exe.c_str()); sl->SetWorkingDirectory(dir.c_str());
        sl->SetDescription(L"NAS와 PC 폴더 동기화"); sl->SetIconLocation(exe.c_str(), 0);
        IPersistFile* pf = nullptr;
        if (SUCCEEDED(sl->QueryInterface(IID_PPV_ARGS(&pf)))) {
            pf->Save((Known(FOLDERID_Programs) + L"\\Mydrive.lnk").c_str(), TRUE); pf->Release();
        }
        sl->Release();
    }
    // 설정 > 앱 목록 (제거)
    HKEY k;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Mydrive", 0, nullptr, 0,
                        KEY_SET_VALUE, nullptr, &k, nullptr) == ERROR_SUCCESS) {
        SetStr(k, L"DisplayName", L"Mydrive");
        SetStr(k, L"DisplayVersion", VERSION);
        SetStr(k, L"Publisher", L"Mydrive");
        SetStr(k, L"DisplayIcon", exe);
        SetStr(k, L"InstallLocation", dir);
        SetStr(k, L"UninstallString", L"\"" + exe + L"\" --uninstall");
        SetStr(k, L"QuietUninstallString", L"\"" + exe + L"\" --uninstall --quiet");
        SetDw(k, L"EstimatedSize", size / 1024);
        SetDw(k, L"NoModify", 1); SetDw(k, L"NoRepair", 1);
        RegCloseKey(k);
    }
    // 로그인할 때 자동 실행 (설정에서 끈 적이 있으면 그대로 둠)
    if (StartupWanted() && RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Run", 0, KEY_SET_VALUE, &k) == ERROR_SUCCESS) {
        SetStr(k, L"Mydrive", L"\"" + exe + L"\" --tray");
        RegCloseKey(k);
    }
    exeOut = exe;
    return L"";
}

// exe를 실행하고 끝날 때까지 기다려 종료 코드를 돌려줌 (verb "runas" 면 관리자 승인 창, 거절하면 -1)
static int RunWait(const std::wstring& exe, const wchar_t* args, const wchar_t* verb) {
    SHELLEXECUTEINFOW se{sizeof se};
    se.fMask = SEE_MASK_NOCLOSEPROCESS; se.lpVerb = verb; se.lpFile = exe.c_str(); se.lpParameters = args; se.nShow = SW_HIDE;
    if (!ShellExecuteExW(&se) || !se.hProcess) return -1;
    WaitForSingleObject(se.hProcess, 60000);
    DWORD code = 1; GetExitCodeProcess(se.hProcess, &code); CloseHandle(se.hProcess);
    return (int)code;
}

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR cmdLine, int) {
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    std::wstring cl = cmdLine ? cmdLine : L"";
    bool quiet = cl.find(L"/quiet") != std::wstring::npos || cl.find(L"--quiet") != std::wstring::npos;
    bool norun = cl.find(L"/norun") != std::wstring::npos;
    bool run = !norun;
    if (!quiet) {
        std::wstring content = std::wstring(L"NAS와 이 PC의 폴더를 같은 내용으로 맞춰 주는 프로그램입니다.\n\n") +
            L"• 설치 위치: " + Known(FOLDERID_LocalAppData) + L"\\Programs\\Mydrive\n" +
            L"• 이 사용자 계정에만 설치됩니다.\n" +
            L"• 탐색기 오른쪽 클릭 메뉴(이전 버전 보기 등)를 넣을 때 한 번 관리자 승인을 묻습니다. 거절해도 동기화는 그대로 됩니다.\n" +
            L"• 이미 설치되어 있으면 새 버전으로 바꾸며, 설정과 동기화 기록은 그대로 둡니다.";
        TASKDIALOG_BUTTON btns[] = {{IDOK, L"설치"}};
        TASKDIALOGCONFIG tc{sizeof tc};
        tc.dwFlags = TDF_ALLOW_DIALOG_CANCELLATION | TDF_POSITION_RELATIVE_TO_WINDOW;
        tc.dwCommonButtons = TDCBF_CANCEL_BUTTON;
        tc.pszWindowTitle = L"Mydrive 설치";
        tc.cxWidth = 300;   // 대화 상자 단위
        tc.hMainIcon = LoadIconW(GetModuleHandleW(nullptr), MAKEINTRESOURCEW(1));
        tc.dwFlags |= TDF_USE_HICON_MAIN;
        std::wstring title = std::wstring(L"Mydrive ") + VERSION + L" 설치";
        tc.pszMainInstruction = title.c_str();
        tc.pszContent = content.c_str();
        tc.pButtons = btns; tc.cButtons = 1; tc.nDefaultButton = IDOK;
        tc.pszVerificationText = L"설치가 끝나면 Mydrive 실행";
        int btn = 0; BOOL verify = TRUE;
        tc.dwFlags |= TDF_VERIFICATION_FLAG_CHECKED;
        if (FAILED(TaskDialogIndirect(&tc, &btn, nullptr, &verify)) || btn != IDOK) return 1;
        run = verify != FALSE;
    }
    std::wstring exe;
    std::wstring err = Install(exe);
    if (!err.empty()) {
        if (!quiet) MessageBoxW(nullptr, err.c_str(), L"Mydrive 설치", MB_ICONERROR);
        return 2;
    }
    // 탐색기 오른쪽 클릭 메뉴: Windows 11은 PC 전체(HKLM) 등록만 보여 주므로 관리자 승인을 받아 넣음.
    // 이미 이 실행 파일로 들어가 있으면 (업데이트) 다시 묻지 않음.
    if (RunWait(exe, L"--shell-current", L"open") != 0) RunWait(exe, L"--register-shell", L"runas");
    // /tray: 앱이 스스로 업데이트할 때 — 끝나면 창 없이 트레이로 다시 시작
    bool tray = cl.find(L"/tray") != std::wstring::npos;
    if (run) ShellExecuteW(nullptr, L"open", exe.c_str(), tray ? L"--tray" : nullptr, nullptr, SW_SHOWNORMAL);
    if (!quiet && !run)
        MessageBoxW(nullptr, L"설치했습니다. 시작 메뉴에서 Mydrive를 실행하세요.", L"Mydrive 설치", MB_ICONINFORMATION);
    return 0;
}
