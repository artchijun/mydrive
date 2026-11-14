// webdrive.cpp — 웹드라이브 (설명은 webdrive.h)
//   WinFsp - Windows File System Proxy, Copyright (C) Bill Zissimopoulos — https://github.com/winfsp/winfsp
//   winfsp-x64.dll 은 늦게 불러옴(/DELAYLOAD): WinFsp 가 없는 PC 에서도 앱은 그대로 뜨고, 웹드라이브만 '설치 필요'로 알림
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define PNTSTATUS NTSTATUS*   // 이 SDK 의 winternl.h 에는 없음
#include <winfsp/winfsp.h>
#include "webdrive.h"
#include "util.h"
#include "http.h"
#include "../third_party/json.hpp"
#include <sddl.h>
#include <shared_mutex>
#include <unordered_map>
#include <map>
#include <set>
#include <vector>
#include <thread>
#include <condition_variable>
#include <algorithm>
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "delayimp.lib")

using json = nlohmann::json;

namespace md {

namespace {
struct Ent { bool d = false; int64_t s = 0, m = 0; uint64_t i = 0; };
struct Node { Ent e; std::set<std::string> kids; };

std::string Rel(PCWSTR fn) {   // L"\\가\\나.txt" → "가/나.txt"
    std::wstring w = fn ? fn : L"";
    while (!w.empty() && w[0] == L'\\') w.erase(0, 1);
    while (!w.empty() && w.back() == L'\\') w.pop_back();
    for (auto& c : w) if (c == L'\\') c = L'/';
    return U(w);
}
std::string Join(const std::string& dir, const std::string& name) { return dir.empty() ? name : dir + "/" + name; }
bool Under(const std::string& p, const std::string& dir) { return p.size() > dir.size() && p.compare(0, dir.size(), dir) == 0 && p[dir.size()] == '/'; }
UINT64 Ft(int64_t ns) { return (UINT64)(ns / 100 + EPOCH_DIFF_100NS); }
int64_t NowNs() { FILETIME ft; GetSystemTimeAsFileTime(&ft); return FtToNs(ft); }
int64_t FtNs(UINT64 ft) { FILETIME f{(DWORD)(ft & 0xffffffff), (DWORD)(ft >> 32)}; return FtToNs(f); }
bool EqNoCase(const std::string& a, const std::string& b) {
    std::wstring x = W(a), y = W(b);
    return CompareStringOrdinal(x.c_str(), (int)x.size(), y.c_str(), (int)y.size(), TRUE) == CSTR_EQUAL;
}
// 탐색기에 보이지 않을 이름: Windows 에서 쓸 수 없는 이름, 시놀로지 내부 폴더, 우리 임시 파일
bool Hidden(const std::string& p) {
    if (BadWinName(p)) return true;
    for (size_t i = 0; i < p.size();) {
        size_t j = p.find('/', i); if (j == std::string::npos) j = p.size();
        if (AlwaysSkipName(W(p.substr(i, j - i)))) return true;
        i = j + 1;
    }
    return false;
}
Ent FromJ(const json& j) {
    Ent e; e.d = j.value("d", false); e.s = j.value("s", (int64_t)0); e.m = j.value("m", (int64_t)0); e.i = j.value("i", (uint64_t)0); return e;
}
bool g_loaded = false, g_loadOk = false;
std::mutex g_loadMu;
bool LoadWinFsp() {
    std::lock_guard<std::mutex> g(g_loadMu);
    if (!g_loaded) { g_loaded = true; g_loadOk = NT_SUCCESS(FspLoad(nullptr)); }
    return g_loadOk;
}
}   // namespace

bool WinFspInstalled() { return LoadWinFsp(); }

std::wstring FreeDriveLetters() {
    DWORD used = GetLogicalDrives();
    std::wstring s;
    for (wchar_t c = L'D'; c <= L'Z'; c++) if (!(used & (1u << (c - L'A')))) s += c;
    return s;
}

// 열린 파일 하나 (같은 파일을 여러 번 열면 함께 씀)
struct FileObj {
    std::string path;
    bool dir = false;
    std::mutex m;
    HANDLE tmp = INVALID_HANDLE_VALUE;   // 고치는 중이면 PC 임시 파일 (내용 전체)
    std::wstring tmpPath;
    bool dirty = false, isNew = false, deleted = false;
    int64_t size = 0, mtime = 0;
    uint64_t ino = 0;
    int refs = 0;
    // 읽기 조각 (가장 최근에 받은 것) — 차례로 읽으면 조각을 키움
    int64_t cOff = -1, nextOff = -1, chunk = 1 << 20;
    std::string cBuf;
};
struct Handle { std::shared_ptr<FileObj> fo; PVOID dirBuf = nullptr; };

struct WebDrive::Impl {
    WebDriveCfg c;
    WebDrive* owner;
    Http http, poll, up;
    FSP_FILE_SYSTEM* fs = nullptr;
    std::atomic<bool> stop{false}, mounted{false}, online{false}, loaded{false};
    std::atomic<int> uploading{0};
    std::atomic<uint64_t> total{0}, freeB{0};
    std::thread th;
    HANDLE wake = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    std::shared_mutex tmu;                              // 목록 보호
    std::unordered_map<std::string, Node> nodes;
    std::string epoch; int64_t seq = 0;
    std::mutex omu;                                     // 열린 파일 보호
    std::unordered_map<std::string, std::shared_ptr<FileObj>> open;
    std::vector<std::shared_ptr<FileObj>> pending;      // 닫았는데 아직 못 올린 파일 (다시 시도)
    PSECURITY_DESCRIPTOR sd = nullptr; ULONG sdLen = 0;
    std::atomic<uint64_t> tmpSeq{0};

    Impl(WebDriveCfg cfg, WebDrive* o) : c(std::move(cfg)), owner(o), http(c.serverUrl, c.token), poll(c.serverUrl, c.token), up(c.serverUrl, c.token) {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(L"O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FA;;;WD)", SDDL_REVISION_1, &sd, &sdLen);
        nodes[""].e.d = true;
        CreateDirectoryW(c.cacheDir.c_str(), nullptr);
    }
    ~Impl() { if (sd) LocalFree(sd); if (wake) CloseHandle(wake); }

    void Ev(const char* k, const std::string& p, const std::string& x = "") {
        LOG("[%s · 웹드라이브] %s  %s%s", c.name.c_str(), k, p.c_str(), x.empty() ? "" : ("  (" + x + ")").c_str());
        if (owner->onEvent) owner->onEvent(k, p, x);
    }
    static NTSTATUS NetErr(const Resp& r) {
        if (r.status == 404) return STATUS_OBJECT_NAME_NOT_FOUND;
        if (r.status == 409) return STATUS_OBJECT_NAME_COLLISION;
        if (r.status == 403 || r.status == 401) return STATUS_ACCESS_DENIED;
        if (r.status == 0) return STATUS_NETWORK_UNREACHABLE;
        return STATUS_UNEXPECTED_NETWORK_ERROR;
    }

    // ---------- 목록 ----------
    void PutLocked(const std::string& p, const Ent& e) {
        if (p.empty()) return;
        std::string par = Parent(p);
        if (!nodes.count(par)) { Ent pe; pe.d = true; PutLocked(par, pe); }
        nodes[p].e = e;
        nodes[par].kids.insert(Leaf(p));
    }
    void DelLocked(const std::string& p) {
        auto it = nodes.find(p);
        if (it == nodes.end() || p.empty()) return;
        std::vector<std::string> ks(it->second.kids.begin(), it->second.kids.end());
        for (auto& k : ks) DelLocked(Join(p, k));
        nodes.erase(p);
        auto pi = nodes.find(Parent(p));
        if (pi != nodes.end()) pi->second.kids.erase(Leaf(p));
    }
    void Put(const std::string& p, const Ent& e) { std::unique_lock<std::shared_mutex> g(tmu); PutLocked(p, e); }
    void Del(const std::string& p) { std::unique_lock<std::shared_mutex> g(tmu); DelLocked(p); }
    // 대소문자를 가리지 않고 찾아 NAS 의 실제 이름으로
    bool ResolveLocked(const std::string& rel, std::string* out) {
        std::string cur;
        for (size_t i = 0; i < rel.size();) {
            size_t j = rel.find('/', i); if (j == std::string::npos) j = rel.size();
            std::string comp = rel.substr(i, j - i);
            auto it = nodes.find(cur);
            if (it == nodes.end() || !it->second.e.d) return false;
            std::string hit;
            if (it->second.kids.count(comp)) hit = comp;
            else for (auto& k : it->second.kids) if (EqNoCase(k, comp)) { hit = k; break; }
            if (hit.empty()) return false;
            cur = Join(cur, hit);
            i = j + 1;
        }
        *out = cur;
        return true;
    }
    bool Lookup(const std::string& rel, std::string* p, Ent* e) {
        std::shared_lock<std::shared_mutex> g(tmu);
        if (!ResolveLocked(rel, p)) return false;
        *e = nodes[*p].e;
        return true;
    }
    NTSTATUS NotFound(const std::string& rel) {
        std::shared_lock<std::shared_mutex> g(tmu);
        std::string par;
        return ResolveLocked(Parent(rel), &par) ? STATUS_OBJECT_NAME_NOT_FOUND : STATUS_OBJECT_PATH_NOT_FOUND;
    }
    // 옮기기·이름 바꾸기: 아래 항목까지 새 이름으로
    void MoveTree(const std::string& from, const std::string& to) {
        std::unique_lock<std::shared_mutex> g(tmu);
        std::vector<std::pair<std::string, Ent>> sub;
        std::vector<std::string> st{from};
        while (!st.empty()) {
            std::string p = st.back(); st.pop_back();
            auto it = nodes.find(p); if (it == nodes.end()) continue;
            sub.push_back({p, it->second.e});
            for (auto& k : it->second.kids) st.push_back(Join(p, k));
        }
        DelLocked(from);
        std::sort(sub.begin(), sub.end(), [](auto& a, auto& b) { return a.first.size() < b.first.size(); });
        for (auto& [p, e] : sub) PutLocked(to + p.substr(from.size()), e);
    }

    // ---------- 열린 파일 ----------
    std::shared_ptr<FileObj> Acquire(const std::string& p, const Ent& e) {
        std::lock_guard<std::mutex> g(omu);
        auto it = open.find(p);
        if (it != open.end()) { it->second->refs++; return it->second; }
        auto fo = std::make_shared<FileObj>();
        fo->path = p; fo->dir = e.d; fo->size = e.s; fo->mtime = e.m ? e.m : NowNs(); fo->ino = e.i; fo->refs = 1;
        open[p] = fo;
        return fo;
    }
    void RekeyOpen(const std::string& from, const std::string& to) {
        std::lock_guard<std::mutex> g(omu);
        std::vector<std::pair<std::string, std::shared_ptr<FileObj>>> mv;
        for (auto it = open.begin(); it != open.end();) {
            if (it->first == from || Under(it->first, from)) { mv.push_back(*it); it = open.erase(it); }
            else ++it;
        }
        for (auto& [k, fo] : mv) { std::string nk = to + k.substr(from.size()); fo->path = nk; open[nk] = fo; }
    }
    bool NewTmp(FileObj& fo, bool create) {
        wchar_t n[64]; swprintf(n, 64, L"\\wd-%u-%llu.tmp", GetCurrentProcessId(), (unsigned long long)++tmpSeq);
        fo.tmpPath = c.cacheDir + n;
        fo.tmp = CreateFileW(fo.tmpPath.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                             create ? CREATE_ALWAYS : OPEN_EXISTING, FILE_ATTRIBUTE_TEMPORARY, nullptr);
        return fo.tmp != INVALID_HANDLE_VALUE;
    }
    // 쓰기 전에: 내용 전체를 PC 임시 파일로 (새 파일이거나 통째로 덮어쓰면 받지 않음)
    NTSTATUS EnsureTmp(FileObj& fo, bool needContent) {
        if (fo.tmp != INVALID_HANDLE_VALUE) return STATUS_SUCCESS;
        if (!needContent || fo.isNew || fo.size == 0) return NewTmp(fo, true) ? STATUS_SUCCESS : FspNtStatusFromWin32(GetLastError());
        wchar_t n[64]; swprintf(n, 64, L"\\wd-%u-%llu.tmp", GetCurrentProcessId(), (unsigned long long)++tmpSeq);
        std::wstring path = c.cacheDir + n;
        Resp r = http.Download("/v1/file", {{"root", c.name}, {"path", fo.path}}, path);
        if (!r.ok()) { Ev("받기 실패", fo.path, "고치려고 받는 중 — " + (r.error.empty() ? std::to_string(r.status) : r.error)); return NetErr(r); }
        SetFileAttributesW(path.c_str(), FILE_ATTRIBUTE_TEMPORARY);
        fo.tmpPath = path;
        fo.tmp = CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0, nullptr);
        if (fo.tmp == INVALID_HANDLE_VALUE) return FspNtStatusFromWin32(GetLastError());
        LARGE_INTEGER sz; GetFileSizeEx(fo.tmp, &sz); fo.size = sz.QuadPart;
        return STATUS_SUCCESS;
    }
    void DropTmp(FileObj& fo) {
        if (fo.tmp != INVALID_HANDLE_VALUE) { CloseHandle(fo.tmp); fo.tmp = INVALID_HANDLE_VALUE; }
        if (!fo.tmpPath.empty()) { DeleteFileW(fo.tmpPath.c_str()); fo.tmpPath.clear(); }
    }
    // 고친 파일을 NAS 로 올림 (fo.m 을 잡은 채로)
    bool UploadLocked(FileObj& fo) {
        if (!fo.dirty || fo.deleted || fo.tmp == INVALID_HANDLE_VALUE) return true;
        FlushFileBuffers(fo.tmp);
        uploading++;
        Resp r = up.Upload("/v1/file", {{"root", c.name}, {"path", fo.path}, {"mtime", std::to_string(fo.mtime)}}, fo.tmpPath);
        uploading--;
        if (!r.ok()) { Ev("올리기 실패", fo.path, (r.error.empty() ? std::to_string(r.status) : r.error) + " · 잠시 뒤 다시"); return false; }
        json j = json::parse(r.body, nullptr, false);
        Ent e; if (!j.is_discarded()) e = FromJ(j); else { e.s = fo.size; e.m = fo.mtime; }
        Put(fo.path, e);
        fo.dirty = false; fo.isNew = false; fo.ino = e.i;
        Ev("↑ 올림", fo.path, std::to_string(e.s) + " B");
        return true;
    }
    void Release(const std::shared_ptr<FileObj>& fo) {
        bool last;
        {
            std::lock_guard<std::mutex> g(omu);
            last = --fo->refs == 0;
            if (last) { auto it = open.find(fo->path); if (it != open.end() && it->second == fo) open.erase(it); }
        }
        if (!last) return;
        std::lock_guard<std::mutex> g(fo->m);
        if (fo->dirty && !fo->deleted && !UploadLocked(*fo)) { std::lock_guard<std::mutex> g2(omu); pending.push_back(fo); SetEvent(wake); return; }
        DropTmp(*fo);
    }
    void RetryPending() {
        std::vector<std::shared_ptr<FileObj>> list;
        { std::lock_guard<std::mutex> g(omu); list.swap(pending); }
        for (auto& fo : list) {
            std::lock_guard<std::mutex> g(fo->m);
            if (UploadLocked(*fo)) DropTmp(*fo);
            else { std::lock_guard<std::mutex> g2(omu); pending.push_back(fo); }
        }
    }

    void Fill(const Ent& e, FSP_FSCTL_FILE_INFO* fi) {
        memset(fi, 0, sizeof *fi);
        fi->FileAttributes = e.d ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_ARCHIVE;
        fi->FileSize = e.d ? 0 : (UINT64)e.s;
        fi->AllocationSize = (fi->FileSize + 4095) & ~4095ULL;
        UINT64 t = Ft(e.m ? e.m : NowNs());
        fi->CreationTime = fi->LastAccessTime = fi->LastWriteTime = fi->ChangeTime = t;
        fi->IndexNumber = e.i;
    }
    void FillFo(const FileObj& fo, FSP_FSCTL_FILE_INFO* fi) {
        Ent e; e.d = fo.dir; e.s = fo.size; e.m = fo.mtime; e.i = fo.ino;
        Fill(e, fi);
    }
    void SpaceRefresh() {
        Resp r = http.Req(L"GET", "/v1/roots", {}, nullptr, 20);
        if (!r.ok()) return;
        json j = json::parse(r.body, nullptr, false);
        if (!j.is_array()) return;
        for (auto& x : j) if (x.value("name", std::string()) == c.name) { total = x.value("total", (uint64_t)0); freeB = x.value("free", (uint64_t)0); }
    }

    // ---------- 목록 받기 · 변경 기다리기 ----------
    bool LoadSnapshot() {
        Resp r = http.Req(L"GET", "/v1/snapshot", {{"root", c.name}}, nullptr, 300);
        if (!r.ok()) return false;
        json j = json::parse(r.body, nullptr, false);
        if (j.is_discarded()) return false;
        std::unordered_map<std::string, Node> nn;
        nn[""].e.d = true;
        std::vector<std::pair<std::string, Ent>> es;
        for (auto& e : j["entries"]) { std::string p = e.value("p", std::string()); if (!p.empty() && !Hidden(p)) es.push_back({p, FromJ(e)}); }
        std::sort(es.begin(), es.end(), [](auto& a, auto& b) { return a.first < b.first; });
        {
            std::unique_lock<std::shared_mutex> g(tmu);
            nodes.swap(nn);
            for (auto& [p, e] : es) PutLocked(p, e);
            epoch = j.value("epoch", std::string()); seq = j.value("seq", (int64_t)0);
        }
        online = true;
        return true;
    }
    void Notify(const std::vector<std::pair<std::string, UINT32>>& ch) {
        if (!fs || ch.empty()) return;
        if (!NT_SUCCESS(FspFileSystemNotifyBegin(fs, 1000))) return;
        std::vector<UINT8> buf(sizeof(FSP_FSCTL_NOTIFY_INFO) + 1024 * sizeof(WCHAR));
        size_t n = 0;
        for (auto& [p, act] : ch) {
            if (++n > 500) break;
            std::wstring w = L"\\" + W(p); for (auto& x : w) if (x == L'/') x = L'\\';
            if (w.size() > 1000) continue;
            auto* ni = (FSP_FSCTL_NOTIFY_INFO*)buf.data();
            memset(ni, 0, sizeof *ni);
            ni->Size = (UINT16)(FIELD_OFFSET(FSP_FSCTL_NOTIFY_INFO, FileNameBuf) + w.size() * sizeof(WCHAR));
            ni->Filter = FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_DIR_NAME | FILE_NOTIFY_CHANGE_SIZE | FILE_NOTIFY_CHANGE_LAST_WRITE;
            ni->Action = act;
            memcpy(ni->FileNameBuf, w.data(), w.size() * sizeof(WCHAR));
            FspFileSystemNotify(fs, ni, ni->Size);
        }
        FspFileSystemNotifyEnd(fs);
    }
    void PollOnce() {
        std::string ep; int64_t since;
        { std::shared_lock<std::shared_mutex> g(tmu); ep = epoch; since = seq; }
        Resp r = poll.Req(L"GET", "/v1/changes", {{"root", c.name}, {"epoch", ep}, {"since", std::to_string(since)}, {"wait", "50"}}, nullptr, 70);
        if (stop) return;
        if (!r.ok()) { online = false; WaitForSingleObject(wake, 5000); return; }
        online = true;
        json j = json::parse(r.body, nullptr, false);
        if (j.is_discarded()) return;
        if (j.value("reset", false)) { LoadSnapshot(); Notify({{"", FILE_ACTION_MODIFIED}}); return; }
        std::vector<std::pair<std::string, UINT32>> ch;
        {
            std::unique_lock<std::shared_mutex> g(tmu);
            for (auto& x : j["changes"]) {
                std::string p = x["e"].value("p", std::string());
                seq = std::max<int64_t>(seq, x.value("q", seq));
                if (p.empty() || Hidden(p)) continue;
                if (x.value("op", std::string()) == "put") {
                    Ent e = FromJ(x["e"]);
                    auto it = nodes.find(p);
                    bool fresh = it == nodes.end();
                    if (!fresh && it->second.e.s == e.s && it->second.e.m == e.m && it->second.e.d == e.d) { it->second.e.i = e.i; continue; }   // 우리가 방금 올린 것
                    PutLocked(p, e);
                    ch.push_back({p, (UINT32)(fresh ? FILE_ACTION_ADDED : FILE_ACTION_MODIFIED)});
                } else if (nodes.count(p)) {
                    DelLocked(p);
                    ch.push_back({p, (UINT32)FILE_ACTION_REMOVED});
                }
            }
            if (j["changes"].empty()) seq = std::max<int64_t>(seq, j.value("seq", seq));
        }
        Notify(ch);
    }

    // ---------- 붙이기 ----------
    bool Mount(std::string* err);
    void Unmount() {
        if (!fs) return;
        FspFileSystemStopDispatcher(fs);
        FspFileSystemDelete(fs);
        fs = nullptr;
        mounted = false;
    }
    void Run() {
        int fail = 0;
        uint64_t spaceAt = 0;
        while (!stop) {
            if (!loaded) {
                if (!LoadSnapshot()) {
                    online = false;
                    if (fail++ == 0) Ev("웹드라이브 연결 안 됨", c.name, "NAS 목록을 받지 못함 — 잠시 뒤 다시");
                    WaitForSingleObject(wake, 10000);
                    continue;
                }
                loaded = true; fail = 0;
            }
            if (!mounted) {
                std::string err;
                if (!Mount(&err)) {
                    if (fail++ % 20 == 0) Ev("웹드라이브 붙이기 실패", c.name, err);
                    WaitForSingleObject(wake, 15000);
                    continue;
                }
                mounted = true; fail = 0;
                Ev("🔗 웹드라이브 붙임", c.name, U(c.mount));
            }
            if (GetTickCount64() > spaceAt) { SpaceRefresh(); spaceAt = GetTickCount64() + 300000; }
            PollOnce();
            if (!stop) RetryPending();
        }
    }
};

// ---------- WinFsp 콜백 ----------
static WebDrive::Impl* I(FSP_FILE_SYSTEM* fs) { return (WebDrive::Impl*)fs->UserContext; }
static Handle* H(PVOID ctx) { return (Handle*)ctx; }
static NTSTATUS Impl_NetErr(const Resp& r) { return WebDrive::Impl::NetErr(r); }

static NTSTATUS GetVolumeInfo(FSP_FILE_SYSTEM* fs, FSP_FSCTL_VOLUME_INFO* vi) {
    auto* d = I(fs);
    uint64_t t = d->total, f = d->freeB;
    vi->TotalSize = t ? t : (1ULL << 44);
    vi->FreeSize = t ? f : (1ULL << 43);
    std::wstring l = W(d->c.name);
    size_t n = std::min<size_t>(l.size(), 31);
    memcpy(vi->VolumeLabel, l.c_str(), n * sizeof(WCHAR));
    vi->VolumeLabelLength = (UINT16)(n * sizeof(WCHAR));
    return STATUS_SUCCESS;
}

static NTSTATUS GetSecurityByName(FSP_FILE_SYSTEM* fs, PWSTR fn, PUINT32 pAttr, PSECURITY_DESCRIPTOR sd, SIZE_T* pSd) {
    auto* d = I(fs);
    std::string rel = Rel(fn), p; Ent e;
    if (!d->Lookup(rel, &p, &e)) return d->NotFound(rel);
    if (pAttr) *pAttr = e.d ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_ARCHIVE;
    if (pSd) {
        if (*pSd < d->sdLen) { *pSd = d->sdLen; return STATUS_BUFFER_OVERFLOW; }
        *pSd = d->sdLen;
        if (sd) memcpy(sd, d->sd, d->sdLen);
    }
    return STATUS_SUCCESS;
}

static NTSTATUS Create(FSP_FILE_SYSTEM* fs, PWSTR fn, UINT32 co, UINT32, UINT32, PSECURITY_DESCRIPTOR, UINT64, PVOID* pctx, FSP_FSCTL_FILE_INFO* fi) {
    auto* d = I(fs);
    std::string rel = Rel(fn), par;
    {
        std::shared_lock<std::shared_mutex> g(d->tmu);
        std::string x;
        if (d->ResolveLocked(rel, &x)) return STATUS_OBJECT_NAME_COLLISION;
        if (!d->ResolveLocked(Parent(rel), &par)) return STATUS_OBJECT_PATH_NOT_FOUND;
        if (!d->nodes[par].e.d) return STATUS_NOT_A_DIRECTORY;
    }
    std::string p = Join(par, Leaf(rel));
    if (Hidden(p)) return STATUS_OBJECT_NAME_INVALID;
    bool dir = (co & FILE_DIRECTORY_FILE) != 0;
    Ent e; e.d = dir; e.m = NowNs();
    if (dir) {
        Resp r = d->http.Req(L"POST", "/v1/mkdir", {{"root", d->c.name}, {"path", p}});
        if (!r.ok()) return Impl_NetErr(r);
        json j = json::parse(r.body, nullptr, false);
        if (!j.is_discarded()) e = FromJ(j);
        e.d = true;
        d->Put(p, e);
        d->Ev("↑ 폴더", p);
    } else d->Put(p, e);   // 닫을 때 올림 — 그동안 목록에는 0바이트로 보임
    auto fo = d->Acquire(p, e);
    if (!dir) {
        bool ok; DWORD er = 0;
        { std::lock_guard<std::mutex> g(fo->m); fo->isNew = true; fo->dirty = true; ok = d->NewTmp(*fo, true); if (!ok) { er = GetLastError(); fo->dirty = false; } }
        if (!ok) { d->Del(p); d->Release(fo); return FspNtStatusFromWin32(er); }
    }
    *pctx = new Handle{fo};
    d->FillFo(*fo, fi);
    return STATUS_SUCCESS;
}

static NTSTATUS Open(FSP_FILE_SYSTEM* fs, PWSTR fn, UINT32, UINT32, PVOID* pctx, FSP_FSCTL_FILE_INFO* fi) {
    auto* d = I(fs);
    std::string rel = Rel(fn), p; Ent e;
    if (!d->Lookup(rel, &p, &e)) return d->NotFound(rel);
    auto fo = d->Acquire(p, e);
    *pctx = new Handle{fo};
    std::lock_guard<std::mutex> g(fo->m);
    d->FillFo(*fo, fi);
    return STATUS_SUCCESS;
}

static NTSTATUS Overwrite(FSP_FILE_SYSTEM* fs, PVOID ctx, UINT32, BOOLEAN, UINT64, FSP_FSCTL_FILE_INFO* fi) {
    auto* d = I(fs); auto fo = H(ctx)->fo;
    std::lock_guard<std::mutex> g(fo->m);
    NTSTATUS s = d->EnsureTmp(*fo, false);
    if (!NT_SUCCESS(s)) return s;
    LARGE_INTEGER z{}; SetFilePointerEx(fo->tmp, z, nullptr, FILE_BEGIN); SetEndOfFile(fo->tmp);
    fo->size = 0; fo->dirty = true; fo->mtime = NowNs(); fo->cOff = -1; fo->cBuf.clear();
    d->FillFo(*fo, fi);
    return STATUS_SUCCESS;
}

static VOID Cleanup(FSP_FILE_SYSTEM* fs, PVOID ctx, PWSTR, ULONG flags) {
    auto* d = I(fs); auto fo = H(ctx)->fo;
    if (flags & FspCleanupDelete) {
        std::lock_guard<std::mutex> g(fo->m);
        if (!fo->isNew) {
            Resp r = d->http.Req(L"DELETE", "/v1/file", {{"root", d->c.name}, {"path", fo->path}});
            if (!r.ok() && r.status != 404) { d->Ev("지우기 실패", fo->path, r.error.empty() ? std::to_string(r.status) : r.error); return; }
            d->Ev("✕ NAS 휴지통으로", fo->path);
        }
        fo->deleted = true; fo->dirty = false;
        d->Del(fo->path);
        d->DropTmp(*fo);
        return;
    }
    if (!fo->dir) { std::lock_guard<std::mutex> g(fo->m); if (fo->dirty) d->UploadLocked(*fo); }
}

static VOID Close(FSP_FILE_SYSTEM* fs, PVOID ctx) {
    auto* d = I(fs); auto* h = H(ctx);
    if (h->dirBuf) FspFileSystemDeleteDirectoryBuffer(&h->dirBuf);
    d->Release(h->fo);
    delete h;
}

static NTSTATUS Read(FSP_FILE_SYSTEM* fs, PVOID ctx, PVOID buf, UINT64 off, ULONG len, PULONG pn) {
    auto* d = I(fs); auto fo = H(ctx)->fo;
    if (fo->dir) return STATUS_INVALID_DEVICE_REQUEST;
    std::lock_guard<std::mutex> g(fo->m);
    if (fo->tmp != INVALID_HANDLE_VALUE) {
        OVERLAPPED ov{}; ov.Offset = (DWORD)off; ov.OffsetHigh = (DWORD)(off >> 32);
        DWORD got = 0;
        if (!ReadFile(fo->tmp, buf, len, &got, &ov)) { DWORD e = GetLastError(); return e == ERROR_HANDLE_EOF ? STATUS_END_OF_FILE : FspNtStatusFromWin32(e); }
        *pn = got;
        return STATUS_SUCCESS;
    }
    if ((int64_t)off >= fo->size) return STATUS_END_OF_FILE;
    int64_t want = std::min<int64_t>(len, fo->size - (int64_t)off);
    if (fo->cOff >= 0 && (int64_t)off >= fo->cOff && (int64_t)off + want <= fo->cOff + (int64_t)fo->cBuf.size()) {
        memcpy(buf, fo->cBuf.data() + (off - fo->cOff), (size_t)want);
        *pn = (ULONG)want; fo->nextOff = off + want;
        return STATUS_SUCCESS;
    }
    fo->chunk = (int64_t)off == fo->nextOff ? std::min<int64_t>(fo->chunk * 2, 16 << 20) : (1 << 20);   // 차례로 읽으면 크게
    int64_t n = std::min<int64_t>(fo->size - (int64_t)off, std::max<int64_t>(want, fo->chunk));
    std::string data;
    Resp r = d->http.Range("/v1/file", {{"root", d->c.name}, {"path", fo->path}}, (int64_t)off, n, data);
    if (!r.ok() || (int64_t)data.size() < want) {
        if (r.ok()) return STATUS_END_OF_FILE;   // 그사이 NAS 에서 줄어듦
        d->Ev("읽기 실패", fo->path, r.error.empty() ? std::to_string(r.status) : r.error);
        return Impl_NetErr(r);
    }
    fo->cOff = (int64_t)off; fo->cBuf.swap(data);
    memcpy(buf, fo->cBuf.data(), (size_t)want);
    *pn = (ULONG)want; fo->nextOff = off + want;
    return STATUS_SUCCESS;
}

static NTSTATUS Write(FSP_FILE_SYSTEM* fs, PVOID ctx, PVOID buf, UINT64 off, ULONG len, BOOLEAN toEnd, BOOLEAN constrained, PULONG pn, FSP_FSCTL_FILE_INFO* fi) {
    auto* d = I(fs); auto fo = H(ctx)->fo;
    if (fo->dir) return STATUS_INVALID_DEVICE_REQUEST;
    std::lock_guard<std::mutex> g(fo->m);
    NTSTATUS s = d->EnsureTmp(*fo, true);
    if (!NT_SUCCESS(s)) return s;
    if (constrained) {
        if ((int64_t)off >= fo->size) { *pn = 0; d->FillFo(*fo, fi); return STATUS_SUCCESS; }
        if ((int64_t)(off + len) > fo->size) len = (ULONG)(fo->size - (int64_t)off);
    }
    if (toEnd) off = (UINT64)fo->size;
    OVERLAPPED ov{}; ov.Offset = (DWORD)off; ov.OffsetHigh = (DWORD)(off >> 32);
    DWORD w = 0;
    if (!WriteFile(fo->tmp, buf, len, &w, &ov)) return FspNtStatusFromWin32(GetLastError());
    fo->size = std::max<int64_t>(fo->size, (int64_t)off + w);
    fo->dirty = true; fo->mtime = NowNs(); fo->cOff = -1; fo->cBuf.clear();
    *pn = w;
    d->FillFo(*fo, fi);
    return STATUS_SUCCESS;
}

static NTSTATUS Flush(FSP_FILE_SYSTEM* fs, PVOID ctx, FSP_FSCTL_FILE_INFO* fi) {
    if (!ctx) return STATUS_SUCCESS;   // 볼륨 전체
    auto* d = I(fs); auto fo = H(ctx)->fo;
    std::lock_guard<std::mutex> g(fo->m);
    if (fo->tmp != INVALID_HANDLE_VALUE) FlushFileBuffers(fo->tmp);
    d->FillFo(*fo, fi);
    return STATUS_SUCCESS;
}

static NTSTATUS GetFileInfo(FSP_FILE_SYSTEM* fs, PVOID ctx, FSP_FSCTL_FILE_INFO* fi) {
    auto* d = I(fs); auto fo = H(ctx)->fo;
    std::lock_guard<std::mutex> g(fo->m);
    d->FillFo(*fo, fi);
    return STATUS_SUCCESS;
}

static NTSTATUS SetBasicInfo(FSP_FILE_SYSTEM* fs, PVOID ctx, UINT32, UINT64, UINT64, UINT64 wt, UINT64, FSP_FSCTL_FILE_INFO* fi) {
    auto* d = I(fs); auto fo = H(ctx)->fo;
    std::lock_guard<std::mutex> g(fo->m);
    if (wt && !fo->dir) fo->mtime = FtNs(wt);   // 복사할 때 원래 수정 시각 → 올릴 때 그대로
    d->FillFo(*fo, fi);
    return STATUS_SUCCESS;
}

static NTSTATUS SetFileSize(FSP_FILE_SYSTEM* fs, PVOID ctx, UINT64 ns, BOOLEAN alloc, FSP_FSCTL_FILE_INFO* fi) {
    auto* d = I(fs); auto fo = H(ctx)->fo;
    if (fo->dir) return STATUS_INVALID_DEVICE_REQUEST;
    std::lock_guard<std::mutex> g(fo->m);
    if ((alloc && (int64_t)ns >= fo->size) || ((int64_t)ns == fo->size && fo->tmp == INVALID_HANDLE_VALUE)) { d->FillFo(*fo, fi); return STATUS_SUCCESS; }
    NTSTATUS s = d->EnsureTmp(*fo, ns > 0);
    if (!NT_SUCCESS(s)) return s;
    LARGE_INTEGER li; li.QuadPart = (LONGLONG)ns;
    if (!SetFilePointerEx(fo->tmp, li, nullptr, FILE_BEGIN) || !SetEndOfFile(fo->tmp)) return FspNtStatusFromWin32(GetLastError());
    fo->size = (int64_t)ns; fo->dirty = true; fo->mtime = NowNs(); fo->cOff = -1; fo->cBuf.clear();
    d->FillFo(*fo, fi);
    return STATUS_SUCCESS;
}

static NTSTATUS CanDelete(FSP_FILE_SYSTEM* fs, PVOID ctx, PWSTR) {
    auto* d = I(fs); auto fo = H(ctx)->fo;
    if (!fo->dir) return STATUS_SUCCESS;
    std::shared_lock<std::shared_mutex> g(d->tmu);
    auto it = d->nodes.find(fo->path);
    return it != d->nodes.end() && !it->second.kids.empty() ? STATUS_DIRECTORY_NOT_EMPTY : STATUS_SUCCESS;
}

static NTSTATUS Rename(FSP_FILE_SYSTEM* fs, PVOID ctx, PWSTR, PWSTR newFn, BOOLEAN replace) {
    auto* d = I(fs); auto fo = H(ctx)->fo;
    std::string src = fo->path, nrel = Rel(newFn), npar, dst, exists;
    {
        std::shared_lock<std::shared_mutex> g(d->tmu);
        if (!d->ResolveLocked(Parent(nrel), &npar)) return STATUS_OBJECT_PATH_NOT_FOUND;
        dst = Join(npar, Leaf(nrel));
        std::string ex;
        if (d->ResolveLocked(nrel, &ex) && ex != src) {
            if (!replace) return STATUS_OBJECT_NAME_COLLISION;
            if (d->nodes[ex].e.d) return STATUS_ACCESS_DENIED;
            exists = ex;
        }
    }
    if (dst == src) return STATUS_SUCCESS;
    if (Hidden(dst)) return STATUS_OBJECT_NAME_INVALID;
    if (!exists.empty()) {   // 덮어쓰기 (예: 오피스가 임시 파일을 원래 이름으로 바꿀 때)
        Resp r = d->http.Req(L"DELETE", "/v1/file", {{"root", d->c.name}, {"path", exists}});
        if (!r.ok() && r.status != 404) return Impl_NetErr(r);
        d->Del(exists);
    }
    bool isNew; { std::lock_guard<std::mutex> g(fo->m); isNew = fo->isNew; }
    if (!isNew) {
        Resp r = d->http.Req(L"POST", "/v1/move", {{"root", d->c.name}, {"from", src}, {"to", dst}}, nullptr, 900);
        if (!r.ok()) { d->Ev("이름 바꾸기 실패", src, r.error.empty() ? std::to_string(r.status) : r.error); return Impl_NetErr(r); }
    }
    d->MoveTree(src, dst);
    d->RekeyOpen(src, dst);
    d->Ev("↔ 이름 바꿈", src, "→ " + dst);
    return STATUS_SUCCESS;
}

static NTSTATUS GetSecurity(FSP_FILE_SYSTEM* fs, PVOID, PSECURITY_DESCRIPTOR sd, SIZE_T* pSd) {
    auto* d = I(fs);
    if (*pSd < d->sdLen) { *pSd = d->sdLen; return STATUS_BUFFER_OVERFLOW; }
    *pSd = d->sdLen;
    if (sd) memcpy(sd, d->sd, d->sdLen);
    return STATUS_SUCCESS;
}
static NTSTATUS SetSecurity(FSP_FILE_SYSTEM*, PVOID, SECURITY_INFORMATION, PSECURITY_DESCRIPTOR) { return STATUS_SUCCESS; }

static bool AddDir(WebDrive::Impl* d, PVOID* buf, const std::string& name, const Ent& e, NTSTATUS* res) {
    std::wstring w = W(name);
    if (w.size() > 255) return true;
    union { UINT8 B[FIELD_OFFSET(FSP_FSCTL_DIR_INFO, FileNameBuf) + 256 * sizeof(WCHAR)]; FSP_FSCTL_DIR_INFO D; } di;
    memset(&di.D, 0, sizeof(FSP_FSCTL_DIR_INFO));
    di.D.Size = (UINT16)(FIELD_OFFSET(FSP_FSCTL_DIR_INFO, FileNameBuf) + w.size() * sizeof(WCHAR));
    d->Fill(e, &di.D.FileInfo);
    memcpy(di.D.FileNameBuf, w.data(), w.size() * sizeof(WCHAR));
    return FspFileSystemFillDirectoryBuffer(buf, &di.D, res) != FALSE;
}

static NTSTATUS ReadDirectory(FSP_FILE_SYSTEM* fs, PVOID ctx, PWSTR, PWSTR marker, PVOID buf, ULONG len, PULONG pn) {
    auto* d = I(fs); auto* h = H(ctx);
    NTSTATUS res = STATUS_SUCCESS;
    if (FspFileSystemAcquireDirectoryBuffer(&h->dirBuf, marker == nullptr, &res)) {
        std::vector<std::pair<std::string, Ent>> list;
        Ent self; self.d = true;
        {
            std::shared_lock<std::shared_mutex> g(d->tmu);
            auto it = d->nodes.find(h->fo->path);
            if (it != d->nodes.end()) {
                self = it->second.e;
                for (auto& k : it->second.kids) { auto ci = d->nodes.find(Join(h->fo->path, k)); if (ci != d->nodes.end()) list.push_back({k, ci->second.e}); }
            }
        }
        bool ok = true;
        if (!h->fo->path.empty()) { ok = AddDir(d, &h->dirBuf, ".", self, &res) && AddDir(d, &h->dirBuf, "..", self, &res); }
        for (auto& [n, e] : list) { if (!ok) break; ok = AddDir(d, &h->dirBuf, n, e, &res); }
        FspFileSystemReleaseDirectoryBuffer(&h->dirBuf);
    }
    if (!NT_SUCCESS(res)) return res;
    FspFileSystemReadDirectoryBuffer(&h->dirBuf, marker, buf, len, pn);
    return STATUS_SUCCESS;
}

static NTSTATUS GetDirInfoByName(FSP_FILE_SYSTEM* fs, PVOID ctx, PWSTR fn, FSP_FSCTL_DIR_INFO* di) {
    auto* d = I(fs); auto fo = H(ctx)->fo;
    std::string want = U(fn ? fn : L""), hit;
    Ent e;
    {
        std::shared_lock<std::shared_mutex> g(d->tmu);
        auto it = d->nodes.find(fo->path);
        if (it == d->nodes.end()) return STATUS_OBJECT_NAME_NOT_FOUND;
        if (it->second.kids.count(want)) hit = want;
        else for (auto& k : it->second.kids) if (EqNoCase(k, want)) { hit = k; break; }
        if (hit.empty()) return STATUS_OBJECT_NAME_NOT_FOUND;
        e = d->nodes[Join(fo->path, hit)].e;
    }
    std::wstring w = W(hit);
    if (w.size() > 255) return STATUS_OBJECT_NAME_NOT_FOUND;
    di->Size = (UINT16)(FIELD_OFFSET(FSP_FSCTL_DIR_INFO, FileNameBuf) + w.size() * sizeof(WCHAR));
    d->Fill(e, &di->FileInfo);
    memcpy(di->FileNameBuf, w.data(), w.size() * sizeof(WCHAR));
    return STATUS_SUCCESS;
}

static FSP_FILE_SYSTEM_INTERFACE g_iface = [] {
    FSP_FILE_SYSTEM_INTERFACE f{};
    f.GetVolumeInfo = GetVolumeInfo;
    f.GetSecurityByName = GetSecurityByName;
    f.Create = Create;
    f.Open = Open;
    f.Overwrite = Overwrite;
    f.Cleanup = Cleanup;
    f.Close = Close;
    f.Read = Read;
    f.Write = Write;
    f.Flush = Flush;
    f.GetFileInfo = GetFileInfo;
    f.SetBasicInfo = SetBasicInfo;
    f.SetFileSize = SetFileSize;
    f.CanDelete = CanDelete;
    f.Rename = Rename;
    f.GetSecurity = GetSecurity;
    f.SetSecurity = SetSecurity;
    f.ReadDirectory = ReadDirectory;
    f.GetDirInfoByName = GetDirInfoByName;
    return f;
}();

bool WebDrive::Impl::Mount(std::string* err) {
    if (!LoadWinFsp()) { *err = "WinFsp 가 설치되어 있지 않음 — 설정 → 동기화 작업에서 [WinFsp 설치]"; return false; }
    std::wstring mp = c.mount;
    bool letter = mp.size() == 2 && mp[1] == L':';
    if (letter) {
        if (GetLogicalDrives() & (1u << (towupper(mp[0]) - L'A'))) { *err = U(mp) + " 드라이브 문자를 이미 다른 드라이브가 쓰고 있음"; return false; }
    } else {
        DWORD a = GetFileAttributesW(mp.c_str());
        if (a != INVALID_FILE_ATTRIBUTES) {   // WinFsp 는 없는 폴더에 붙음 → 빈 폴더면 지우고 붙임
            if (!(a & FILE_ATTRIBUTE_DIRECTORY) || !RemoveDirectoryW(mp.c_str())) { *err = U(mp) + " 폴더가 비어 있지 않음 (웹드라이브는 없거나 빈 폴더에 붙음)"; return false; }
        }
    }
    FSP_FSCTL_VOLUME_PARAMS vp{};
    vp.Version = sizeof vp;
    vp.SectorSize = 4096;
    vp.SectorsPerAllocationUnit = 1;
    vp.MaxComponentLength = 255;
    vp.VolumeCreationTime = Ft(NowNs());
    vp.VolumeSerialNumber = (UINT32)std::hash<std::string>{}(c.name);
    vp.FileInfoTimeout = 1000;
    vp.CaseSensitiveSearch = 0;
    vp.CasePreservedNames = 1;
    vp.UnicodeOnDisk = 1;
    vp.PersistentAcls = 1;
    vp.PostCleanupWhenModifiedOnly = 1;
    vp.FlushAndPurgeOnCleanup = 1;   // 닫으면 Windows 캐시를 비움 → 다른 PC가 고친 내용을 다음에 열 때 바로 봄
    vp.UmFileContextIsUserContext2 = 1;
    wcscpy_s(vp.FileSystemName, L"Mydrive");
    if (letter) {   // 드라이브 문자: 네트워크 드라이브로 (탐색기 '네트워크 위치'에 \\mydrive\이름 으로 보임)
        std::wstring pre = L"\\mydrive\\" + W(c.name);
        wcsncpy_s(vp.Prefix, pre.c_str(), _TRUNCATE);
    }
    FSP_FILE_SYSTEM* f = nullptr;
    NTSTATUS s = FspFileSystemCreate((PWSTR)(letter ? L"" FSP_FSCTL_NET_DEVICE_NAME : L"" FSP_FSCTL_DISK_DEVICE_NAME), &vp, &g_iface, &f);
    if (!NT_SUCCESS(s)) { char b[64]; sprintf_s(b, "WinFsp 파일 시스템을 만들지 못함 (0x%08X)", (unsigned)s); *err = b; return false; }
    f->UserContext = this;
    s = FspFileSystemSetMountPoint(f, (PWSTR)mp.c_str());
    if (!NT_SUCCESS(s)) { FspFileSystemDelete(f); char b[64]; sprintf_s(b, "붙이지 못함 (0x%08X)", (unsigned)s); *err = b; return false; }
    s = FspFileSystemStartDispatcher(f, 0);
    if (!NT_SUCCESS(s)) { FspFileSystemDelete(f); char b[64]; sprintf_s(b, "시작하지 못함 (0x%08X)", (unsigned)s); *err = b; return false; }
    fs = f;
    return true;
}

// ---------- 바깥에서 쓰는 부분 ----------
WebDrive::WebDrive(WebDriveCfg c) : d_(std::make_unique<Impl>(std::move(c), this)) {}
WebDrive::~WebDrive() { Stop(); }
const WebDriveCfg& WebDrive::cfg() const { return d_->c; }
bool WebDrive::Mounted() const { return d_->mounted; }
bool WebDrive::Online() const { return d_->online; }
int WebDrive::Uploading() const { return d_->uploading; }
void WebDrive::Start() {
    d_->stop = false;
    d_->th = std::thread([this] { d_->Run(); });
}
void WebDrive::Stop() {
    if (d_->stop.exchange(true)) return;
    d_->poll.Abort();
    SetEvent(d_->wake);
    if (d_->th.joinable()) d_->th.join();
    d_->Unmount();               // 열린 파일이 닫히며 남은 것은 올림 (Close → Release)
    d_->RetryPending();          // 그래도 못 올린 것 한 번 더
}

}   // namespace md
