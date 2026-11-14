// cloud.cpp — Windows Cloud Files API 로 탐색기 연동
#include "cloud.h"
#include <winternl.h>   // NTSTATUS
#include <cfapi.h>
#include <sddl.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <shlwapi.h>
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "shlwapi.lib")
#pragma comment(lib, "ole32.lib")
#include <thread>
#include <mutex>
#include <map>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.Provider.h>
#include <winrt/Windows.Security.Cryptography.h>
#pragma comment(lib, "cldapi.lib")
#pragma comment(lib, "windowsapp.lib")
#pragma comment(lib, "advapi32.lib")

using namespace winrt::Windows::Storage::Provider;

namespace md::cloud {

static std::wstring UserSid() {
    static std::wstring sid;
    if (!sid.empty()) return sid;
    HANDLE tok; if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &tok)) return L"";
    DWORD n = 0; GetTokenInformation(tok, TokenUser, nullptr, 0, &n);
    std::vector<BYTE> b(n);
    if (GetTokenInformation(tok, TokenUser, b.data(), n, &n)) {
        LPWSTR s = nullptr;
        if (ConvertSidToStringSidW(((TOKEN_USER*)b.data())->User.Sid, &s)) { sid = s; LocalFree(s); }
    }
    CloseHandle(tok);
    return sid;
}
static std::wstring FullId(const std::wstring& id) { return L"Mydrive!" + UserSid() + L"!" + id; }
static std::wstring Plain(std::wstring p) { if (p.compare(0, 4, L"\\\\?\\") == 0) p = p.substr(4); return p; }

// WinRT 는 MTA 스레드에서 (UI 스레드는 STA 라 .get() 으로 기다리면 안 됨)
template <class F> static auto OnMta(F f) {
    decltype(f()) r{};
    std::thread([&] { winrt::init_apartment(winrt::apartment_type::multi_threaded); r = f(); winrt::uninit_apartment(); }).join();
    return r;
}

// ---------- 탐색기 왼쪽 탐색 창 항목 (설치형 앱은 직접 등록해야 보임: 원드라이브·시놀로지와 같은 방식) ----------
static std::wstring NavGuid(const std::wstring& id) {
    // 이름에서 늘 같은 GUID 를 만듦 (연결마다 하나)
    std::hash<std::wstring> h;
    uint64_t a = h(L"Mydrive-nav-a!" + id), b = h(L"Mydrive-nav-b!" + id);
    wchar_t g[40];
    swprintf(g, 40, L"{%08X-%04X-%04X-%04X-%012llX}", (unsigned)(a >> 32), (unsigned)((a >> 16) & 0xFFFF), (unsigned)((a & 0x0FFF) | 0x4000),
             (unsigned)(((b >> 48) & 0x3FFF) | 0x8000), (unsigned long long)(b & 0xFFFFFFFFFFFFULL));
    return g;
}
static void RegStr(const std::wstring& key, const wchar_t* name, const std::wstring& v, DWORD type = REG_SZ) {
    RegSetKeyValueW(HKEY_CURRENT_USER, key.c_str(), name, type, v.c_str(), (DWORD)((v.size() + 1) * sizeof(wchar_t)));
}
static void RegDw(const std::wstring& key, const wchar_t* name, DWORD v) { RegSetKeyValueW(HKEY_CURRENT_USER, key.c_str(), name, REG_DWORD, &v, sizeof v); }
static void NavAdd(const std::wstring& id, const std::wstring& path, const std::wstring& display, const std::wstring& icon) {
    std::wstring g = NavGuid(id), k = L"Software\\Classes\\CLSID\\" + g;
    RegStr(k, nullptr, display);
    RegDw(k, L"System.IsPinnedToNameSpaceTree", 1);
    RegDw(k, L"SortOrderIndex", 0x42);
    RegStr(k + L"\\DefaultIcon", nullptr, icon);
    RegStr(k + L"\\InProcServer32", nullptr, L"%SystemRoot%\\system32\\shell32.dll", REG_EXPAND_SZ);
    RegStr(k + L"\\Instance", L"CLSID", L"{0E5AAE11-A475-4c5b-AB00-C66DE400274E}");   // 보통 파일 폴더처럼 보여 줌
    RegDw(k + L"\\Instance\\InitPropertyBag", L"Attributes", 0x11);
    RegStr(k + L"\\Instance\\InitPropertyBag", L"TargetFolderPath", path);
    RegDw(k + L"\\ShellFolder", L"FolderValueFlags", 0x28);
    RegDw(k + L"\\ShellFolder", L"Attributes", 0xF080004D);
    RegStr(L"Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Desktop\\NameSpace\\" + g, nullptr, display);
    RegDw(L"Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\HideDesktopIcons\\NewStartPanel", g.c_str(), 1);   // 바탕 화면에는 아이콘을 두지 않음
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
}
static void NavRemove(const std::wstring& id) {
    std::wstring g = NavGuid(id);
    RegDeleteTreeW(HKEY_CURRENT_USER, (L"Software\\Classes\\CLSID\\" + g).c_str());
    RegDeleteTreeW(HKEY_CURRENT_USER, (L"Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Desktop\\NameSpace\\" + g).c_str());
    RegDeleteKeyValueW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\HideDesktopIcons\\NewStartPanel", g.c_str());
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
}

bool Register(const std::wstring& id, const std::wstring& path, const std::wstring& display, const std::wstring& icon);
bool Unregister(const std::wstring& id);
// ---------- 탐색 창: Mydrive 하나에 모든 짝 ----------
static const wchar_t* kNs = L"Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Desktop\\NameSpace";
static std::wstring g_hub;   // 탐색 창 'Mydrive' 폴더 (작업마다 바로 가기)
static std::mutex g_hubMu;
static std::map<std::wstring, bool> g_rootState;   // 작업 → 마지막으로 알린 상태 (바로 가기를 새로 만들면 다시 칠함)
// 폴더에 '맞춰짐 / 맞추는 중' 표시 (자리표시가 아니면 자리표시로 바꾸면서)
static bool SetDirInSync(const std::wstring& dir, bool in) {
    HANDLE h = CreateFileW(dir.c_str(), FILE_READ_ATTRIBUTES | FILE_WRITE_ATTRIBUTES | FILE_WRITE_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    HRESULT hr = CfSetInSyncState(h, in ? CF_IN_SYNC_STATE_IN_SYNC : CF_IN_SYNC_STATE_NOT_IN_SYNC, CF_SET_IN_SYNC_FLAG_NONE, nullptr);
    if (FAILED(hr)) {
        char id[] = "-";
        hr = CfConvertToPlaceholder(h, id, 1, in ? CF_CONVERT_FLAG_MARK_IN_SYNC : CF_CONVERT_FLAG_NONE, nullptr, nullptr);
    }
    CloseHandle(h);
    return SUCCEEDED(hr);
}
static void HidePerRootNav() {   // 동기화 폴더마다 Windows 가 만든 항목 (이름이 "Mydrive!" 로 시작)
    HKEY k; if (RegOpenKeyExW(HKEY_CURRENT_USER, kNs, 0, KEY_READ, &k) != ERROR_SUCCESS) return;
    std::vector<std::wstring> del; wchar_t name[128];
    for (DWORD i = 0;; i++) {
        DWORD n = 128; if (RegEnumKeyExW(k, i, name, &n, nullptr, nullptr, nullptr, nullptr) != ERROR_SUCCESS) break;
        wchar_t def[512]; DWORD dn = sizeof def;
        if (RegGetValueW(k, name, nullptr, RRF_RT_REG_SZ, nullptr, def, &dn) == ERROR_SUCCESS && wcsncmp(def, L"Mydrive!", 8) == 0) del.push_back(name);
    }
    RegCloseKey(k);
    for (auto& g : del) {
        RegDeleteTreeW(HKEY_CURRENT_USER, (std::wstring(kNs) + L"\\" + g).c_str());
        DWORD zero = 0;
        RegSetKeyValueW(HKEY_CURRENT_USER, (L"Software\\Classes\\CLSID\\" + g).c_str(), L"System.IsPinnedToNameSpaceTree", REG_DWORD, &zero, sizeof zero);
    }
}
// 우리가 만든 폴더 바로 가기인지 (안에 desktop.ini 와 target.lnk 만 있음) — 다른 것은 절대 지우지 않게
static bool IsHubLink(const std::wstring& dir) {
    WIN32_FIND_DATAW fd; HANDLE h = FindFirstFileW((dir + L"\\*").c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return false;
    bool ok = true;
    do {
        std::wstring f = fd.cFileName;
        if (f == L"." || f == L"..") continue;
        if (_wcsicmp(f.c_str(), L"desktop.ini") && _wcsicmp(f.c_str(), L"target.lnk")) ok = false;
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return ok;
}
static void RemoveHubLink(const std::wstring& dir) {
    if (!IsHubLink(dir)) return;
    for (auto f : {L"\\desktop.ini", L"\\target.lnk"}) { SetFileAttributesW((dir + f).c_str(), FILE_ATTRIBUTE_NORMAL); DeleteFileW((dir + f).c_str()); }
    SetFileAttributesW(dir.c_str(), FILE_ATTRIBUTE_NORMAL);
    RemoveDirectoryW(dir.c_str());
}
// '폴더 바로 가기': 누르면 실제 동기화 폴더로 들어가므로 상태 아이콘·오른쪽 클릭 메뉴가 그대로 보임
static void MakeHubLink(const std::wstring& dir, const std::wstring& target) {
    DWORD a = GetFileAttributesW(dir.c_str());
    if (a != INVALID_FILE_ATTRIBUTES && !IsHubLink(dir)) return;   // 같은 이름의 다른 폴더가 있으면 건드리지 않음
    CreateDirectoryW(dir.c_str(), nullptr);
    IShellLinkW* sl = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&sl)))) {
        sl->SetPath(target.c_str());
        IPersistFile* pf = nullptr;
        if (SUCCEEDED(sl->QueryInterface(IID_PPV_ARGS(&pf)))) { pf->Save((dir + L"\\target.lnk").c_str(), TRUE); pf->Release(); }
        sl->Release();
    }
    std::wstring ini = dir + L"\\desktop.ini";
    SetFileAttributesW(ini.c_str(), FILE_ATTRIBUTE_NORMAL);
    if (FILE* f = _wfopen(ini.c_str(), L"wb")) { fputs("[.ShellClassInfo]\r\nCLSID2={0AFACED1-E828-11D1-9187-B532F1E9575D}\r\nFlags=2\r\n", f); fclose(f); }
    SetFileAttributesW(ini.c_str(), FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM);
    PathMakeSystemFolderW(dir.c_str());
}
void NavHub(const std::vector<std::pair<std::wstring, std::wstring>>& pairs, const std::wstring& hubDir, const std::wstring& icon) {
    CreateDirectoryW(hubDir.c_str(), nullptr);
    // 'Mydrive' 폴더도 동기화 폴더로 등록 → 안의 작업 항목마다 상태 칸(체크/맞추는 중)이 보임
    bool hubOk = !pairs.empty() && Register(L"_hub", hubDir, L"Mydrive", icon);
    HidePerRootNav();
    for (auto& pr : pairs) MakeHubLink(hubDir + L"\\" + pr.first, Plain(pr.second));
    {
        std::lock_guard<std::mutex> g(g_hubMu);
        g_hub = hubOk ? hubDir : L"";
        if (hubOk) for (auto& pr : pairs) { auto it = g_rootState.find(pr.first); SetDirInSync(hubDir + L"\\" + pr.first, it != g_rootState.end() && it->second); }
    }
    // 빠진 짝의 바로 가기 정리
    WIN32_FIND_DATAW fd; HANDLE h = FindFirstFileW((hubDir + L"\\*").c_str(), &fd);
    if (h != INVALID_HANDLE_VALUE) {
        std::vector<std::wstring> gone;
        do {
            std::wstring f = fd.cFileName;
            if (f == L"." || f == L".." || !(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
            bool keep = false; for (auto& pr : pairs) if (_wcsicmp(pr.first.c_str(), f.c_str()) == 0) keep = true;
            if (!keep) gone.push_back(hubDir + L"\\" + f);
        } while (FindNextFileW(h, &fd));
        FindClose(h);
        for (auto& g : gone) RemoveHubLink(g);
    }
    if (pairs.empty()) { NavRemove(L"*hub*"); return; }
    NavAdd(L"*hub*", hubDir, L"Mydrive", icon);
}
bool SetRootInSync(const std::wstring& id, const std::wstring& root, bool in) {
    bool ok = SetDirInSync(Plain(root), in);
    std::lock_guard<std::mutex> g(g_hubMu);
    g_rootState[id] = in;
    if (!g_hub.empty()) SetDirInSync(g_hub + L"\\" + id, in);
    return ok;
}
void NavHubRemove(const std::wstring& hubDir) {
    NavRemove(L"*hub*");
    Unregister(L"_hub");
    WIN32_FIND_DATAW fd; HANDLE h = FindFirstFileW((hubDir + L"\\*").c_str(), &fd);
    if (h != INVALID_HANDLE_VALUE) {
        std::vector<std::wstring> all;
        do { std::wstring f = fd.cFileName; if (f != L"." && f != L".." && (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) all.push_back(hubDir + L"\\" + f); } while (FindNextFileW(h, &fd));
        FindClose(h);
        for (auto& g : all) RemoveHubLink(g);
    }
    RemoveDirectoryW(hubDir.c_str());
}

bool Register(const std::wstring& id, const std::wstring& path, const std::wstring& display, const std::wstring& icon) {
    // 탐색 창 항목은 Windows 가 등록할 때 스스로 만듦 → 예전 판이 따로 만든 항목이 있으면 지움 (두 번 보이지 않게)
    NavRemove(id);
    (void)&NavAdd;
    return OnMta([&]() -> bool {
        try {
            StorageProviderSyncRootInfo info;
            info.Id(FullId(id));
            info.Path(winrt::Windows::Storage::StorageFolder::GetFolderFromPathAsync(Plain(path)).get());
            info.DisplayNameResource(display);
            info.IconResource(icon);
            info.HydrationPolicy(StorageProviderHydrationPolicy::Full);
            info.HydrationPolicyModifier(StorageProviderHydrationPolicyModifier::AutoDehydrationAllowed);
            info.PopulationPolicy(StorageProviderPopulationPolicy::AlwaysFull);
            info.InSyncPolicy(StorageProviderInSyncPolicy::Default);
            info.HardlinkPolicy(StorageProviderHardlinkPolicy::None);
            info.ShowSiblingsAsGroup(false);
            info.AllowPinning(false);   // Windows 기본 '항상 이 장치에 유지/공간 확보'는 끔 → Mydrive 메뉴 안에 둠
            info.ProtectionMode(StorageProviderProtectionMode::Personal);
            info.Version(L"1.0");
            info.Context(winrt::Windows::Security::Cryptography::CryptographicBuffer::ConvertStringToBinary(
                id, winrt::Windows::Security::Cryptography::BinaryStringEncoding::Utf8));
            // 이미 등록돼 있는데 설정이 예전 것이면 풀고 다시 (같은 이름으로 다시 등록해도 설정은 안 바뀜)
            try {
                auto cur = StorageProviderSyncRootManager::GetSyncRootInformationForId(FullId(id));
                // PC 폴더를 바꾼 경우도: 예전 폴더에 등록이 남아 있으면 새 폴더에 연결이 안 됨
                std::wstring curPath = cur.Path() ? std::wstring(cur.Path().Path()) : L"";
                bool samePath = _wcsicmp(curPath.c_str(), Plain(path).c_str()) == 0;
                if (!samePath) LOG("탐색기 등록 폴더가 바뀜: %s → %s", U(curPath).c_str(), U(Plain(path)).c_str());
                // 주의: 등록을 풀면 Windows 가 그 폴더의 온라인 전용 자리표시 파일을 모두 치움.
                //   AllowPinning 은 false 로 등록해도 Windows 가 true 로 돌려줘서, 예전에는 켤 때마다 풀었다가 다시 등록했음
                //   (→ 온라인 전용 파일이 사라지고, 그것을 'PC에서 지움'으로 오해). 이제는 폴더가 바뀐 경우에만 풂.
                if (!samePath) StorageProviderSyncRootManager::Unregister(FullId(id));
            } catch (...) {}
            StorageProviderSyncRootManager::Register(info);
            return true;
        } catch (winrt::hresult_error const& e) {
            LOG("탐색기 등록 실패 %s: %s (0x%08X)", U(path).c_str(), U(std::wstring(e.message())).c_str(), (unsigned)e.code());
            return false;
        }
    });
}

bool Unregister(const std::wstring& id) {
    NavRemove(id);
    return OnMta([&]() -> bool {
        try { StorageProviderSyncRootManager::Unregister(FullId(id)); return true; }
        catch (winrt::hresult_error const& e) { LOG("탐색기 등록 풀기 실패: %s", U(std::wstring(e.message())).c_str()); return false; }
    });
}

std::vector<std::wstring> RegisteredIds() {
    return OnMta([&]() -> std::vector<std::wstring> {
        std::vector<std::wstring> out;
        try {
            std::wstring pre = L"Mydrive!" + UserSid() + L"!";
            for (auto const& r : StorageProviderSyncRootManager::GetCurrentSyncRoots()) {
                std::wstring i(r.Id());
                if (i.compare(0, pre.size(), pre) == 0) out.push_back(i.substr(pre.size()));
            }
        } catch (...) {}
        return out;
    });
}

bool IsRegistered(const std::wstring& path) {
    CF_SYNC_ROOT_BASIC_INFO bi{}; DWORD n = 0;
    HANDLE h = CreateFileW(path.c_str(), FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    HRESULT hr = CfGetSyncRootInfoByHandle(h, CF_SYNC_ROOT_INFO_BASIC, &bi, sizeof bi, &n);
    CloseHandle(h);
    return SUCCEEDED(hr) || hr == HRESULT_FROM_WIN32(ERROR_MORE_DATA);
}

// ---------- 열 때 받기 ----------
static std::mutex g_mu;
static std::map<int64_t, FetchFn> g_fetch;   // 연결 키 → 받는 함수

static HRESULT Transfer(const CF_CALLBACK_INFO* ci, NTSTATUS st, const void* buf, int64_t off, int64_t len) {
    CF_OPERATION_INFO oi{sizeof oi, CF_OPERATION_TYPE_TRANSFER_DATA, ci->ConnectionKey, ci->TransferKey};
    CF_OPERATION_PARAMETERS op{};
    op.ParamSize = (ULONG)(FIELD_OFFSET(CF_OPERATION_PARAMETERS, TransferData) + sizeof(op.TransferData));
    op.TransferData.CompletionStatus = st;
    op.TransferData.Buffer = buf;
    op.TransferData.Offset.QuadPart = off;
    op.TransferData.Length.QuadPart = len;
    return CfExecute(&oi, &op);
}

static void CALLBACK OnFetchData(const CF_CALLBACK_INFO* ci, const CF_CALLBACK_PARAMETERS* cp) {
    FetchFn f;
    { std::lock_guard<std::mutex> g(g_mu); auto it = g_fetch.find(ci->ConnectionKey.Internal); if (it != g_fetch.end()) f = it->second; }
    int64_t off = cp->FetchData.RequiredFileOffset.QuadPart, total = ci->FileSize.QuadPart;
    std::wstring abs = std::wstring(ci->VolumeDosName) + ci->NormalizedPath;
    if (!f) { Transfer(ci, (NTSTATUS)0xC0000001L /*STATUS_UNSUCCESSFUL*/, nullptr, off, cp->FetchData.RequiredLength.QuadPart); return; }
    // 1 MB 씩 (4 KB 경계) 넘겨줌. 마지막 조각만 짧아도 됨.
    const size_t CH = 1 << 20;
    std::vector<char> buf; buf.reserve(CH);
    int64_t pos = off;
    bool sent = true;   // 넘겨주기가 거절되면(취소·파일 바뀜) 받기를 멈춤
    bool ok = f(abs, off, [&](const char* p, size_t n) {
        while (n) {
            if (pos + (int64_t)buf.size() + (int64_t)n > total) n = (size_t)std::max<int64_t>(0, total - pos - (int64_t)buf.size());   // 파일 크기를 넘는 부분은 버림
            if (!n) break;
            size_t k = std::min(n, CH - buf.size());
            buf.insert(buf.end(), p, p + k); p += k; n -= k;
            if (buf.size() == CH) {
                sent = SUCCEEDED(Transfer(ci, 0 /*STATUS_SUCCESS*/, buf.data(), pos, (int64_t)buf.size()));
                pos += buf.size(); buf.clear();
                if (!sent) return false;
            }
        }
        return pos + (int64_t)buf.size() < total;   // 끝까지 받았으면 멈춤
    });
    if (sent && !buf.empty()) { sent = SUCCEEDED(Transfer(ci, 0, buf.data(), pos, (int64_t)buf.size())); pos += buf.size(); buf.clear(); }
    // 꼭 필요한 부분을 못 넘겼으면 실패를 알림 (여는 프로그램이 오류를 받음)
    int64_t reqEnd = std::min(total, off + cp->FetchData.RequiredLength.QuadPart);
    if (sent && pos < reqEnd) Transfer(ci, (NTSTATUS)0xC000CF0FL /*STATUS_CLOUD_FILE_UNSUCCESSFUL*/, nullptr, pos, reqEnd - pos);
    (void)ok;
}
static void CALLBACK OnCancelFetch(const CF_CALLBACK_INFO*, const CF_CALLBACK_PARAMETERS*) {}

bool Connect(const std::wstring& path, FetchFn fetch, Connection& out) {
    static const CF_CALLBACK_REGISTRATION table[] = {
        {CF_CALLBACK_TYPE_FETCH_DATA, OnFetchData},
        {CF_CALLBACK_TYPE_CANCEL_FETCH_DATA, OnCancelFetch},
        CF_CALLBACK_REGISTRATION_END};
    CF_CONNECTION_KEY key{};
    HRESULT hr = CfConnectSyncRoot(Plain(path).c_str(), table, nullptr, CF_CONNECT_FLAG_REQUIRE_FULL_FILE_PATH, &key);
    if (FAILED(hr)) { LOG("탐색기 연결 실패 %s: 0x%08X", U(path).c_str(), (unsigned)hr); return false; }
    { std::lock_guard<std::mutex> g(g_mu); g_fetch[key.Internal] = std::move(fetch); }
    out.key = key.Internal;
    return true;
}
void Disconnect(Connection& c) {
    if (!c.key) return;
    CF_CONNECTION_KEY k; k.Internal = c.key;
    CfDisconnectSyncRoot(k);
    std::lock_guard<std::mutex> g(g_mu); g_fetch.erase(c.key);
    c.key = 0;
}

// ---------- 자리표시 파일 ----------
static LARGE_INTEGER Li(int64_t ns) { FILETIME ft = NsToFt(ns); LARGE_INTEGER li; li.LowPart = ft.dwLowDateTime; li.HighPart = (LONG)ft.dwHighDateTime; return li; }
static CF_FS_METADATA Meta(bool dir, int64_t size, int64_t mtimeNs) {
    CF_FS_METADATA m{};
    m.FileSize.QuadPart = dir ? 0 : size;
    m.BasicInfo.CreationTime = m.BasicInfo.LastWriteTime = m.BasicInfo.LastAccessTime = m.BasicInfo.ChangeTime = Li(mtimeNs);
    m.BasicInfo.FileAttributes = dir ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
    return m;
}

bool CreatePlaceholder(const std::wstring& parentAbs, const std::wstring& leaf, bool dir, int64_t size, int64_t mtimeNs, const std::string& identity) {
    CF_PLACEHOLDER_CREATE_INFO ci{};
    ci.RelativeFileName = leaf.c_str();
    ci.FsMetadata = Meta(dir, size, mtimeNs);
    std::string id = identity.empty() ? std::string("-") : identity;
    ci.FileIdentity = id.data(); ci.FileIdentityLength = (DWORD)id.size();
    ci.Flags = CF_PLACEHOLDER_CREATE_FLAG_MARK_IN_SYNC | (dir ? CF_PLACEHOLDER_CREATE_FLAG_DISABLE_ON_DEMAND_POPULATION : CF_PLACEHOLDER_CREATE_FLAG_NONE);
    DWORD n = 0;
    HRESULT hr = CfCreatePlaceholders(Plain(parentAbs).c_str(), &ci, 1, CF_CREATE_FLAG_NONE, &n);
    if (FAILED(hr) || FAILED(ci.Result)) { LOG("자리표시 파일 만들기 실패 %s\\%s: 0x%08X/0x%08X", U(parentAbs).c_str(), U(leaf).c_str(), (unsigned)hr, (unsigned)ci.Result); return false; }
    return true;
}

// 자리표시 파일을 다룰 때는 다른 프로그램과 부딪히지 않게 독점 핸들(oplock)로 엶
struct PH {
    HANDLE ph = INVALID_HANDLE_VALUE;
    explicit PH(const std::wstring& abs, bool write) { CfOpenFileWithOplock(Plain(abs).c_str(), write ? (CF_OPEN_FILE_FLAG_WRITE_ACCESS | CF_OPEN_FILE_FLAG_EXCLUSIVE) : CF_OPEN_FILE_FLAG_NONE, &ph); }
    ~PH() { if (ph != INVALID_HANDLE_VALUE) CfCloseHandle(ph); }
    bool ok() const { return ph != INVALID_HANDLE_VALUE; }
    HANDLE h() const { return CfGetWin32HandleFromProtectedHandle(ph); }
};

bool UpdateDehydrated(const std::wstring& abs, int64_t size, int64_t mtimeNs, const std::string& identity) {
    PH p(abs, true);
    if (!p.ok()) return false;
    CF_FS_METADATA m = Meta(false, size, mtimeNs);
    std::string id = identity.empty() ? std::string("-") : identity;
    HRESULT hr = CfUpdatePlaceholder(p.h(), &m, id.data(), (DWORD)id.size(), nullptr, 0, CF_UPDATE_FLAG_DEHYDRATE | CF_UPDATE_FLAG_MARK_IN_SYNC, nullptr, nullptr);
    if (FAILED(hr)) LOG("자리표시 파일 고치기 실패 %s: 0x%08X", U(abs).c_str(), (unsigned)hr);
    return SUCCEEDED(hr);
}

bool MarkInSync(const std::wstring& abs, bool dir, const std::string& identity) {
    DWORD attr = GetFileAttributesW(abs.c_str());
    if (attr == INVALID_FILE_ATTRIBUTES) return false;
    // 바꾸려면 쓰기 권한이 있어야 함 (내용은 건드리지 않음 — 시각도 그대로)
    HANDLE h = CreateFileW(abs.c_str(), FILE_READ_ATTRIBUTES | FILE_WRITE_ATTRIBUTES | FILE_WRITE_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                           OPEN_EXISTING, dir ? FILE_FLAG_BACKUP_SEMANTICS : 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    HRESULT hr;
    if (attr & FILE_ATTRIBUTE_REPARSE_POINT) hr = CfSetInSyncState(h, CF_IN_SYNC_STATE_IN_SYNC, CF_SET_IN_SYNC_FLAG_NONE, nullptr);
    else {
        std::string id = identity.empty() ? std::string("-") : identity;
        hr = CfConvertToPlaceholder(h, id.data(), (DWORD)id.size(), CF_CONVERT_FLAG_MARK_IN_SYNC, nullptr, nullptr);
    }
    CloseHandle(h);
    return SUCCEEDED(hr);
}

bool Hydrate(const std::wstring& abs) {
    PH p(abs, false);
    if (!p.ok()) return false;
    LARGE_INTEGER z{}, all; all.QuadPart = -1;
    HRESULT hr = CfHydratePlaceholder(p.h(), z, all, CF_HYDRATE_FLAG_NONE, nullptr);
    if (FAILED(hr)) LOG("받기(항상 유지) 실패 %s: 0x%08X", U(abs).c_str(), (unsigned)hr);
    return SUCCEEDED(hr);
}

bool Revert(const std::wstring& abs, bool dir) {
    HANDLE h = CreateFileW(abs.c_str(), FILE_READ_ATTRIBUTES | FILE_WRITE_ATTRIBUTES | FILE_WRITE_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                           OPEN_EXISTING, dir ? FILE_FLAG_BACKUP_SEMANTICS : 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    HRESULT hr = CfRevertPlaceholder(h, CF_REVERT_FLAG_NONE, nullptr);
    CloseHandle(h);
    return SUCCEEDED(hr);
}

static bool SetPin(const std::wstring& abs, bool dir, CF_PIN_STATE st, bool recurse) {
    HANDLE h = CreateFileW(abs.c_str(), FILE_READ_ATTRIBUTES | FILE_WRITE_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                           OPEN_EXISTING, dir ? FILE_FLAG_BACKUP_SEMANTICS : 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    HRESULT hr = CfSetPinState(h, st, recurse ? CF_SET_PIN_FLAG_RECURSE : CF_SET_PIN_FLAG_NONE, nullptr);
    CloseHandle(h);
    return SUCCEEDED(hr);
}
bool ClearPin(const std::wstring& abs, bool dir, bool recurse) { return SetPin(abs, dir, CF_PIN_STATE_UNSPECIFIED, recurse); }
bool PinDir(const std::wstring& abs) { return SetPin(abs, true, CF_PIN_STATE_PINNED, true); }
bool PinOne(const std::wstring& abs, bool dir) { return SetPin(abs, dir, CF_PIN_STATE_PINNED, false); }
bool SetKeep(const std::wstring& abs, bool keep) {
    DWORD a = GetFileAttributesW(abs.c_str());
    if (a == INVALID_FILE_ATTRIBUTES) return false;
    bool dir = (a & FILE_ATTRIBUTE_DIRECTORY) != 0;
    return SetPin(abs, dir, keep ? CF_PIN_STATE_PINNED : CF_PIN_STATE_UNPINNED, dir);
}

bool Dehydrate(const std::wstring& abs) {
    PH p(abs, true);
    if (!p.ok()) return false;
    LARGE_INTEGER z{}, all; all.QuadPart = -1;
    HRESULT hr = CfDehydratePlaceholder(p.h(), z, all, CF_DEHYDRATE_FLAG_NONE, nullptr);
    if (FAILED(hr)) LOG("공간 확보 실패 %s: 0x%08X", U(abs).c_str(), (unsigned)hr);
    return SUCCEEDED(hr);
}

} // namespace md::cloud
