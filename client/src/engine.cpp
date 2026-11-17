#include "engine.h"
#include "../third_party/json.hpp"
#include <shellapi.h>
#include <shlobj.h>
#include <bcrypt.h>
#pragma comment(lib, "bcrypt.lib")
#include <shlwapi.h>
#include <restartmanager.h>
#include <pdh.h>
#pragma comment(lib, "pdh.lib")
#include <algorithm>
#include <set>
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "shlwapi.lib")
#pragma comment(lib, "rstrtmgr.lib")
#pragma comment(lib, "user32.lib")

using json = nlohmann::json;

namespace md {
static std::wstring PlainRoot(std::wstring r);

// 폴더 짝 순서(rank 0 = 맨 위)가 곧 우선순위: 위 짝이 파일을 옮기는 동안 아래 짝은 기다림
static std::atomic<int> s_rankBusy[8];   // 우선순위 '높음' 연결이 파일을 옮기는 중인 수
struct RankGuard { int r; explicit RankGuard(int x) : r(std::clamp(x, 0, 7)) { s_rankBusy[r]++; } ~RankGuard() { s_rankBusy[r]--; } };
static bool UpperBusy(int rank) { for (int i = 0; i < std::min(rank, 8); i++) if (s_rankBusy[i] > 0) return true; return false; }

static bool Under(const std::string& p, const std::string& dir) {
    return !dir.empty() && (p == dir || (p.size() > dir.size() && p.compare(0, dir.size(), dir) == 0 && p[dir.size()] == '/'));
}

// 제외 규칙: 이름 패턴(경로의 어느 부분이든), 선택 동기화로 뺀 폴더, 최대 크기
bool Engine::Excluded(const std::string& p, bool d, int64_t size) const {
    if (!d && cfg_.maxFileSize > 0 && size > cfg_.maxFileSize) return true;
    if (g_skipMask.load() & 16) {   // 점(.)으로 시작하는 폴더: 그 폴더와 안의 모든 것 (마지막 칸은 폴더일 때만)
        for (size_t i = 0; i < p.size();) {
            size_t j = p.find('/', i); bool last = j == std::string::npos; if (last) j = p.size();
            if (p[i] == '.' && (!last || d)) return true;
            i = j + 1;
        }
    }
    for (auto& s : cfg_.skipDirs) if (Under(p, s)) return true;
    if (!cfg_.excludes.empty()) {
        for (size_t i = 0; i <= p.size();) {
            size_t j = p.find('/', i); if (j == std::string::npos) j = p.size();
            std::wstring comp = W(p.substr(i, j - i));
            for (auto& pat : cfg_.excludes) if (!pat.empty() && PathMatchSpecW(comp.c_str(), pat.c_str())) return true;
            i = j + 1;
        }
    }
    return false;
}

// 파일을 잡고 있는 프로그램 (Windows Restart Manager)
std::vector<Holder> FileHolders(std::wstring path) {
    if (path.compare(0, 4, L"\\\\?\\") == 0 && path.size() < MAX_PATH + 4) path = path.substr(4);
    std::vector<Holder> out;
    DWORD s; WCHAR key[CCH_RM_SESSION_KEY + 1] = {};
    if (RmStartSession(&s, 0, key) != ERROR_SUCCESS) return out;
    LPCWSTR f = path.c_str();
    if (RmRegisterResources(s, 1, &f, 0, nullptr, 0, nullptr) == ERROR_SUCCESS) {
        UINT need = 0, n = 0; DWORD reason = 0;
        DWORD rc = RmGetList(s, &need, &n, nullptr, &reason);
        if ((rc == ERROR_MORE_DATA || rc == ERROR_SUCCESS) && need) {
            std::vector<RM_PROCESS_INFO> v(need); n = need;
            if (RmGetList(s, &need, &n, v.data(), &reason) == ERROR_SUCCESS)
                for (UINT i = 0; i < n; i++) out.push_back({v[i].Process.dwProcessId, v[i].strAppName, v[i].Process.ProcessStartTime});
        }
    }
    RmEndSession(s);
    return out;
}
static std::string Holders(const std::wstring& path) {
    std::string out;
    for (auto& h : FileHolders(path)) {
        std::string a = U(h.name);
        if (a.empty() || out.find(a) != std::string::npos) continue;
        if (!out.empty()) out += ", ";
        out += a;
    }
    return out;
}
// 다른 프로그램이 쓰는 중인지: 방금 바뀐 파일(아직 쓰는 중일 수 있음)이나 잠긴 파일은 이번에 건너뛰고 잠시 뒤 다시 봄
bool Engine::Busy(const std::string& p, bool forReplace) {
    std::wstring a = Abs(cfg_.localRoot, p);
    uint64_t now = GetTickCount64();
    if (!forReplace) {
        LocalE l;
        if (StatLocal(p, l) && !l.d) {
            FILETIME ft; GetSystemTimeAsFileTime(&ft);
            int64_t age = FtToNs(ft) - l.m;
            if (age >= 0 && age < (int64_t)cfg_.quietSec * 1000000000LL) {
                uint64_t t = now + (uint64_t)cfg_.quietSec * 1000 + 300;
                if (!retryAt_ || t < retryAt_) retryAt_ = t;
                return true;   // 방금 바뀜: 조용해지면 올림 (알리지 않음)
            }
        }
    }
    // 올리기: 읽을 수 있어야 함 / 바꾸기·지우기: 지울 수 있어야 함
    DWORD acc = forReplace ? DELETE : GENERIC_READ;
    HANDLE h = CreateFileW(a.c_str(), acc, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (h != INVALID_HANDLE_VALUE) {
        CloseHandle(h);
        size_t was; { std::lock_guard<std::mutex> g(xMu_); was = waitNote_.erase(p); }
        if (was) Event("▶ 이어서 진행", p, "다른 프로그램이 파일을 놓음");
        return false;
    }
    DWORD e = GetLastError();
    if (e != ERROR_SHARING_VIOLATION && e != ERROR_LOCK_VIOLATION && e != ERROR_ACCESS_DENIED) return false;
    std::string who = Holders(a);
    std::string note = who.empty() ? "다른 프로그램이 쓰는 중" : who + " 이(가) 쓰는 중";
    bool fresh;
    {
        std::lock_guard<std::mutex> g(xMu_);
        auto it = waitNote_.find(p);
        fresh = it == waitNote_.end() || it->second.first != note;
        if (it == waitNote_.end()) waitNote_[p] = {note, now}; else it->second.first = note;
    }
    if (fresh) {
        Event("⏳ 기다림", p, note + " — 닫으면 이어서 맞춤");
    }
    uint64_t t = now + 15000;
    if (!retryAt_ || t < retryAt_) retryAt_ = t;
    return true;
}

Engine::Engine(Config c) : cfg_(std::move(c)), http_(cfg_.serverUrl, cfg_.token), poll_(cfg_.serverUrl, cfg_.token), fetch_(cfg_.serverUrl, cfg_.token) {
    wake_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    CreateDirectoryW(cfg_.stateDir.c_str(), nullptr);
    if (cfg_.pcName.empty()) {
        wchar_t n[64]; DWORD l = 64; GetComputerNameW(n, &l); cfg_.pcName = n;
    }
    Load();
    LoadOpened();
}
Engine::~Engine() { Stop(); if (wake_) CloseHandle(wake_); }

void Engine::Event(const char* kind, const std::string& p, const std::string& extra) {
    std::string line = std::string(kind) + "  " + p + (extra.empty() ? "" : "  (" + extra + ")");
    LOG("[%s] %s", cfg_.name.c_str(), line.c_str());
    // 같은 파일이 계속 실패하면 '막힘'으로 보이게 기록, 성공하면 지움
    std::string k = kind;
    if (k.find("실패") != std::string::npos) {
        std::lock_guard<std::mutex> g(xMu_);
        auto& f = fails_[p]; uint64_t now = GetTickCount64();
        if (!f.n) f.first = now;
        f.n++; f.last = now; f.msg = k + (extra.empty() ? "" : " — " + extra);
        failRetryAt_ = now + 30000;   // 30초 뒤 다시 시도
    } else if (k.rfind("↑", 0) == 0 || k.rfind("↓", 0) == 0 || k.rfind("✕", 0) == 0 || k.rfind("☁", 0) == 0) {
        std::lock_guard<std::mutex> g(xMu_); fails_.erase(p);
    }
    if (onEvent) onEvent(kind, p, extra);
}

// ---------- 상태 저장 (마지막으로 맞춘 상태 + NAS 목록) ----------
static void PutS(std::string& o, const std::string& s) { uint32_t n = (uint32_t)s.size(); o.append((char*)&n, 4); o += s; }
template <class T> static void PutT(std::string& o, const T& v) { o.append((const char*)&v, sizeof v); }
struct Rd {
    const std::string& b; size_t i = 0; bool bad = false;
    std::string S() { uint32_t n; T(n); if (i + n > b.size()) { bad = true; return {}; } std::string s = b.substr(i, n); i += n; return s; }
    template <class X> void T(X& v) { if (i + sizeof v > b.size()) { bad = true; v = {}; return; } memcpy(&v, b.data() + i, sizeof v); i += sizeof v; }
};

void Engine::Save() {
    std::lock_guard<std::recursive_mutex> g(mu_);
    std::string o = "MDS1";
    PutS(o, epoch_); PutT(o, seq_);
    PutT(o, (uint64_t)base_.size());
    for (auto& [p, b] : base_) { PutS(o, p); PutT(o, b); }
    PutT(o, (uint64_t)remote_.size());
    for (auto& [p, r] : remote_) { PutS(o, p); PutT(o, r); }
    std::wstring f = cfg_.stateDir + L"\\" + W(cfg_.name) + L".state", tmp = f + L".tmp";
    HANDLE h = CreateFileW(tmp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return;
    DWORD w; BOOL ok = WriteFile(h, o.data(), (DWORD)o.size(), &w, nullptr) && w == o.size();
    FlushFileBuffers(h); CloseHandle(h);
    if (ok) MoveFileExW(tmp.c_str(), f.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
    dirty_ = false;
    RebuildIdx();
}

void Engine::Load() {
    std::wstring f = cfg_.stateDir + L"\\" + W(cfg_.name) + L".state";
    HANDLE h = CreateFileW(f.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return;
    LARGE_INTEGER sz; GetFileSizeEx(h, &sz);
    std::string b((size_t)sz.QuadPart, 0); DWORD r;
    ReadFile(h, b.data(), (DWORD)b.size(), &r, nullptr); CloseHandle(h);
    if (b.size() < 4 || b.compare(0, 4, "MDS1") != 0) return;
    Rd rd{b}; rd.i = 4;
    std::string ep = rd.S(); int64_t sq; rd.T(sq);
    std::map<std::string, BaseE> base; std::map<std::string, RemoteE> remote;
    uint64_t n; rd.T(n);
    for (uint64_t k = 0; k < n && !rd.bad; k++) { std::string p = rd.S(); BaseE e; rd.T(e); base[p] = e; }
    rd.T(n);
    for (uint64_t k = 0; k < n && !rd.bad; k++) { std::string p = rd.S(); RemoteE e; rd.T(e); remote[p] = e; }
    if (rd.bad) { LOG("[%s] 상태 파일이 손상됨 — 처음부터 맞춤 (지우지 않고 비교만)", cfg_.name.c_str()); return; }
    epoch_ = ep; seq_ = sq; base_ = std::move(base); remote_ = std::move(remote);
    RebuildIdx();
}

// ---------- NAS 목록 ----------
static RemoteE FromJ(const json& j) {
    RemoteE e; e.d = j.value("d", false); e.s = j.value("s", (int64_t)0); e.m = j.value("m", (int64_t)0); e.i = j.value("i", (uint64_t)0); return e;
}

void Engine::ApplyRemoteEntry(const std::string& p, const RemoteE& e) { remote_[p] = e; }

bool Engine::RefreshRemote() {
    bool force = resync_.exchange(false);
    if (force) LOG("[%s] NAS 전체 목록을 새로 받음 (들고 있던 목록 바로잡기)", cfg_.name.c_str());
    for (int round = 0; round < 50; round++) {
        if (!epoch_.empty() && !force) {
            Resp r = http_.Req(L"GET", "/v1/changes", {{"root", cfg_.name}, {"epoch", epoch_}, {"since", std::to_string(seq_)}});
            if (!r.ok()) { stats.online = false; LOG("[%s] NAS 연결 실패: %s %d", cfg_.name.c_str(), r.error.c_str(), r.status); return false; }
            stats.online = true;
            json j = json::parse(r.body, nullptr, false);
            if (j.is_discarded()) return false;
            if (!j.value("reset", false)) {
                auto& ch = j["changes"];
                for (auto& c : ch) {
                    std::string p = c["e"]["p"];
                    if (c["op"] == "put") ApplyRemoteEntry(p, FromJ(c["e"])); else remote_.erase(p);
                    seq_ = c["q"];
                    dirty_ = true;
                }
                if (ch.empty()) { seq_ = std::max(seq_, j.value("seq", seq_)); return true; }
                continue;   // 더 있을 수 있음
            }
            LOG("[%s] NAS 변경 기록을 이어 받을 수 없음 — 전체 목록을 다시 받음", cfg_.name.c_str());
        }
        Resp r = http_.Req(L"GET", "/v1/snapshot", {{"root", cfg_.name}}, nullptr, 300);
        if (!r.ok()) { stats.online = false; LOG("[%s] NAS 목록 받기 실패: %s %d", cfg_.name.c_str(), r.error.c_str(), r.status); return false; }
        stats.online = true;
        json j = json::parse(r.body, nullptr, false);
        if (j.is_discarded()) return false;
        remote_.clear();
        for (auto& e : j["entries"]) ApplyRemoteEntry(e["p"], FromJ(e));
        epoch_ = j["epoch"]; seq_ = j["seq"]; dirty_ = true;
        if (force) { force = false; Event("↻ NAS 목록 새로 받음", cfg_.name, std::to_string(remote_.size()) + "개"); }
        // 다시 받은 뒤에도 그 사이 변경을 이어 받도록 한 번 더 돈다
    }
    return true;
}

// ---------- 로컬 폴더 읽기 (폴더 하나를 한 번에 읽는 방식: 이름·크기·시각·파일 번호를 같이 받음) ----------
static uint8_t OwnPin(DWORD a) { return cloud::IsPinned(a) ? 1 : cloud::IsUnpinned(a) ? 2 : 0; }   // '항상 유지'(1) / '공간 확보'(2)

// 폴더 하나를 읽음 (아래 폴더로는 들어가지 않음). 열 수 없으면 false
bool Engine::ListDir(const std::string& dir, uint8_t parentPin, int64_t nowNs, std::vector<std::pair<std::string, LocalE>>& out, std::vector<std::string>& exclPlain) {
    HANDLE h = CreateFileW(Abs(cfg_.localRoot, dir).c_str(), FILE_LIST_DIRECTORY, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    std::vector<char>& buf = scanBuf_;
    if (buf.empty()) buf.resize(256 * 1024);
    FILE_INFO_BY_HANDLE_CLASS cls = FileIdBothDirectoryRestartInfo;
    while (GetFileInformationByHandleEx(h, cls, buf.data(), (DWORD)buf.size())) {
        cls = FileIdBothDirectoryInfo;
        auto* e = (FILE_ID_BOTH_DIR_INFO*)buf.data();
        for (;;) {
            std::wstring n(e->FileName, e->FileNameLength / sizeof(wchar_t));
            // 링크(정션 등)는 따라가지 않음. 단, 탐색기 연동의 자리표시 파일(클라우드 파일)은 보통 파일처럼 다룸
            // (FILE_ATTRIBUTE_REPARSE_POINT 이면 EaSize 칸에 재분석 태그가 들어 있음)
            bool link = (e->FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) && !cloud::IsCloudReparse(e->FileAttributes, e->EaSize);
            // 체크로 뺀 종류의 보통 파일(자리표시 아님): 1분 넘게 그대로면 탐색기 표시만 고칠 대상 (저장 중인 임시 파일은 건드리지 않음)
            if (!(e->FileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) && !AlwaysSkipName(n) &&
                (SkipKind(n) & g_skipMask.load()) && nowNs - FtToNs(*(FILETIME*)&e->LastWriteTime) > 60000000000LL && exclPlain.size() < 500)
                exclPlain.push_back(dir.empty() ? U(n) : dir + "/" + U(n));
            // 늘 빼는 이름(시놀로지 내부 폴더 등)이 자리표시가 아니면 위 폴더가 탐색기에 늘 '동기화 중' → 안까지 표시만 고침 (우리 임시 파일은 제외)
            if (n != L"." && n != L".." && AlwaysSkipName(n) && n.rfind(L".mydrive-tmp-", 0) != 0 && !(e->FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) && exclTree_.size() < 20)
                exclTree_.push_back(dir.empty() ? U(n) : dir + "/" + U(n));
            if (n != L"." && n != L".." && !SkipLocalName(n) && !link) {
                std::string rel = dir.empty() ? U(n) : dir + "/" + U(n);
                LocalE l;
                l.d = (e->FileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
                l.s = l.d ? 0 : e->EndOfFile.QuadPart;
                l.m = FtToNs(*(FILETIME*)&e->LastWriteTime);
                l.id = (uint64_t)e->FileId.QuadPart;
                l.a = e->FileAttributes;
                l.at = FtToNs(*(FILETIME*)&e->LastAccessTime);
                uint8_t own = OwnPin(l.a);
                l.pin = own ? own : parentPin;   // 폴더에 붙은 표시는 아래 항목이 물려받음
                // 이름 패턴·선택 동기화로 뺀 것은 아예 보지 않음 (크기 0 으로 물어 크기 제한은 여기서 보지 않음 — 맞출 때 양쪽을 함께 봄)
                if (!Excluded(rel, l.d, 0)) out.emplace_back(std::move(rel), l);
            }
            if (!e->NextEntryOffset) break;
            e = (FILE_ID_BOTH_DIR_INFO*)((char*)e + e->NextEntryOffset);
        }
    }
    CloseHandle(h);
    return true;
}

// start 폴더 아래 전부를 읽어 out 에 넣음. 맨 위 폴더(start 가 빈 값)를 못 열 때만 false
bool Engine::ScanTree(const std::string& start, uint8_t startPin, int64_t nowNs, std::map<std::string, LocalE>& out, std::vector<std::string>& exclPlain) {
    std::vector<std::pair<std::string, uint8_t>> stack{{start, startPin}};
    std::vector<std::pair<std::string, LocalE>> kids;
    while (!stack.empty()) {
        auto [dir, pin] = stack.back(); stack.pop_back();
        kids.clear();
        if (!ListDir(dir, pin, nowNs, kids, exclPlain)) {
            if (dir.empty()) return false;
            // 읽을 수 없는 폴더: 그 아래는 "모름" — base 에 있는 항목을 그대로 둬서 지우는 것으로 오해하지 않게
            std::string pre = dir + "/";
            for (auto it = base_.lower_bound(pre); it != base_.end() && it->first.compare(0, pre.size(), pre) == 0; ++it)
                out[it->first] = LocalE{it->second.d, it->second.ls, it->second.lm, it->second.lid};
            continue;
        }
        for (auto& [rel, l] : kids) { if (l.d) stack.push_back({rel, l.pin}); out[rel] = l; }
    }
    return true;
}

// ---------- 로컬 폴더 훑기 ----------
//   보통은 Windows 가 '바뀌었다'고 알려 준 폴더만 다시 읽음 (파일 하나 바뀔 때마다 수만~수십만 개를 다 읽지 않게).
//   전체를 읽는 때: 처음, 알림이 넘쳤을 때, 알림을 못 받을 때, [지금 동기화], 그리고 재검사 간격(rescanMin)마다 — 놓친 것이 있어도 여기서 잡힘.
bool Engine::ScanLocal() {
    int64_t nowNs; { FILETIME ft; GetSystemTimeAsFileTime(&ft); nowNs = FtToNs(ft); }
    DWORD attr = GetFileAttributesW(Abs(cfg_.localRoot, "").c_str());
    if (attr == INVALID_FILE_ATTRIBUTES || !(attr & FILE_ATTRIBUTE_DIRECTORY)) {
        LOG("[%s] 로컬 폴더를 열 수 없음 — 이번에는 건너뜀 (지우지 않음)", cfg_.name.c_str());
        scanAll_ = true;
        return false;
    }
    std::set<std::string> dd;
    { std::lock_guard<std::mutex> g(ddMu_); dd.swap(dirtyDirs_); }
    bool all = scanAll_.exchange(false);
    uint64_t now = GetTickCount64();
    uint8_t rp = OwnPin(attr);
    if (!haveLocal_ || !watchOk_ || rp != rootPin_ || now - lastFull_ > (uint64_t)std::max(1, cfg_.rescanMin) * 60000) all = true;
    rootPin_ = rp;
    std::vector<std::string> exclPlain;
    if (all) {
        std::map<std::string, LocalE> out;
        if (!ScanTree("", rootPin_, nowNs, out, exclPlain)) { scanAll_ = true; return false; }
        local_ = std::move(out);
        exclPlain_ = std::move(exclPlain);
        haveLocal_ = true; lastFull_ = now; scanDirs_ = -1;
        return true;
    }
    // 바뀐 폴더만: 그 폴더 바로 아래를 다시 읽어 local_ 을 고침. 새로 생겼거나 바뀐(다른 폴더가 됨·표시가 바뀜) 아래 폴더는 안까지 다시 읽음
    auto eraseTree = [&](const std::string& p) { local_.erase(local_.lower_bound(p + "/"), local_.lower_bound(p + "0")); };   // '0' 은 '/' 바로 다음 글자
    std::vector<std::string> deep;   // 안까지 다시 읽은 폴더 (그 아래 폴더는 또 읽지 않음)
    std::vector<std::pair<std::string, LocalE>> kids;
    int nDirs = 0;
    for (auto& dir : dd) {   // 정렬돼 있어 위 폴더가 먼저 옴
        if (stop_) { scanAll_ = true; return false; }
        if (std::any_of(deep.begin(), deep.end(), [&](const std::string& r) { return Under(dir, r); })) continue;
        uint8_t pin = rootPin_;
        if (!dir.empty()) {
            auto it = local_.find(dir);
            if (it == local_.end() || !it->second.d) continue;   // 모르는 폴더(뺀 폴더이거나, 새 폴더면 위 폴더를 읽을 때 같이 읽음) · 사라진 폴더
            pin = it->second.pin;
        }
        kids.clear();
        if (!ListDir(dir, pin, nowNs, kids, exclPlain)) continue;   // 못 열면 아는 그대로 둠 (사라진 것이면 위 폴더를 읽을 때 지워짐)
        nDirs++;
        std::string pre = dir.empty() ? std::string() : dir + "/";
        std::set<std::string> have;
        for (auto& [rel, l] : kids) have.insert(rel);
        // 전에 있던 바로 아래 항목 중 사라진 것을 지움
        std::vector<std::string> gone;
        for (auto it = pre.empty() ? local_.begin() : local_.lower_bound(pre); it != local_.end() && it->first.compare(0, pre.size(), pre) == 0;) {
            size_t s = it->first.find('/', pre.size());
            if (s != std::string::npos) { it = local_.lower_bound(it->first.substr(0, s) + "0"); continue; }   // 더 아래 항목: 그 폴더를 통째로 건너뜀
            if (!have.count(it->first)) gone.push_back(it->first);
            ++it;
        }
        for (auto& p : gone) { eraseTree(p); local_.erase(p); }
        for (auto& [rel, l] : kids) {
            auto old = local_.find(rel);
            bool had = old != local_.end();
            if (l.d) {
                if (!had || !old->second.d || old->second.id != l.id || old->second.pin != l.pin) {
                    eraseTree(rel);
                    local_[rel] = l;
                    ScanTree(rel, l.pin, nowNs, local_, exclPlain);
                    deep.push_back(rel);
                } else old->second = l;
            } else {
                if (had && old->second.d) eraseTree(rel);
                local_[rel] = l;
            }
        }
    }
    exclPlain_ = std::move(exclPlain);
    scanDirs_ = nDirs;
    return true;
}
bool Engine::StatLocal(const std::string& p, LocalE& out) {
    HANDLE h = CreateFileW(Abs(cfg_.localRoot, p).c_str(), FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    BY_HANDLE_FILE_INFORMATION fi;
    BOOL ok = GetFileInformationByHandle(h, &fi);
    CloseHandle(h);
    if (!ok) return false;
    out.d = (fi.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
    out.s = out.d ? 0 : ((int64_t)fi.nFileSizeHigh << 32 | fi.nFileSizeLow);
    out.m = FtToNs(fi.ftLastWriteTime);
    out.id = ((uint64_t)fi.nFileIndexHigh << 32) | fi.nFileIndexLow;
    out.a = fi.dwFileAttributes;
    return true;
}

void Engine::SetBase(const std::string& p, const LocalE& l, const RemoteE& r, bool mark) {
    std::lock_guard<std::recursive_mutex> g(mu_);
    base_[p] = BaseE{l.d, l.s, l.m, l.id, r.s, r.m, r.i};
    LocalE keep = l;
    if (!keep.pin) { auto it = local_.find(p); keep.pin = it != local_.end() ? it->second.pin : ParentPin(p); }   // 물려받은 표시는 유지
    local_[p] = keep; remote_[p] = r; dirty_ = true;
    if (mark) InSync(p, l.d);   // 탐색기에 '맞춰짐' 표시
    // '항상 유지' 폴더 안에 새로 생긴 항목도 같은 표시 (탐색기에 진한 초록 체크)
    if (cloudOn_ && !unregister_ && keep.pin == 1 && !cloud::IsPinned(l.a)) cloud::PinOne(Abs(cfg_.localRoot, p), l.d);
}

void Engine::Rekey(const std::string& from, const std::string& to) {
    auto move = [&](auto& m) {
        std::vector<std::pair<std::string, typename std::decay_t<decltype(m)>::mapped_type>> moved;
        auto it = m.find(from);
        if (it != m.end()) { moved.push_back({to, it->second}); m.erase(it); }
        std::string pre = from + "/";
        for (auto i = m.lower_bound(pre); i != m.end() && i->first.compare(0, pre.size(), pre) == 0;) {
            moved.push_back({to + i->first.substr(from.size()), i->second}); i = m.erase(i);
        }
        for (auto& [k, v] : moved) m[k] = v;
    };
    move(base_); move(local_); move(remote_);
    dirty_ = true;
}

// ---------- 실행 ----------
static std::string Cond(const RemoteE& r) { return std::to_string(r.s) + ":" + std::to_string(r.m) + ":" + std::to_string(r.i); }
static std::string CondB(const BaseE& b) { return std::to_string(b.rs) + ":" + std::to_string(b.rm) + ":" + std::to_string(b.ri); }

bool Engine::Upload(const std::string& p, const LocalE& l, const BaseE* b) {
    // 조건: NAS 에 지금 그 파일이 없으면 "없어야 함", 있으면 "마지막으로 맞춘 판이어야 함" (그사이 바뀌었으면 서버가 거절)
    std::string cond = "none";
    {
        std::lock_guard<std::recursive_mutex> g(mu_);
        auto it = remote_.find(p);
        if (it != remote_.end()) cond = (b && !b->d) ? CondB(*b) : Cond(it->second);
    }
    auto xs = BeginXfer(p, true, l.s, l.s, l.m);
    t_xferStop = &xs->cancel;
    Resp r = http_.Upload("/v1/file", {{"root", cfg_.name}, {"path", p}, {"mtime", std::to_string(l.m)}, {"if", cond}},
                          Abs(cfg_.localRoot, p), &xs->done);
    t_xferStop = nullptr;
    EndXfer(p);
    if (xs->gone) { Event("⏹ 올리기 멈춤", p, "올리는 중에 PC에서 지움 — NAS에는 올리지 않음"); return false; }
    if (xs->cancel) { Event("⏹ 취소함", p, "올리기 — 파일이 바뀌거나 [지금 동기화]를 누르면 다시 맞춤"); return false; }
    return UploadDone(p, l, r, nullptr);
}

bool Engine::UploadDone(const std::string& p, const LocalE& l, const Resp& r, std::vector<std::pair<std::string, bool>>* marks) {
    if (r.status == 409) {
        Event("충돌", p, "그사이 NAS에서도 바뀜 — NAS 목록을 다시 받아 둘 다 남김");
        // PC 가 들고 있는 NAS 목록이 어긋났을 수 있음 → 전체 목록을 새로 받음 (5분에 한 번까지). 안 그러면 같은 올리기를 계속 되풀이함
        uint64_t now = GetTickCount64();
        if (!lastResync_ || now - lastResync_ > 300000) { lastResync_ = now; resync_ = true; }
        return false;
    }
    if (!r.ok()) { stats.errors++; Event("올리기 실패", p, r.error.empty() ? std::to_string(r.status) + " " + r.body : r.error); return false; }
    json j = json::parse(r.body, nullptr, false);
    RemoteE re = FromJ(j);
    LocalE now;
    {
        std::lock_guard<std::recursive_mutex> g(mu_);
        bool here = StatLocal(p, now);
        bool mark = marks == nullptr;
        if (here && now.s == l.s && SameTime(now.m, l.m)) { SetBase(p, now, re, mark); if (!mark) marks->push_back({p, now.d}); }
        else if (!here) SetBase(p, l, re);   // 다 올린 뒤 지워짐 → 다음 차례에 NAS에서도 지움 (예전에는 PC로 도로 받아 와서 되살아났음)
        else {
            // 올리는 사이 PC 에서 또 바뀜 → '올린 판'을 마지막으로 맞춘 상태로 적어 두고, 다음 차례에 새 판을 다시 올림.
            //   예전에는 NAS 쪽만 적어서 다음 차례에 'PC·NAS 둘 다 바뀜'으로 보고 충돌 사본을 만들었음 (최신 판이 충돌 사본으로 가고 본 파일은 옛 판으로 돌아감)
            SetBase(p, l, re, false);
            auto it = local_.find(p);
            if (it != local_.end()) { now.pin = it->second.pin; it->second = now; }
        }
    }
    stats.up++;
    Event("↑ 올림", p, std::to_string(l.s) + " B");
    return true;
}

// ---------- 작은 파일 묶어 올리기 ----------
// 몸통: [4바이트 머리 길이][머리 JSON {"p","m","if","s"}][내용 s바이트] 를 이어 붙이고 길이 0 으로 끝냄.
// 서버는 파일마다 /v1/file PUT 과 똑같이 처리하고 결과를 차례대로 돌려줌 → 파일마다 주고받던 왕복이 묶음당 한 번.
int Engine::UploadBatch(const std::vector<const Job*>& jobs, bool& fallback) {
    fallback = false;
    struct One { const Job* j; std::string cond; std::shared_ptr<XState> xs; };
    std::vector<One> sent;
    std::string body;
    int64_t bytes = 0;
    for (auto* j : jobs) {
        One o{j, "none", nullptr};
        {
            std::lock_guard<std::recursive_mutex> g(mu_);
            auto it = remote_.find(j->p);
            if (it != remote_.end()) o.cond = (j->hasBase && !j->b.d) ? CondB(j->b) : Cond(it->second);
        }
        // 내용을 지금 읽어 둠 (작은 파일만 오므로 메모리로 충분)
        HANDLE f = CreateFileW(Abs(cfg_.localRoot, j->p).c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                               OPEN_EXISTING, FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
        if (f == INVALID_HANDLE_VALUE) { stats.errors++; Event("올리기 실패", j->p, "파일을 열 수 없음 (다른 프로그램이 쓰는 중일 수 있음)"); continue; }
        LARGE_INTEGER sz{}; GetFileSizeEx(f, &sz);
        if (sz.QuadPart != j->l.s) { CloseHandle(f); continue; }   // 그사이 바뀜 → 다음 차례에 다시 맞춤
        json h = {{"p", j->p}, {"m", j->l.m}, {"if", o.cond}, {"s", j->l.s}};
        std::string hs = h.dump();
        uint32_t hl = (uint32_t)hs.size();
        size_t at = body.size();
        body.append((const char*)&hl, 4); body += hs;
        size_t dataAt = body.size();
        body.resize(dataAt + (size_t)j->l.s);
        DWORD got = 0; bool ok = true; int64_t left = j->l.s; char* dst = body.data() + dataAt;
        while (left > 0) {
            DWORD want = (DWORD)std::min<int64_t>(left, 1 << 20);
            if (!ReadFile(f, dst, want, &got, nullptr) || got == 0) { ok = false; break; }
            dst += got; left -= got;
        }
        CloseHandle(f);
        if (!ok) { body.resize(at); stats.errors++; Event("올리기 실패", j->p, "파일 읽기 실패"); continue; }
        o.xs = BeginXfer(j->p, true, j->l.s, j->l.s, j->l.m);
        bytes += j->l.s;
        sent.push_back(std::move(o));
    }
    if (sent.empty()) return 0;
    uint32_t end = 0; body.append((const char*)&end, 4);
    uint64_t t0 = GetTickCount64();
    Resp r = http_.Req(L"PUT", "/v1/batch", {{"root", cfg_.name}}, &body, 300);
    uint64_t ms = GetTickCount64() - t0;
    for (auto& o : sent) { o.xs->done = o.j->l.s; EndXfer(o.j->p); }
    // 서버가 묶음 올리기를 모름 (예전 판): 하나씩 올리도록 돌려보냄
    if (r.status == 404 || r.status == 405) {
        if (r.body.find("no_root") == std::string::npos) { fallback = true; if (batchOk_.exchange(false)) LOG("[%s] 서버가 묶음 올리기를 지원하지 않음 — 하나씩 올림", cfg_.name.c_str()); return 0; }
    }
    if (!r.ok()) {
        for (auto& o : sent) { stats.errors++; Event("올리기 실패", o.j->p, r.error.empty() ? std::to_string(r.status) + " " + r.body : r.error); }
        return 0;
    }
    json res = json::parse(r.body, nullptr, false);
    const json* arr = (res.is_object() && res.contains("results") && res["results"].is_array()) ? &res["results"] : nullptr;
    std::vector<std::pair<std::string, bool>> marks;
    int okN = 0;
    for (size_t i = 0; i < sent.size(); i++) {
        auto& o = sent[i];
        Resp one;
        if (!arr || i >= arr->size()) { one.status = 0; one.error = "서버 응답에 결과가 없음"; }
        else {
            const json& it = (*arr)[i];
            one.status = it.value("status", 0);
            if (it.contains("e")) one.body = it["e"].dump();
            else if (it.contains("err")) one.body = it["err"].dump();
        }
        if (UploadDone(o.j->p, o.j->l, one, &marks)) okN++;
    }
    // 탐색기 '맞춰짐' 표시는 상태 잠금 밖에서 (다른 통로가 기다리지 않게)
    for (auto& [p, d] : marks) InSync(p, d);
    LOG("[%s] 묶음 올림 %d/%d개 %.1f MB — %llu ms", cfg_.name.c_str(), okN, (int)sent.size(), bytes / 1048576.0, (unsigned long long)ms);
    return okN;
}

bool Engine::Download(const std::string& p, const RemoteE& r, const BaseE* b) {
    std::wstring dst = Abs(cfg_.localRoot, p);
    std::string par = Parent(p);
    if (!par.empty()) { LocalE pd; if (!StatLocal(par, pd)) SHCreateDirectoryExW(nullptr, Abs(cfg_.localRoot, par).c_str() + 4, nullptr); }
    wchar_t rnd[16]; swprintf(rnd, 16, L"%08x", GetTickCount() ^ GetCurrentThreadId());
    std::wstring tmp = Abs(cfg_.localRoot, par.empty() ? ".mydrive-tmp-" + U(rnd) : par + "/.mydrive-tmp-" + U(rnd));
    auto xs = BeginXfer(p, false, r.s, r.s, r.m);
    t_xferStop = &xs->cancel;
    Resp res = http_.Download("/v1/file", {{"root", cfg_.name}, {"path", p}}, tmp, &xs->done);
    t_xferStop = nullptr;
    EndXfer(p);
    if (xs->cancel) { Event("⏹ 취소함", p, "받기 — NAS 파일이 바뀌거나 [지금 동기화]를 누르면 다시 맞춤"); return false; }
    if (!res.ok()) { stats.errors++; Event("받기 실패", p, res.error.empty() ? std::to_string(res.status) : res.error); return false; }
    // 받는 사이 NAS 쪽이 또 바뀌었으면, 받은 판의 정보(헤더)를 씀
    RemoteE got = r;
    if (!res.entryHdr.empty()) sscanf(res.entryHdr.c_str(), "%lld:%lld:%llu", &got.s, &got.m, &got.i);
    HANDLE h = CreateFileW(tmp.c_str(), FILE_WRITE_ATTRIBUTES, 0, nullptr, OPEN_EXISTING, 0, nullptr);
    if (h != INVALID_HANDLE_VALUE) { FILETIME ft = NsToFt(got.m); SetFileTime(h, nullptr, nullptr, &ft); CloseHandle(h); }
    SetFileAttributesW(tmp.c_str(), FILE_ATTRIBUTE_NORMAL);
    // 바꾸기 직전에 로컬이 그대로인지 확인 (그사이 사용자가 고쳤으면 덮지 않음)
    LocalE cur; bool exists = StatLocal(p, cur);
    bool safe = !exists ? true : (b && !cur.d && cur.s == b->ls && SameTime(cur.m, b->lm));
    if (!safe && exists && !cur.d && (cur.a & FILE_ATTRIBUTE_REPARSE_POINT) && cloud::IsPartial(dst)) safe = true;   // 덜 받은 자리표시: 고친 내용이 없음
    if (!safe) { DeleteFileW(tmp.c_str()); Event("받기 보류", p, "로컬에서 그사이 바뀜 — 다음 차례에 판단"); return false; }
    if (!MoveFileExW(tmp.c_str(), dst.c_str(), MOVEFILE_REPLACE_EXISTING)) {
        DWORD e = GetLastError(); DeleteFileW(tmp.c_str());
        stats.errors++; Event("받기 실패", p, e == ERROR_SHARING_VIOLATION || e == ERROR_ACCESS_DENIED ? "다른 프로그램이 쓰는 중" : "바꾸기 실패 " + std::to_string(e));
        return false;
    }
    LocalE now; StatLocal(p, now);
    SetBase(p, now, got);
    stats.down++;
    Event("↓ 받음", p, std::to_string(got.s) + " B");
    if (IsConflictName(Leaf(p))) NoteConflict(p);
    return true;
}

bool Engine::MkdirRemote(const std::string& p) {
    Resp r = http_.Req(L"POST", "/v1/mkdir", {{"root", cfg_.name}, {"path", p}});
    if (!r.ok()) { stats.errors++; Event("NAS 폴더 만들기 실패", p, r.error); return false; }
    json j = json::parse(r.body, nullptr, false);
    LocalE l; StatLocal(p, l);
    SetBase(p, l, FromJ(j));
    Event("↑ 폴더", p);
    return true;
}

bool Engine::MkdirLocal(const std::string& p, const RemoteE& r) {
    std::wstring a = Abs(cfg_.localRoot, p);
    int rc = SHCreateDirectoryExW(nullptr, a.c_str() + 4, nullptr);   // \\?\ 빼고
    if (rc != ERROR_SUCCESS && rc != ERROR_ALREADY_EXISTS && rc != ERROR_FILE_EXISTS) {
        if (!CreateDirectoryW(a.c_str(), nullptr)) { stats.errors++; Event("폴더 만들기 실패", p); return false; }
    }
    LocalE l; StatLocal(p, l);
    SetBase(p, l, r);
    Event("↓ 폴더", p);
    return true;
}

bool Engine::DeleteRemote(const std::string& p, const BaseE& b) {
    Resp r = http_.Req(L"DELETE", "/v1/file", {{"root", cfg_.name}, {"path", p}, {"if", b.d ? std::string("dir") : CondB(b)}});
    if (r.status == 409) { Event("NAS 삭제 보류", p, "NAS에서 그사이 바뀜 — 지우지 않음"); return false; }
    if (!r.ok()) { stats.errors++; Event("NAS 삭제 실패", p, r.error); return false; }
    std::string pre = p + "/";
    for (auto* m : {&base_}) {
        m->erase(p);
        for (auto i = m->lower_bound(pre); i != m->end() && i->first.compare(0, pre.size(), pre) == 0;) i = m->erase(i);
    }
    remote_.erase(p);
    for (auto i = remote_.lower_bound(pre); i != remote_.end() && i->first.compare(0, pre.size(), pre) == 0;) i = remote_.erase(i);
    dirty_ = true; stats.delRemote++;
    Event("✕ NAS 휴지통으로", p);
    return true;
}

bool Engine::DeleteLocal(const std::string& p) {
    std::wstring a = Abs(cfg_.localRoot, p).substr(4);   // 휴지통 기능은 \\?\ 를 못 받음
    a.push_back(L'\0');
    SHFILEOPSTRUCTW op{};
    op.wFunc = FO_DELETE; op.pFrom = a.c_str();
    op.fFlags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI;
    int rc = SHFileOperationW(&op);
    if (rc != 0 || op.fAnyOperationsAborted) { stats.errors++; Event("로컬 삭제 실패", p, "코드 " + std::to_string(rc)); return false; }
    std::string pre = p + "/";
    for (auto* m : {&base_}) {
        m->erase(p);
        for (auto i = m->lower_bound(pre); i != m->end() && i->first.compare(0, pre.size(), pre) == 0;) i = m->erase(i);
    }
    local_.erase(p);
    for (auto i = local_.lower_bound(pre); i != local_.end() && i->first.compare(0, pre.size(), pre) == 0;) i = local_.erase(i);
    dirty_ = true; stats.delLocal++;
    Event("✕ PC 휴지통으로", p);
    return true;
}

bool Engine::ForgetMoved(const std::string& rel) {
    std::lock_guard<std::recursive_mutex> g(mu_);
    bool ok = GetFileAttributesW(Abs(cfg_.localRoot, rel).c_str()) == INVALID_FILE_ATTRIBUTES || DeleteLocal(rel);   // 자리표시는 휴지통으로 보내도 받지 않음
    std::string pre = rel + "/";
    remote_.erase(rel);
    for (auto i = remote_.lower_bound(pre); i != remote_.end() && i->first.compare(0, pre.size(), pre) == 0;) i = remote_.erase(i);
    base_.erase(rel);
    for (auto i = base_.lower_bound(pre); i != base_.end() && i->first.compare(0, pre.size(), pre) == 0;) i = base_.erase(i);
    dirty_ = true;
    Kick();
    return ok;
}

bool Engine::KeepBothLocal(const std::string& p) {
    std::lock_guard<std::recursive_mutex> g(mu_);
    SYSTEMTIME st; GetLocalTime(&st);
    char tag[96]; snprintf(tag, sizeof tag, " (%s 충돌 %04d-%02d-%02d %02d%02d)", U(cfg_.pcName).c_str(), st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute);
    std::string leaf = Leaf(p), par = Parent(p);
    auto dot = leaf.rfind('.');
    std::string nn = (dot == std::string::npos || dot == 0) ? leaf + tag : leaf.substr(0, dot) + tag + leaf.substr(dot);
    std::string np = par.empty() ? nn : par + "/" + nn;
    if (!MoveFileExW(Abs(cfg_.localRoot, p).c_str(), Abs(cfg_.localRoot, np).c_str(), 0)) {
        stats.errors++; Event("충돌 사본 만들기 실패", p); return false;
    }
    base_.erase(p); local_.erase(p); dirty_ = true;
    stats.conflicts++;
    Event("⚠ 충돌 — 둘 다 남김", p, "PC 판 → " + nn);
    NoteConflict(np);
    return true;
}

// ---------- 내용 비교 (SHA-256: PC 는 직접 계산, NAS 는 서버가 계산) ----------
static std::string LocalSha256(const std::wstring& file) {
    BCRYPT_ALG_HANDLE alg = nullptr; BCRYPT_HASH_HANDLE h = nullptr;
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0) return {};
    std::string out;
    HANDLE f = CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
    if (f != INVALID_HANDLE_VALUE && BCryptCreateHash(alg, &h, nullptr, 0, nullptr, 0, 0) == 0) {
        std::vector<unsigned char> buf(1 << 20); DWORD got;
        while (ReadFile(f, buf.data(), (DWORD)buf.size(), &got, nullptr) && got) BCryptHashData(h, buf.data(), got, 0);
        unsigned char d[32];
        if (BCryptFinishHash(h, d, 32, 0) == 0) { char hx[65]; for (int i = 0; i < 32; i++) sprintf(hx + i * 2, "%02x", d[i]); out = hx; }
        BCryptDestroyHash(h);
    }
    if (f != INVALID_HANDLE_VALUE) CloseHandle(f);
    BCryptCloseAlgorithmProvider(alg, 0);
    return out;
}

int Engine::SameContent(const std::string& p) {
    std::string lh = LocalSha256(Abs(cfg_.localRoot, p));
    if (lh.empty() || stop_) return -1;
    Resp r = http_.Req(L"GET", "/v1/hash", {{"root", cfg_.name}, {"path", p}}, nullptr, 600);
    // 확인을 못 했으면(끊김·종료 중) '다르다'로 보지 않고 다음 차례에 다시 — 예전에는 여기서 충돌 사본을 만들어 큰 파일을 통째로 다시 올렸음
    if (!r.ok()) return -1;
    json j = json::parse(r.body, nullptr, false);
    if (j.is_discarded()) return -1;
    std::string rh = j.value("sha256", std::string());
    if (rh.empty()) return -1;
    return rh == lh ? 1 : 0;
}

// PC 판이 깨졌나: 끝 64 KB 가 모두 0 인데 NAS 판의 같은 자리는 아님 (1 MB 넘는 파일만 — 작은 파일은 끝이 0 인 형식이 흔함)
//   덜 받은 파일이 크기만 맞춰진 채 남으면 앞부분은 맞고 뒷부분은 0 으로 참 → 내용 비교로는 '다름'이라 예전에는 충돌 사본으로 올렸음
int Engine::BrokenLocal(const std::string& p, int64_t size) {
    const int64_t K = 64 * 1024;
    if (size < (1LL << 20)) return 0;
    std::string tail(K, '\0');
    HANDLE f = CreateFileW(Abs(cfg_.localRoot, p).c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return -1;
    LARGE_INTEGER at; at.QuadPart = size - K;
    DWORD got = 0;
    bool rd = SetFilePointerEx(f, at, nullptr, FILE_BEGIN) && ReadFile(f, tail.data(), (DWORD)K, &got, nullptr) && got == (DWORD)K;
    CloseHandle(f);
    if (!rd) return -1;
    auto zero = [](const std::string& s) { for (char c : s) if (c) return false; return true; };
    if (!zero(tail)) return 0;
    std::string rt;
    Resp r = http_.Range("/v1/file", {{"root", cfg_.name}, {"path", p}}, size - K, K, rt);
    if (!r.ok() || (int64_t)rt.size() != K) return -1;
    return zero(rt) ? 0 : 1;   // 둘 다 끝이 0 이면 원래 그런 파일
}

// ---------- 이름 바꾸기 알아채기 (지우고 새로 올리는 대신 이름만 바꿈) ----------
bool Engine::DetectRenames() {
    // 예전에는 차례마다 양쪽 전체(drive 는 40만 개)로 파일 번호 색인을 만들고(이름 패턴 검사 포함) base 전체를 정렬해 1~2초 걸렸음.
    // 이름이 바뀐 것은 반드시 'base 에 있는데 한쪽에서 사라진 것'(후보)과 'base 에 없는 새 경로'(짝)이므로 그것만 봄.
    // 세 목록은 같은 순서로 정렬된 map 이라 한 번 나란히 훑으면 됨 (찾기 없이).
    std::vector<std::string> keys;
    bool needL = false, needR = false;
    {
        auto li = local_.begin(); auto ri = remote_.begin();
        for (auto& [p, b] : base_) {
            while (li != local_.end() && li->first < p) ++li;
            while (ri != remote_.end() && ri->first < p) ++ri;
            bool lHere = li != local_.end() && li->first == p, rHere = ri != remote_.end() && ri->first == p;
            if (!lHere && rHere && b.lid) { keys.push_back(p); needL = true; }        // PC 에서 사라짐 → PC 의 새 경로에서 짝을 찾음
            else if (lHere && !rHere && b.ri) { keys.push_back(p); needR = true; }    // NAS 에서 사라짐 → NAS 의 새 경로에서 짝을 찾음
        }
    }
    if (keys.empty()) return false;
    bool any = false;
    std::unordered_map<uint64_t, std::string> lById, rByIno;   // base 에 없는 새 경로만
    if (needL) {
        auto bi = base_.begin();
        for (auto& [p, l] : local_) {
            while (bi != base_.end() && bi->first < p) ++bi;
            if (bi != base_.end() && bi->first == p) continue;
            if (l.id && !Excluded(p, l.d, l.s)) lById[l.id] = p;
        }
    }
    if (needR) {
        auto bi = base_.begin();
        for (auto& [p, r] : remote_) {
            while (bi != base_.end() && bi->first < p) ++bi;
            if (bi != base_.end() && bi->first == p) continue;
            if (r.i && !Excluded(p, r.d, r.s)) rByIno[r.i] = p;
        }
    }
    if (lById.empty() && rByIno.empty()) return false;   // 짝이 될 새 경로가 없음 (지우기만 함)
    std::sort(keys.begin(), keys.end(), [](auto& a, auto& b) { return Depth(a) < Depth(b) || (Depth(a) == Depth(b) && a < b); });
    for (auto& p : keys) {
        auto bi = base_.find(p);
        if (bi == base_.end()) continue;   // 앞에서 폴더째 옮겨짐
        if (inflight_.count(p) || std::any_of(inflight_.begin(), inflight_.end(), [&](const std::string& f) { return Under(f, p); })) continue;
        BaseE b = bi->second;
        bool lHere = local_.count(p), rHere = remote_.count(p);
        // PC 에서 이름이 바뀜 → NAS 도 이름만 바꿈
        if (!lHere && rHere && b.lid) {
            auto f = lById.find(b.lid);
            if (f != lById.end() && !base_.count(f->second) && !remote_.count(f->second)) {
                const LocalE& l = local_[f->second];
                const RemoteE& r = remote_[p];
                bool sameL = l.d == b.d && (b.d || (l.s == b.ls && SameTime(l.m, b.lm)));
                bool sameR = r.d == b.d && (b.d || (r.s == b.rs && r.m == b.rm));
                if (sameL && sameR) {
                    std::string q = f->second;
                    Resp res = http_.Req(L"POST", "/v1/move", {{"root", cfg_.name}, {"from", p}, {"to", q}, {"if", b.d ? std::string("dir") : CondB(b)}});
                    if (res.ok()) {
                        Rekey(p, q);
                        InSync(q, b.d); cloud::RefreshShell(Abs(cfg_.localRoot, q));   // 이름을 바꾸면 Windows 가 '맞춰짐' 표시를 지움 → 다시 붙임 (안 그러면 탐색기에 계속 '보류 중')
                        json j = json::parse(res.body, nullptr, false);
                        if (!j.is_discarded()) { RemoteE nr = FromJ(j); remote_[q] = nr; base_[q].ri = nr.i; base_[q].rm = nr.m; }
                        stats.moved++; any = true;
                        Event("↑ 이름 바꿈", p, "→ " + q);
                    } else Event("이름 바꾸기 실패", p, res.error + " " + std::to_string(res.status));
                    continue;
                }
            }
        }
        // NAS 에서 이름이 바뀜 → PC 도 이름만 바꿈
        if (lHere && !rHere && b.ri) {
            auto f = rByIno.find(b.ri);
            if (f != rByIno.end() && !base_.count(f->second) && !local_.count(f->second) && !BadWinName(f->second)) {
                const LocalE& l = local_[p];
                const RemoteE& r = remote_[f->second];
                bool sameL = l.d == b.d && (b.d || (l.s == b.ls && SameTime(l.m, b.lm)));
                bool sameR = r.d == b.d && (b.d || (r.s == b.rs && r.m == b.rm));
                if (sameL && sameR) {
                    std::string q = f->second, par = Parent(q);
                    if (!par.empty()) SHCreateDirectoryExW(nullptr, Abs(cfg_.localRoot, par).c_str() + 4, nullptr);
                    if (MoveFileExW(Abs(cfg_.localRoot, p).c_str(), Abs(cfg_.localRoot, q).c_str(), 0)) {
                        Rekey(p, q);
                        InSync(q, b.d); cloud::RefreshShell(Abs(cfg_.localRoot, q));
                        stats.moved++; any = true;
                        Event("↓ 이름 바꿈", p, "→ " + q);
                    } else Event("이름 바꾸기 실패", p, "코드 " + std::to_string(GetLastError()));
                }
            }
        }
    }
    return any;
}

// ---------- 동기화 작업 사이 옮기기 ----------
//   PC 에서 한 작업 폴더(예: C:\BACK)의 항목을 다른 작업 폴더(C:\DOWNLOAD)로 옮기면, 올리기·지우기 대신 NAS 안에서 바로 옮김
//   (/v1/move ... toRoot). 같은 볼륨 안에서 옮기면 Windows 파일 번호(FileId)가 그대로라 '사라진 것 = 새로 생긴 것'을 알아봄.
//   - 받는 쪽(새로 생긴 쪽): 다른 작업이 맞춰 둔 파일 번호이고 그쪽 자리에서는 사라졌으면 → NAS 에서 옮기고 바로 '맞춰짐'
//   - 보내는 쪽(사라진 쪽): 그 파일 번호가 지금 다른 작업 폴더 안에 있으면 → 받는 쪽이 옮길 때까지 NAS 에서 지우지 않고 기다림 (최대 1분)
//   전에는 지우고(NAS 휴지통) 새로 올렸음 → 온라인 전용(자리표시) 파일은 내용이 PC 에 없어 올리지 못하고 막혔음.
static std::mutex g_engMu;            // g_engs 보호 (이것을 잡은 채로는 다른 엔진의 idxMu_ 만 잡음 — mu_ 는 잡지 않음)
static std::vector<Engine*> g_engs;   // 지금 돌고 있는 연결들

void Engine::RebuildIdx() {
    std::unordered_map<uint64_t, std::pair<std::string, BaseE>> m;
    m.reserve(base_.size());
    for (auto& [p, b] : base_) if (b.lid) m.emplace(b.lid, std::make_pair(p, b));
    std::lock_guard<std::mutex> g(idxMu_);
    lidIdx_.swap(m);
}

bool Engine::OwnsMissing(uint64_t lid, std::string* p, BaseE* b) {
    std::pair<std::string, BaseE> e;
    {
        std::lock_guard<std::mutex> g(idxMu_);
        auto f = lidIdx_.find(lid);
        if (f == lidIdx_.end()) return false;
        e = f->second;
    }
    if (GetFileAttributesW(Abs(cfg_.localRoot, e.first).c_str()) != INVALID_FILE_ATTRIBUTES) return false;   // 원래 자리에도 있음 → 옮긴 것 아님
    DWORD er = GetLastError();
    if (er != ERROR_FILE_NOT_FOUND && er != ERROR_PATH_NOT_FOUND) return false;
    *p = e.first; *b = e.second;
    return true;
}

bool Engine::MovedElsewhere(uint64_t lid) {
    std::wstring root = PlainRoot(cfg_.localRoot);
    HANDLE hr = CreateFileW(root.c_str(), FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (hr == INVALID_HANDLE_VALUE) return false;
    FILE_ID_DESCRIPTOR d{}; d.dwSize = sizeof d; d.Type = FileIdType; d.FileId.QuadPart = (LONGLONG)lid;
    // 속성만 읽음 (자리표시 파일을 받지 않게 재분석 지점 그대로)
    HANDLE h = OpenFileById(hr, &d, FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT);
    CloseHandle(hr);
    if (h == INVALID_HANDLE_VALUE) return false;
    std::vector<wchar_t> buf(4096);
    DWORD n = GetFinalPathNameByHandleW(h, buf.data(), (DWORD)buf.size(), FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    CloseHandle(h);
    if (!n || n >= buf.size()) return false;
    std::wstring path(buf.data(), n);
    if (path.compare(0, 4, L"\\\\?\\") == 0) path = path.substr(4);
    std::lock_guard<std::mutex> g(g_engMu);
    for (Engine* e : g_engs) {
        if (e == this) continue;
        std::wstring r = PlainRoot(e->cfg().localRoot);
        if (path.size() > r.size() && _wcsnicmp(path.c_str(), r.c_str(), r.size()) == 0 && path[r.size()] == L'\\') return true;
    }
    return false;
}

bool Engine::CrossMoveIn(const std::string& p, const LocalE& l) {
    if (!l.id) return false;
    std::string src, sp; BaseE sb;
    {
        std::lock_guard<std::mutex> g(g_engMu);
        for (Engine* e : g_engs)
            if (e != this && !e->stop_ && e->OwnsMissing(l.id, &sp, &sb)) { src = e->cfg().name; break; }
    }
    if (src.empty()) return false;
    // 서버가 다른 연결로 옮기기(toRoot)를 알아야 함 (0.6.3 부터 — 공유 폴더가 달라도 reflink 복사로). 예전 서버는 toRoot 를 모르고 같은 연결 안에서 옮겨 버림
    static std::atomic<int> s_srvOK{-1};
    if (s_srvOK < 0) {
        Resp v = http_.Req(L"GET", "/v1/ping", {});
        int a = 0, b2 = 0, c = 0;
        if (v.ok() && sscanf_s(v.body.c_str(), "mydrived %d.%d.%d", &a, &b2, &c) == 3) s_srvOK = (a * 10000 + b2 * 100 + c) >= 603 ? 1 : 0;
        else if (v.ok()) s_srvOK = 0;
    }
    if (s_srvOK != 1) return false;
    // 옮긴 뒤 고친 파일은 평소대로 (NAS 에 있는 것은 고치기 전 판)
    if (l.d != sb.d || (!l.d && (l.s != sb.ls || !SameTime(l.m, sb.lm)))) return false;
    Resp res = http_.Req(L"POST", "/v1/move", {{"root", src}, {"from", sp}, {"to", p}, {"toRoot", cfg_.name}, {"if", sb.d ? std::string("dir") : CondB(sb)}}, nullptr, 900);   // 큰 폴더는 NAS 에서 복사가 걸릴 수 있음
    if (!res.ok()) {
        Event("작업 사이 옮기기 안 됨", p, src + ": " + sp + " — " + (res.error.empty() ? std::to_string(res.status) : res.error) + " · 평소대로 맞춤");
        return false;
    }
    json j = json::parse(res.body, nullptr, false);
    RemoteE nr = j.is_discarded() ? RemoteE{} : FromJ(j);
    nr.d = sb.d;
    if (j.is_discarded()) { nr.s = sb.rs; nr.m = sb.rm; nr.i = sb.ri; }
    remote_[p] = nr;
    SetBase(p, l, nr);
    if (l.d) xAdopt_[p] = GetTickCount64() + 180000;   // 아래 항목: NAS 목록이 따라오면 받기·올리기 없이 맞춤
    InSync(p, l.d); cloud::RefreshShell(Abs(cfg_.localRoot, p));
    stats.moved++;
    Event("⇄ 작업 사이 옮김", p, "← " + src + ": " + sp + " · NAS 안에서 바로");
    std::lock_guard<std::mutex> g(g_engMu);
    for (Engine* e : g_engs) if (e != this && e->cfg().name == src) e->Kick();   // 보내는 쪽이 마지막 상태 기록을 정리하게
    return true;
}

// ---------- 한 번 맞추기 ----------
bool Engine::RunPass() {
    std::unique_lock<std::recursive_mutex> L(mu_);   // 작은 파일을 옮기는 동안만 풀어 줌
    retryAt_ = 0;
    uint64_t T0 = GetTickCount64(); prof_[0] = prof_[1] = prof_[2] = prof_[3] = 0;
    if (!RefreshRemote()) return false;
    // 다른 연결이 '옮겨진 것 찾기'에 쓸 NAS 파일 색인 (요청이 있을 때만, NAS 목록이 바뀌었을 때만 다시 만듦)
    if (rkWant_ > GetTickCount64() && rkSeq_ != seq_ && GetTickCount64() - rkAt_ > 30000) {   // 많이 바뀌는 중에도 30초에 한 번만
        rkAt_ = GetTickCount64();
        std::unordered_map<std::string, std::string> ix; ix.reserve(remote_.size());
        for (auto& [p, r] : remote_) if (!r.d) ix.emplace(MoveKey(Leaf(p), r.s, r.m), p);
        { std::lock_guard<std::mutex> g(rkMu_); rkIdx_.swap(ix); }
        rkSeq_ = seq_;
    }
    prof_[0] = GetTickCount64() - T0;
    if (!ScanLocal()) return false;
    bool any = DetectRenames();
    prof_[1] = GetTickCount64() - T0;

    enum Act { None, Up, Down, MkR, MkL, DelR, DelL, Conflict, SyncBase, DropBase, ReplR, ReplL, Compare };   // Repl: 파일↔폴더로 종류가 바뀜
    struct Item { std::string p; Act a; };
    std::vector<Item> items;

    // 세 목록의 모든 경로를 정렬된 순서로 한 번씩 (세 map 이 같은 순서라 나란히 훑어 합침).
    //   예전에는 차례마다 set 에 58만 번 넣었음 (drive). 아래에서 세 목록을 고칠 수 있어 경로는 따로 복사해 둠.
    std::vector<std::string> all;
    all.reserve(std::max({local_.size(), remote_.size(), base_.size()}) + 64);
    bool anyNewR = false;   // NAS 에만 새로 생긴 항목이 있나 (대소문자 겹침 검사가 필요한 때)
    {
        auto li = local_.begin(); auto ri = remote_.begin(); auto bi = base_.begin();
        while (li != local_.end() || ri != remote_.end() || bi != base_.end()) {
            const std::string* m = nullptr;
            if (li != local_.end()) m = &li->first;
            if (ri != remote_.end() && (!m || ri->first < *m)) m = &ri->first;
            if (bi != base_.end() && (!m || bi->first < *m)) m = &bi->first;
            bool hl = li != local_.end() && li->first == *m, hr = ri != remote_.end() && ri->first == *m, hb = bi != base_.end() && bi->first == *m;
            if (hr && !hl && !hb) anyNewR = true;
            all.push_back(*m);
            if (hl) ++li;
            if (hr) ++ri;
            if (hb) ++bi;
        }
    }

    std::set<std::string> exclUnderL, exclUnderR;   // 필터로 뺀 파일이 들어 있는 폴더 (통째로 지우지 않음)
    std::map<std::wstring, std::string> caseWinner;  // 소문자 이름 → 처음 나온 NAS 경로 (all 은 정렬돼 있어 늘 같은 것이 이김)
    bool caseBuilt = false;   // 처음 필요할 때(NAS 에만 새로 생긴 항목) 한 번만 만듦 — 예전에는 차례마다 39만 개를 바꿔 넣었음
    auto buildCase = [&] {
        if (caseBuilt) return;
        caseBuilt = true;
        for (auto& [p, l] : local_) { std::wstring lw = W(p); CharLowerBuffW(lw.data(), (DWORD)lw.size()); caseWinner.emplace(lw, p); }   // PC 에 이미 있는 이름이 우선
        for (auto& [p, b] : base_) { std::wstring lw = W(p); CharLowerBuffW(lw.data(), (DWORD)lw.size()); caseWinner.emplace(lw, p); }
    };
    (void)anyNewR;
    std::vector<std::string> hydr, dehy, conv, unpinDirs, autoFree;   // 탐색기 연동: 항상 유지 → 받기 / 공간 확보 / 자리표시로 바꾸기 / 표시 지울 폴더 / 오래 안 쓴 파일
    int64_t nowNs; { FILETIME ft; GetSystemTimeAsFileTime(&ft); nowNs = FtToNs(ft); }
    // 자동 공간 확보는 10분에 한 번만 살펴봄
    int64_t autoFreeNs = 0;
    static const int64_t testSec = [] { wchar_t v[32]; return GetEnvironmentVariableW(L"MYDRIVE_AUTOFREE_TEST_SEC", v, 32) ? (int64_t)_wtoi64(v) : 0; }();   // 시험용: 일 대신 초, 바로 살핌
    if (cloudOn_ && !unregister_ && testSec > 0) autoFreeNs = testSec * 1000000000LL;
    else if (cloudOn_ && !unregister_ && cfg_.autoFreeDays > 0 && GetTickCount64() - lastAutoFree_ > 600000) {
        autoFreeNs = (int64_t)cfg_.autoFreeDays * 86400LL * 1000000000LL;
        lastAutoFree_ = GetTickCount64();
    }
    std::map<std::string, Canceled> canceled; { std::lock_guard<std::mutex> g(xMu_); canceled = canceled_; }
    std::set<std::string> flightAnc;                 // 큰 파일 통로에서 옮기는 중인 파일의 위 폴더
    for (auto& f : inflight_) for (std::string q = Parent(f); !q.empty(); q = Parent(q)) flightAnc.insert(q);
    // 동기화 작업 사이 옮기기: 이번 차례에 기다리는 것(xOutNow) / 다른 작업에 없다고 확인한 것(xOutNo, 그 아래는 다시 확인 안 함)
    std::set<std::string> xOutNow, xOutNo;
    for (auto x = xAdopt_.begin(); x != xAdopt_.end();) x = GetTickCount64() > x->second ? xAdopt_.erase(x) : std::next(x);
    // 맞춰져 있는 항목: 탐색기에서 고른 '항상 유지'/'공간 확보'를 따름
    auto syncedWork = [&](const std::string& p, const LocalE* l, const RemoteE* r) {
        bool reparse = (l->a & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
        if (!l->d && r) {
            bool dehyd = cloud::IsDehydrated(l->a);
            uint8_t pin = unregister_ ? 1 : l->pin;   // 연동을 끄는 중이면 모두 받음
            if (pin == 1 && dehyd) hydr.push_back(p);
            else if (pin == 2 && !dehyd && reparse) dehy.push_back(p);
            // 오래 안 연 파일 → 온라인 전용 (항상 유지가 아닌 것만, 열 때 다시 받음)
            else if (pin == 0 && autoFreeNs > 0 && !dehyd && reparse && autoFree.size() < 1000 &&
                     nowNs - std::max({l->at, l->m, OpenedAt(p)}) > autoFreeNs) autoFree.push_back(p);
        }
        if (l->d && cloud::IsUnpinned(l->a)) unpinDirs.push_back(p);
        if (!reparse && !unregister_ && conv.size() < 2000) {
            bool pend; { std::lock_guard<std::mutex> g(spMu_); pend = syncPending_.count(p) > 0; }
            if (!pend) conv.push_back(p);   // 전부터 있던 보통 파일 → 자리표시로 바꿔 상태 아이콘이 보이게
        }
    };
    // 빠른 길: PC·NAS·마지막 기록 세 곳에 다 있고 지난번 맞춘 그대로인 항목 (큰 폴더에서는 거의 전부)은
    //   이름·제외 규칙 검사를 다시 하지 않음 — 파일마다 경로를 잘라 글자 변환·패턴 비교를 해서 drive 에서 차례마다 2초 넘게 걸렸음.
    //   같은 엔진 안에서는 규칙이 바뀌지 않음 (설정을 바꾸면 엔진을 새로 띄움). 그래도 처음 한 번과 1시간마다는 전부 검사.
    uint64_t nowT = GetTickCount64();
    bool fastOK = checkedOnce_ && nowT - lastFullCheck_ < 3600000;
    if (!fastOK) lastFullCheck_ = nowT;
    int nFast = 0;
    for (auto& p : all) {
        auto li = local_.find(p); auto ri = remote_.find(p); auto bi = base_.find(p);
        const LocalE* l = li == local_.end() ? nullptr : &li->second;
        const RemoteE* r = ri == remote_.end() ? nullptr : &ri->second;
        const BaseE* b = bi == base_.end() ? nullptr : &bi->second;
        if (fastOK && l && r && b && l->d == b->d && r->d == b->d &&
            (b->d || (l->s == b->ls && SameTime(l->m, b->lm) && r->s == b->rs && r->m == b->rm && l->s == r->s)) &&
            !inflight_.count(p) && !flightAnc.count(p)) {
            nFast++;
            if (cloudOn_) syncedWork(p, l, r);
            continue;
        }
        if (SkipRemoteAnc(p)) continue;   // 건너뛰는 폴더 안: 알림 없이 조용히 넘어감
        if (r && (BadWinName(p) || SkipRemoteRel(p))) {
            if (!l && !b) { if (warned_.insert(p).second) Event("건너뜀", p, BadWinName(p) ? "Windows 에서 쓸 수 없는 이름" : "임시·잠금 파일이라 맞추지 않음 (.tmp · ~$ · desktop.ini · Thumbs.db)"); }
            continue;
        }
        // NAS 에 대소문자만 다른 이름이 둘 이상 (Windows 에서는 같은 이름): 정렬해서 첫 것만 맞추고 나머지는 건너뜀
        if (r && !l && !b) {
            buildCase();
            std::wstring lw = W(p); CharLowerBuffW(lw.data(), (DWORD)lw.size());
            auto [wi, fresh] = caseWinner.emplace(lw, p);
            if (!fresh && wi->second != p) {
                if (warned_.insert(p).second) Event("건너뜀", p, "대소문자만 다른 같은 이름이 NAS 에 있음: " + wi->second);
                continue;
            }
        }
        // 큰 파일을 옮기는 중인 자리와 그 위 폴더는 끝날 때까지 건드리지 않음
        if (inflight_.count(p) || flightAnc.count(p)) { uint64_t t = GetTickCount64() + 2000; if (!retryAt_ || t < retryAt_) retryAt_ = t; continue; }
        // 필터로 뺀 항목: 어느 쪽도 건드리지 않음 (마지막 상태 기록만 잊음 → 나중에 다시 넣으면 내용을 비교해 맞춤)
        if ((l && Excluded(p, l->d, l->s)) || (r && Excluded(p, r->d, r->s)) || (!l && !r && b && Excluded(p, b->d, std::max(b->ls, b->rs)))) {
            if (b) { base_.erase(p); dirty_ = true; }
            for (std::string q = Parent(p); !q.empty(); q = Parent(q)) { if (l) exclUnderL.insert(q); if (r) exclUnderR.insert(q); }
            continue;
        }
        // 동기화 작업 사이 옮기기 — 받는 쪽: 새로 생긴 항목이 다른 작업에서 옮겨 온 것이면 NAS 안에서 옮김
        if (!b && l) {
            bool underX = false;
            for (auto& [xp, until] : xAdopt_) if (p != xp && Under(p, xp)) { underX = true; break; }
            if (underX) {   // 방금 옮겨 온 폴더 안: NAS 목록이 따라오면 그대로 맞춤, 아직이면 잠깐 기다림
                if (r && r->d == l->d && (l->d || r->s == l->s)) { SetBase(p, *l, *r); InSync(p, l->d); continue; }
                if (!r) { uint64_t t = GetTickCount64() + 1500; if (!retryAt_ || t < retryAt_) retryAt_ = t; continue; }
            } else if (!r && CrossMoveIn(p, *l)) { any = true; continue; }
        }
        // 동기화 작업 사이 옮기기 — 보내는 쪽: 사라진 항목이 지금 다른 작업 폴더 안에 있으면, 그쪽이 NAS 안에서 옮길 때까지 지우지 않음
        if (b && !l && r && b->lid && !r->d == !b->d) {
            bool wait = false;
            for (std::string q = Parent(p); !q.empty() && !wait; q = Parent(q)) if (xOutNow.count(q)) wait = true;   // 위 폴더째 옮겨 감
            bool parentGone = false;
            for (std::string q = Parent(p); !q.empty() && !parentGone; q = Parent(q)) if (xOutNo.count(q)) parentGone = true;
            if (!wait && !parentGone) { if (MovedElsewhere(b->lid)) wait = true; else xOutNo.insert(p); }
            if (wait) {
                auto [w, fresh] = xOutWait_.emplace(p, GetTickCount64());
                if (GetTickCount64() - w->second < 60000) {
                    xOutNow.insert(p);
                    uint64_t t = GetTickCount64() + 2000; if (!retryAt_ || t < retryAt_) retryAt_ = t;
                    continue;
                }
            }
        }
        bool lc, rc;   // 바뀌었나
        if (!b) { lc = l != nullptr; rc = r != nullptr; }
        else {
            lc = !l || l->d != b->d || (!b->d && (l->s != b->ls || !SameTime(l->m, b->lm)));
            rc = !r || r->d != b->d || (!b->d && (r->s != b->rs || r->m != b->rm));
        }
        // 온라인 전용(내용이 PC 에 없는) 파일은 PC 쪽에서 내용이 바뀔 수 없음 → 'PC 에서 바뀜'으로 보지 않음.
        //   안 그러면 내용 비교·올리기·충돌 사본을 하려고 Mydrive 가 스스로 그 파일을 통째로 내려받음 (8GB 파일을 받은 적이 있음).
        //   NAS 에 있으면 자리표시만 NAS 에 맞춤. NAS 에도 기록에도 없으면(올릴 내용이 없음) 건드리지 않고 알리기만 함.
        //   내용을 일부만 받은 자리표시도 같음: 빈 부분은 열 때 NAS 에서 받아 채우는 것이라 PC 에서 고친 판이 아님.
        //   예전에는 이런 파일을 'PC 에서 바뀜'으로 보고 충돌 사본으로 올려, 끝부분이 0 으로 찬 깨진 판(1 GB 넘는 pptx·pdf)이 NAS 에 생겼음.
        bool partial = cloudOn_ && lc && l && !l->d && (l->a & FILE_ATTRIBUTE_REPARSE_POINT) && !cloud::IsDehydrated(l->a) && (!b || !b->d) &&
                       cloud::IsPartial(Abs(cfg_.localRoot, p));
        if (partial && warned_.insert("partial:" + p).second) Event("덜 받은 파일", p, "온라인 전용 파일의 내용을 일부만 받은 상태 — PC 에서 바뀐 것으로 보지 않고 NAS 판에 맞춤");
        if (cloudOn_ && l && !l->d && (l->a & FILE_ATTRIBUTE_REPARSE_POINT) && (cloud::IsDehydrated(l->a) || partial) && (!b || !b->d)) {
            if (!b && !r) { if (warned_.insert(p).second) Event("건너뜀", p, "온라인 전용 파일인데 NAS 에 없음 — 받을 내용이 없어 그대로 둠"); continue; }
            lc = false;
            if (r && !r->d && (!b || l->s != r->s)) rc = true;   // 자리표시의 크기가 NAS 와 다르면 자리표시를 고침
        }
        Act a = None;
        if (!lc && !rc) {
            if (b && !l && !r) a = DropBase;
        } else if (lc && !rc) {
            if (l) a = (r && r->d != l->d) ? ReplR : l->d ? (r ? SyncBase : MkR) : Up;
            else a = r ? DelR : DropBase;
        } else if (!lc && rc) {
            if (r) a = (l && l->d != r->d) ? ReplL : r->d ? (l ? SyncBase : MkL) : Down;
            else a = l ? DelL : DropBase;
        } else {   // 양쪽 다 바뀜
            if (!l && !r) a = DropBase;
            else if (l && r && l->d && r->d) a = SyncBase;
            else if (l && r && !l->d && !r->d && l->s == r->s) a = Compare;   // 크기가 같으면 내용(SHA-256)을 비교: 같으면 그대로, 다르면 둘 다 남김
            else if (l && r) a = Conflict;
            else if (l) a = l->d ? MkR : Up;        // NAS 에서 지웠지만 PC 에서 고침 → 고친 판을 살림
            else a = r->d ? MkL : Down;             // PC 에서 지웠지만 NAS 에서 고침 → 고친 판을 살림
        }
        // [취소]한 파일: 그 판이 그대로면 이번에도 옮기지 않음 (바뀌었으면 취소 표시를 지우고 평소대로)
        if (a != None && !canceled.empty()) {
            auto ci = canceled.find(p);
            if (ci != canceled.end()) {
                bool same = ci->second.up ? (l && !l->d && l->s == ci->second.s && SameTime(l->m, ci->second.m))
                                          : (r && !r->d && r->s == ci->second.s && r->m == ci->second.m);
                if (same) continue;
                std::lock_guard<std::mutex> g(xMu_); canceled_.erase(p);
            }
        }
        if (a != None) items.push_back({p, a});
        else if (cloudOn_ && l && b) syncedWork(p, l, r);
    }
    checkedOnce_ = true; fastN_ = nFast;

    for (auto x = xOutWait_.begin(); x != xOutWait_.end();) x = xOutNow.count(x->first) ? std::next(x) : xOutWait_.erase(x);
    // 대량 삭제 확인: 지울 항목 수(아래 항목 포함)가 한도를 넘으면 그 방향의 삭제는 멈추고 확인을 기다림
    // [옮긴 것으로 처리]: 다른 동기화 작업으로 옮겨진 것이 확인된 항목은 보류에서 빼고 지움
    if (confirmMoved_.exchange(false)) {
        std::lock_guard<std::mutex> g(cfMu_);
        int n = 0;
        for (auto& [p, v] : movedTo_) { allowDel_.insert(p); if (!v.first.empty()) n++; }
        if (n) Event("→ 옮긴 것으로 처리", cfg_.name, std::to_string(n) + "개 — " + movedConn_ + " 작업으로 옮겨짐, 옛 자리는 휴지통으로");
    }
    {   // 지금 지울 목록에 없는 것은 잊음
        std::set<std::string> dl; for (auto& it : items) if (it.a == DelL) dl.insert(it.p);
        for (auto a = allowDel_.begin(); a != allowDel_.end();) a = dl.count(*a) ? std::next(a) : allowDel_.erase(a);
    }
    int nDelL = 0, nDelR = 0;
    for (auto& it : items) { if (it.a == DelL && !allowDel_.count(it.p)) nDelL++; if (it.a == DelR) nDelR++; }
    // [되살리기]: 한도를 넘은 쪽의 삭제를 하지 않고, 마지막 상태 기록을 잊어 '한쪽에만 있음'으로 다시 맞춤
    //   NAS 삭제 보류(PC에서 사라짐) → PC에 다시 만듦(필요할 때 받기면 자리표시) / PC 삭제 보류(NAS에서 사라짐) → NAS에 다시 올림
    if (restoreDelete_.exchange(false)) {
        bool rr = nDelR > cfg_.massDeleteLimit, rl = nDelL > cfg_.massDeleteLimit;
        int n = 0;
        for (auto& it : items) {
            if (!((it.a == DelR && rr) || (it.a == DelL && rl))) continue;
            base_.erase(it.p); dirty_ = true; n++;
            if (it.a == DelR) { auto ri = remote_.find(it.p); it.a = (ri != remote_.end() && ri->second.d) ? MkL : Down; nDelR--; }
            else { auto li = local_.find(it.p); it.a = (li != local_.end() && li->second.d) ? MkR : Up; nDelL--; }
        }
        if (n) Event("↺ 되살림", cfg_.name, std::to_string(n) + "개 — 지우지 않고 사라진 쪽에 다시 만듦");
    }
    prof_[2] = GetTickCount64() - T0;
    bool holdL = nDelL > cfg_.massDeleteLimit && !confirmDelete_;
    bool holdR = nDelR > cfg_.massDeleteLimit && !confirmDelete_;
    stats.heldDeletes = (holdL ? nDelL : 0) + (holdR ? nDelR : 0);
    // DSM 에서 다른 공유 폴더로 옮긴 경우: NAS 에서 사라진 파일과 이름·크기·수정 시각이 같은 파일이 다른 연결의 NAS 에 있으면 '옮겨진 것'
    bool movedChanged = false;
    if (holdL && (GetTickCount64() - movedAt_ > 15000 || stats.heldDeletes != lastHeld_)) {
        movedAt_ = GetTickCount64();
        std::map<std::string, std::pair<std::string, std::string>> mt;
        std::map<std::string, int> per;
        std::set<std::string> hasMatch, dirty;
        {
            std::lock_guard<std::mutex> g(g_engMu);
            for (Engine* e : g_engs) if (e != this) e->WantKeyIndex();
            for (auto& it : items) {
                if (it.a != DelL || allowDel_.count(it.p)) continue;
                auto bi = base_.find(it.p);
                if (bi == base_.end() || bi->second.d) continue;
                std::string key = MoveKey(Leaf(it.p), bi->second.rs, bi->second.rm), rel, cn;
                for (Engine* e : g_engs) if (e != this && e->LookupKey(key, &rel)) { cn = e->cfg().name; break; }
                if (!cn.empty()) { mt[it.p] = {cn, rel}; per[cn]++; for (std::string q = Parent(it.p); !q.empty(); q = Parent(q)) hasMatch.insert(q); }
                else for (std::string q = Parent(it.p); !q.empty(); q = Parent(q)) dirty.insert(q);
            }
        }
        int n = 0; std::string top; int best = 0;
        for (auto& [c, k] : per) { n += k; if (k > best) { best = k; top = c; } }
        // 폴더: 짝 없는 파일이 하나도 없으면 같이 옮겨진 것 (빈 폴더도 — 옮겨진 파일이 하나라도 있을 때)
        for (auto& it : items) {
            if (it.a != DelL || allowDel_.count(it.p)) continue;
            auto bi = base_.find(it.p);
            if (bi != base_.end() && bi->second.d && !dirty.count(it.p) && (hasMatch.count(it.p) || n > 0)) mt[it.p] = {"", ""};
        }
        std::lock_guard<std::mutex> g(cfMu_);
        movedChanged = n != movedN_;
        movedTo_.swap(mt); movedN_ = n; movedConn_ = top;
        if (movedChanged && n > 0) LOG("[%s] 대량 삭제 보류 중 %d개는 '%s' 작업으로 옮겨진 것으로 보임", cfg_.name.c_str(), n, top.c_str());
    } else if (!holdL) {
        std::lock_guard<std::mutex> g(cfMu_);
        if (movedN_ || !movedTo_.empty()) { movedTo_.clear(); movedN_ = 0; movedConn_.clear(); }
    }
    if ((stats.heldDeletes != lastHeld_ || movedChanged) && stats.heldDeletes > 0) {
        if (stats.heldDeletes != lastHeld_)
            Event("⏸ 대량 삭제 보류", cfg_.name, std::to_string(nDelR) + "개 NAS 삭제 / " + std::to_string(nDelL) + "개 PC 삭제 — [지우기]를 누르면 진행");
        // 어느 폴더에서 사라졌나: 같이 사라진 가장 위 폴더(없으면 파일이 있던 폴더)로 묶어 셈
        {
            std::set<std::string> hs;
            for (auto& it : items) if ((it.a == DelR && holdR) || (it.a == DelL && holdL)) hs.insert(it.p);
            std::map<std::pair<std::string, bool>, int> grp;
            for (auto& it : items) {
                bool r = it.a == DelR;
                if (!((r && holdR) || (it.a == DelL && holdL))) continue;
                std::string rep;
                for (std::string q = Parent(it.p); !q.empty(); q = Parent(q)) if (hs.count(q)) rep = q;
                if (rep.empty()) { auto bi = base_.find(it.p); rep = (bi != base_.end() && bi->second.d) ? it.p : Parent(it.p); }
                grp[{rep, r}]++;
            }
            // 곳이 많으면(예: 폴더마다 파일 하나씩) 가장 깊은 공통 상위 폴더로 묶어 3곳 이하로
            auto lca = [](const std::string& a, const std::string& b) {
                std::string c;
                for (size_t i = 0;;) {
                    size_t ja = a.find('/', i), jb = b.find('/', i);
                    std::string sa = a.substr(i, ja == std::string::npos ? std::string::npos : ja - i), sb = b.substr(i, jb == std::string::npos ? std::string::npos : jb - i);
                    if (sa != sb || sa.empty()) break;
                    c = a.substr(0, ja == std::string::npos ? a.size() : ja);
                    if (ja == std::string::npos || jb == std::string::npos) break;
                    i = ja + 1;
                }
                return c;
            };
            for (int guard = 0; grp.size() > 3 && guard < 5000; guard++) {
                std::string best; bool bestSide = false; int bestDepth = -1;
                std::pair<std::string, bool> prev; bool havePrev = false;
                for (auto& [k, n] : grp) {
                    if (havePrev && prev.second == k.second) {
                        std::string c = lca(prev.first, k.first);
                        int d = c.empty() ? 0 : Depth(c) + 1;
                        if (d > bestDepth) { bestDepth = d; best = c; bestSide = k.second; }
                    }
                    prev = k; havePrev = true;
                }
                if (bestDepth < 0) break;
                int sum = 0;
                for (auto g = grp.begin(); g != grp.end();) {
                    if (g->first.second == bestSide && (best.empty() || g->first.first == best || Under(g->first.first, best))) { sum += g->second; g = grp.erase(g); }
                    else ++g;
                }
                grp[{best, bestSide}] += sum;
            }
            std::vector<HeldDir> v;
            for (auto& [k, n] : grp) v.push_back({k.first, n, k.second});
            std::sort(v.begin(), v.end(), [](const HeldDir& a, const HeldDir& b) { return a.n > b.n; });
            std::lock_guard<std::mutex> g(cfMu_);
            heldGroups_ = (int)v.size();
            if (v.size() > 3) v.resize(3);
            heldDirs_ = v;
        }
        // 무엇이 지워지려는지 파일로 남김 ([목록 보기])
        if (FILE* fp = _wfopen(HeldFile().c_str(), L"wb")) {
            fputs("\xEF\xBB\xBF", fp);
            SYSTEMTIME st; GetLocalTime(&st);
            fprintf(fp, "Mydrive · %s · 대량 삭제 보류 (%04d-%02d-%02d %02d:%02d)\r\n\r\n", cfg_.name.c_str(), st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute);
            for (int pass = 0; pass < 2; pass++) {
                bool r = pass == 0;
                if (!(r ? holdR : holdL)) continue;
                fprintf(fp, r ? "[이 PC에서 사라짐 → [지우기]를 누르면 NAS에서도 지움] %d개\r\n" : "[NAS에서 사라짐 → [지우기]를 누르면 이 PC에서도 지움] %d개\r\n", r ? nDelR : nDelL);
                std::map<std::string, std::pair<std::string, std::string>> mv;
                { std::lock_guard<std::mutex> g(cfMu_); if (!r) mv = movedTo_; }
                for (auto& it : items) {
                    if (it.a != (r ? DelR : DelL) || (!r && allowDel_.count(it.p))) continue;
                    auto bi = base_.find(it.p); bool d = bi != base_.end() && bi->second.d;
                    std::string ap = U(Abs(cfg_.localRoot, it.p));
                    if (ap.rfind("\\\\?\\", 0) == 0) ap.erase(0, 4);
                    auto m = mv.find(it.p);
                    if (m != mv.end() && !m->second.first.empty()) fprintf(fp, "  %s    → 옮겨짐: %s/%s\r\n", ap.c_str(), m->second.first.c_str(), m->second.second.c_str());
                    else if (m != mv.end()) fprintf(fp, "  %s\\    → 폴더째 옮겨짐\r\n", ap.c_str());
                    else fprintf(fp, "  %s%s\r\n", ap.c_str(), d ? "\\" : "");
                }
                fputs("\r\n", fp);
            }
            fputs("직접 지운 적이 없으면 [되살리기]: 아무것도 지우지 않고 사라진 쪽에 다시 만듭니다.\r\n일부러 지웠으면 [지우기]: PC는 휴지통, NAS는 #recycle 로 갑니다.\r\n", fp);
            fclose(fp);
        }
    }
    if (stats.heldDeletes == 0 && lastHeld_ > 0) { std::lock_guard<std::mutex> g(cfMu_); heldDirs_.clear(); heldGroups_ = 0; }
    lastHeld_ = stats.heldDeletes;

    stats.pending = (int)items.size();
    // 이제 할 일 목록에 없는 '기다림'은 정리
    {
        std::set<std::string> ip; for (auto& it : items) ip.insert(it.p);
        std::lock_guard<std::mutex> g(xMu_);
        for (auto w = waitNote_.begin(); w != waitNote_.end();) w = ip.count(w->first) ? std::next(w) : waitNote_.erase(w);
        for (auto w = fails_.begin(); w != fails_.end();) w = ip.count(w->first) ? std::next(w) : fails_.erase(w);
    }
    // 순서: 폴더 만들기(얕은 것 먼저) → 파일 → 삭제(깊은 것 먼저, 위 폴더가 지워지면 아래는 따라감)
    auto depthLess = [](const Item& x, const Item& y) { return Depth(x.p) < Depth(y.p) || (Depth(x.p) == Depth(y.p) && x.p < y.p); };
    std::vector<Item> dirs, files, dels;
    for (auto& it : items) {
        if (it.a == MkR || it.a == MkL || it.a == SyncBase || it.a == DropBase) dirs.push_back(it);
        else if (it.a == DelL || it.a == DelR) dels.push_back(it);
        else files.push_back(it);
    }
    std::sort(dirs.begin(), dirs.end(), depthLess);
    std::sort(dels.begin(), dels.end(), depthLess);   // 얕은 것 먼저: 폴더째 지우고 아래는 건너뜀

    // 어느 쪽에 새로 생기거나 고친 항목이 들어 있는 폴더 (그 폴더는 통째로 지우거나 바꾸지 않음)
    std::set<std::string> newUnderL, newUnderR;
    for (auto& it : items) {
        bool l = it.a == Up || it.a == MkR || it.a == Conflict || it.a == ReplR, r = it.a == Down || it.a == MkL || it.a == Conflict || it.a == ReplL;
        for (std::string q = Parent(it.p); !q.empty(); q = Parent(q)) { if (l) newUnderL.insert(q); if (r) newUnderR.insert(q); }
    }
    for (auto& it : dirs) {
        if (stop_ || Paused()) return any;
        auto& p = it.p;
        switch (it.a) {
        case MkR: any |= MkdirRemote(p); break;
        case MkL: any |= MkdirLocal(p, remote_[p]); break;
        case SyncBase: SetBase(p, local_[p], remote_[p]); break;
        case DropBase: base_.erase(p); dirty_ = true; break;
        default: break;
        }
        stats.pending--;
    }
    // 순서: 먼저 맞출 폴더 → 작은 파일 먼저 (작은 문서가 큰 영상 뒤에 줄 서지 않게)
    auto prio = [&](const std::string& p) { for (auto& d : cfg_.priorityDirs) if (Under(p, d)) return 0; return 1; };
    auto size = [&](const Item& it) -> int64_t {
        if (it.a == Up || it.a == ReplR) { auto f = local_.find(it.p); return f == local_.end() ? 0 : f->second.s; }
        auto f = remote_.find(it.p); return f == remote_.end() ? 0 : f->second.s;
    };
    std::stable_sort(files.begin(), files.end(), [&](const Item& x, const Item& y) {
        int px = prio(x.p), py = prio(y.p); return px != py ? px < py : size(x) < size(y);
    });
    // 옮길 일을 둘로 나눔: 작은 파일(이번 차례 안에 여러 통로로 함께) / 큰 파일(뒤에서 따로)
    const int64_t kBig = 8LL * 1024 * 1024;
    std::vector<Job> smallJobs;
    for (auto& it : files) {
        if (stop_ || Paused()) return any;
        auto& p = it.p;
        // 다른 프로그램이 쓰는 중이면 이번엔 건너뜀 (잠시 뒤 다시) — 뒤의 파일은 그대로 진행
        {
            auto lf = local_.find(p); bool lfile = lf != local_.end() && !lf->second.d;
            bool busy = ((it.a == Up || it.a == ReplR) && lfile && Busy(p, false)) ||
                        ((it.a == Down || it.a == Conflict || it.a == ReplL || it.a == Compare) && lfile && Busy(p, true));
            if (busy) { stats.pending--; continue; }
        }
        auto bi = base_.find(p);
        const BaseE* b = bi == base_.end() ? nullptr : &bi->second;
        BaseE bcopy; if (b) { bcopy = *b; b = &bcopy; }
        if (it.a == Up || it.a == Down || it.a == Compare) {
            Job j;
            j.kind = it.a == Up ? Job::Up : it.a == Down ? Job::Down : Job::Compare;
            j.p = p;
            // 없는 쪽을 만들지 않도록 find 로 (operator[] 는 빈 항목을 넣어 버림)
            if (auto lf = local_.find(p); lf != local_.end()) { j.l = lf->second; j.hasL = true; }
            if (auto rf = remote_.find(p); rf != remote_.end()) j.r = rf->second;
            if (b) { j.hasBase = true; j.b = *b; }
            // 필요할 때 받기: 새 파일이나 온라인 전용 파일은 내용을 받지 않고 목록(자리표시)만 — 단, '항상 유지' 폴더 안은 받음
            if (j.kind == Job::Down && cloudOn_ && !unregister_) {
                uint8_t pin = j.hasL ? j.l.pin : ParentPin(p);
                if (pin != 1 && (!j.hasL || cloud::IsDehydrated(j.l.a) || ((j.l.a & FILE_ATTRIBUTE_REPARSE_POINT) && cloud::IsPartial(Abs(cfg_.localRoot, p))))) j.ph = true;
            }
            j.size = j.ph ? 0 : std::max(j.l.s, j.r.s);
            if (j.size >= kBig && !bigWorkers_.empty()) {
                inflight_.insert(p); bigN++;
                { std::lock_guard<std::mutex> g(qMu_); bigQ_.push_back(j); }
                qCv_.notify_one();
                stats.pending--;
            } else smallJobs.push_back(j);
            continue;
        }
        switch (it.a) {   // 드문 일은 이 자리에서 차례로
        case ReplR: {   // PC 에서 종류가 바뀜 (폴더→파일 또는 파일→폴더): NAS 쪽 옛 것을 휴지통으로 보내고 새로 올림
            LocalE l = local_[p]; RemoteE r = remote_[p];
            if (r.d && newUnderR.count(p)) { if (KeepBothLocal(p)) any |= MkdirLocal(p, r); break; }
            auto bj = base_.find(p);
            if (bj == base_.end() || !DeleteRemote(p, bj->second)) break;
            any = true;
            if (l.d) MkdirRemote(p); else Upload(p, l, nullptr);
            break;
        }
        case ReplL: {   // NAS 에서 종류가 바뀜: PC 쪽 옛 것을 휴지통으로 보내고 새로 받음
            RemoteE r = remote_[p];
            if (local_[p].d && newUnderL.count(p)) { if (KeepBothLocal(p)) any |= r.d ? MkdirLocal(p, r) : Download(p, r, nullptr); break; }
            if (!DeleteLocal(p)) break;
            any = true;
            if (r.d) MkdirLocal(p, r); else Download(p, r, nullptr);
            break;
        }
        case Conflict:
            // PC 판은 이름을 바꿔 남기고(다음 차례에 새 파일로 올라감), NAS 판을 원래 이름으로 받음
            if (KeepBothLocal(p)) { if (remote_[p].d) any |= MkdirLocal(p, remote_[p]); else any |= Download(p, remote_[p], nullptr); }
            break;
        default: break;
        }
        stats.pending--;
    }
    // 작은 파일: 통로 8개로 함께 옮김 (하나가 느려도 나머지는 계속).
    // 1 MB 이하 올리기는 최대 32개(8 MB)씩 묶어 한 요청으로 → 파일마다 드는 왕복을 줄임
    if (!smallJobs.empty()) {
        std::atomic<size_t> next{0};
        std::atomic<bool> anyJob{false};
        const int64_t kTiny = 1LL << 20, kBatchBytes = 8LL << 20;
        const size_t kBatchN = 32;
        auto tiny = [&](const Job& j) { return j.kind == Job::Up && j.size <= kTiny && batchOk_ && UpRateLimit() <= 0; };
        auto work = [&] {
            for (size_t k; !stop_ && !Paused() && (k = next++) < smallJobs.size();) {   // 멈추면 줄에 남은 것은 다음 차례로
                while (UpperBusy(cfg_.rank) && !stop_) Sleep(200);   // 우선순위 '높음' 연결이 먼저
                if (!tiny(smallJobs[k])) {
                    if (RunJob(smallJobs[k])) anyJob = true;
                    stats.pending--;
                    continue;
                }
                // 뒤따르는 작은 올리기를 함께 가져감 (다른 통로와 겹치지 않게 하나씩 차지)
                std::vector<const Job*> batch{&smallJobs[k]};
                int64_t bytes = smallJobs[k].size;
                for (size_t k2 = next.load(); batch.size() < kBatchN && k2 < smallJobs.size();) {
                    const Job& c = smallJobs[k2];
                    if (!tiny(c) || bytes + c.size > kBatchBytes) break;
                    if (next.compare_exchange_weak(k2, k2 + 1)) { batch.push_back(&c); bytes += c.size; k2++; }
                }
                bool fallback = false;
                int n = batch.size() == 1 ? -1 : UploadBatch(batch, fallback);
                if (n > 0) anyJob = true;
                if (n < 0 || fallback) for (auto* j : batch) if (RunJob(*j)) anyJob = true;   // 한 개뿐이거나 서버가 묶음을 모름
                stats.pending -= (int)batch.size();
            }
        };
        RankGuard hg2(cfg_.rank);
        L.unlock();
        int nThreads = (int)std::min<size_t>(8, smallJobs.size());
        std::vector<std::thread> th;
        for (int t = 1; t < nThreads; t++) th.emplace_back(work);
        work();
        for (auto& t : th) t.join();
        L.lock();
        any |= anyJob.load();
    }
    // 탐색기 연동: 항상 유지 → 받기(큰 파일 통로) / 공간 확보 → 내용 비우기 / 보통 파일 → 자리표시로 바꾸고 '맞춰짐' 표시
    if (cloudOn_) {
        for (auto& p : hydr) {
            if (inflight_.count(p)) continue;
            Job j; j.kind = Job::Hydrate; j.p = p; j.l = local_[p]; j.r = remote_[p]; j.hasL = true;
            inflight_.insert(p); bigN++;
            { std::lock_guard<std::mutex> g(qMu_); bigQ_.push_back(j); }
            qCv_.notify_one();
        }
        for (auto& p : dehy) {
            if (stop_ || Paused()) return any;
            std::wstring a = Abs(cfg_.localRoot, p);
            if (!cloud::Dehydrate(a)) continue;   // 쓰는 중이면 다음에
            // '공간 확보' 표시는 위 폴더가 '항상 유지'가 아닐 때만 지움 (보통 온라인 전용으로 돌아감).
            //   '모두 받아 두기'·'항상 유지' 폴더 안이면 지우지 않고 남김 — 지우면 위 폴더의 '항상 유지'를 물려받아 곧바로 다시 통째로 받았음
            if (cloud::IsUnpinned(local_[p].a) && ParentPin(p) != 1) cloud::ClearPin(a, false);
            LocalE now; if (StatLocal(p, now)) { now.pin = local_[p].pin; local_[p] = now; }
            Event("☁ 공간 확보", p, std::to_string(now.s) + " B");
            any = true;
        }
        for (auto& p : autoFree) {
            if (stop_ || Paused()) return any;
            if (!cloud::Dehydrate(Abs(cfg_.localRoot, p))) continue;   // 열려 있으면 그대로 둠
            LocalE now; if (StatLocal(p, now)) local_[p] = now;
            Event("☁ 자동 공간 확보 (오래 안 씀)", p, std::to_string(now.s) + " B");
            any = true;
        }
        for (auto& p : unpinDirs) cloud::ClearPin(Abs(cfg_.localRoot, p), true);
        if (!unregister_) ApplyKeepDirs();
        for (auto& p : conv) { if (stop_ || Paused()) return any; InSync(p, local_[p].d); }
        if (conv.size() >= 2000) any = true;   // 더 남음 → 곧 이어서
        std::vector<std::string> retry;
        { std::lock_guard<std::mutex> g(spMu_); retry.assign(syncPending_.begin(), syncPending_.end()); }
        for (auto& p : retry) {
            auto lf = local_.find(p);
            if (lf != local_.end() && base_.count(p)) InSync(p, lf->second.d);
            else { std::lock_guard<std::mutex> g(spMu_); syncPending_.erase(p); }
        }
        // 동기화 폴더 자체에도 상태 표시 (부모 폴더·탐색 창 'Mydrive'에서 보임): 다 맞으면 체크, 아니면 맞추는 중
        if (!unregister_) {
            bool done = stats.pending <= 0 && inflight_.empty() && hydr.empty() && conv.empty() && stats.heldDeletes == 0;
            { std::lock_guard<std::mutex> g(qMu_); if (!bigQ_.empty()) done = false; }
            int want = done ? 1 : 2;
            // Windows 는 맨 위 폴더 바로 아래 항목이 생기거나 사라지면(이름 바꾸기·옮기기 포함) 맨 위 폴더의 '맞춰짐' 표시를 지움
            //   → 기억해 둔 값만 믿으면 탐색기에 계속 '동기화 중'. 다 맞았을 때는 실제 표시를 확인해 빠졌으면 다시 칠함
            if (want == 1 && rootSync_ == 1 && cloud::InSyncState(PlainRoot(cfg_.localRoot), true) == 0) rootSync_ = 0;
            if (rootSync_ != want && cloud::SetRootInSync(W(cfg_.name), PlainRoot(cfg_.localRoot), done)) rootSync_ = want;
            else if (rootSync_ != want) rootSync_ = want;   // 맨 위 폴더에 못 칠해도 'Mydrive' 쪽은 칠함 — 매번 다시 하지 않게
        }
        // 연동을 끄는 중: 온라인 전용 파일이 하나도 남지 않으면 탐색기 등록을 풂
        if (unregister_ && hydr.empty() && inflight_.empty()) {
            bool left = false;
            for (auto& [p, l] : local_) if (!l.d && cloud::IsDehydrated(l.a)) { left = true; break; }
            if (!left) {
                // 자리표시 → 보통 파일로 (깊은 것부터)
                for (auto it = local_.rbegin(); it != local_.rend(); ++it)
                    if (it->second.a & FILE_ATTRIBUTE_REPARSE_POINT) cloud::Revert(Abs(cfg_.localRoot, it->first), it->second.d);
                CloudStop();
                cloud::Unregister(W(cfg_.name));
                cloudOn_ = unregister_ = false;
                Event("탐색기 연동 해제", cfg_.name, "모든 파일을 PC에 받은 뒤 등록을 풂");
            }
        }
    }
    // 폴더를 지우려는데 그 안에 새로 생기거나 고친 항목이 있으면 폴더는 지우지 않음 (새 것까지 휩쓸려 지워지지 않게)
    std::set<std::string> keep;
    for (auto& it : items)
        if (it.a == Up || it.a == Down || it.a == MkR || it.a == MkL || it.a == Conflict)
            for (std::string q = Parent(it.p); !q.empty(); q = Parent(q)) keep.insert(q);
    std::set<std::string> gone;
    for (auto& it : dels) {
        if (stop_ || Paused()) return any;
        auto& p = it.p;
        if (keep.count(p)) { Event("폴더 유지", p, "안에 새로 생기거나 고친 파일이 있음"); stats.pending--; continue; }
        if ((it.a == DelL && exclUnderL.count(p)) || (it.a == DelR && exclUnderR.count(p))) { Event("폴더 유지", p, "필터로 뺀 파일이 들어 있음"); stats.pending--; continue; }
        bool under = false;
        for (std::string q = Parent(p); !q.empty(); q = Parent(q)) if (gone.count(q)) { under = true; break; }
        if (under) { base_.erase(p); continue; }
        if (it.a == DelL && !local_.count(p)) { stats.pending--; continue; }   // 위에서 이미 지워짐
        bool okL = !holdL || allowDel_.count(p);   // 보류 중이어도 [옮긴 것으로 처리]한 것은 지움
        if (it.a == DelL && okL && !local_[p].d && Busy(p, true)) { stats.pending--; continue; }
        if (it.a == DelL && okL) { if (DeleteLocal(p)) { gone.insert(p); any = true; } }
        if (it.a == DelR && !holdR) { auto bi = base_.find(p); if (bi != base_.end() && DeleteRemote(p, bi->second)) { gone.insert(p); any = true; } }
        stats.pending--;
    }
    prof_[3] = GetTickCount64() - T0;
    if (confirmDelete_ && (nDelL || nDelR)) confirmDelete_ = false;
    stats.pending = 0;
    { std::lock_guard<std::mutex> g(xMu_); stats.waiting = (int)waitNote_.size(); }
    // 상태 저장은 전체를 다시 쓰므로 바쁜 동안은 30초에 한 번만 (조용해지면·끝날 때는 바로). 저장 전에 꺼져도 다음에 비교로 다시 맞춤
    if (dirty_ && (!any || GetTickCount64() - lastSave_ > 30000)) { Save(); lastSave_ = GetTickCount64(); }
    SaveOpened();
    // 뺀 파일 때문에 폴더가 탐색기에 '동기화 중'으로 남지 않게: 그 파일을 '이 PC에만 · 맞춰짐'으로 표시
    if (cloudOn_ && !unregister_ && !exclTree_.empty()) {
        int n = 0;
        std::function<void(const std::wstring&)> mark = [&](const std::wstring& a) {
            if (stop_ || n > 5000) return;
            if (cloud::MarkExcluded(a)) n++;   // 폴더면 먼저 폴더, 그다음 안의 것
            if (!(GetFileAttributesW(a.c_str()) & FILE_ATTRIBUTE_DIRECTORY)) return;
            WIN32_FIND_DATAW fd; HANDLE fh = FindFirstFileW((a + L"\\*").c_str(), &fd);
            if (fh == INVALID_HANDLE_VALUE) return;
            do { std::wstring f = fd.cFileName; if (f != L"." && f != L"..") mark(a + L"\\" + f); } while (FindNextFileW(fh, &fd));
            FindClose(fh);
        };
        for (auto& p : exclTree_) mark(Abs(cfg_.localRoot, p));
        if (n) LOG("[%s] 늘 빼는 폴더 %d개 안의 항목 %d개를 탐색기에 '이 PC에만'으로 표시", cfg_.name.c_str(), (int)exclTree_.size(), n);
    }
    exclTree_.clear();
    if (cloudOn_ && !unregister_ && !exclPlain_.empty()) {
        int n = 0;
        for (auto& p : exclPlain_) if (!stop_ && cloud::MarkExcluded(Abs(cfg_.localRoot, p))) n++;
        if (n) LOG("[%s] 동기화에서 뺀 파일 %d개를 탐색기에 '이 PC에만'으로 표시", cfg_.name.c_str(), n);
        exclPlain_.clear();
    }
    if (!healed_ && !stop_) { healed_ = true; L.unlock(); HealInSync(); }
    return any;
}

// 예전 판에서 이름을 바꾸거나 옮긴 파일은 Windows 가 지운 '맞춰짐' 표시가 그대로 빠져 있어 탐색기에 '보류 중'으로 보임.
// 켠 뒤 첫 차례가 끝나면 한 번, 양쪽이 마지막 상태와 같은 자리표시만 골라 표시를 확인하고 빠졌으면 붙임 (파일은 받지 않음).
void Engine::HealInSync() {
    if (!cloudOn_ || unregister_) return;
    // 파일을 하나하나 열어 보는 일이라 큰 폴더(19만 개)에서는 40초쯤 걸림 → 켤 때마다 하지 않고 7일에 한 번만
    std::wstring mark = cfg_.stateDir + L"\\" + W(cfg_.name) + L".healed";
    WIN32_FILE_ATTRIBUTE_DATA fa{};
    if (GetFileAttributesExW(mark.c_str(), GetFileExInfoStandard, &fa)) {
        FILETIME ft; GetSystemTimeAsFileTime(&ft);
        if (FtToNs(ft) - FtToNs(fa.ftLastWriteTime) < 7LL * 86400 * 1000000000LL) return;
    }
    std::vector<std::pair<std::string, bool>> list;
    {
        std::lock_guard<std::recursive_mutex> g(mu_);
        for (auto& [p, b] : base_) {
            auto li = local_.find(p); auto ri = remote_.find(p);
            if (li == local_.end() || ri == remote_.end()) continue;
            const LocalE& l = li->second; const RemoteE& r = ri->second;
            if (!(l.a & FILE_ATTRIBUTE_REPARSE_POINT)) continue;
            bool sameL = l.d == b.d && (b.d || (l.s == b.ls && SameTime(l.m, b.lm)));
            bool sameR = r.d == b.d && (b.d || (r.s == b.rs && r.m == b.rm));
            if (sameL && sameR) list.push_back({p, l.d});
        }
    }
    int fixed = 0;
    for (auto& [p, d] : list) {
        if (stop_) return;
        if (cloud::InSyncState(Abs(cfg_.localRoot, p), d) == 0) { InSync(p, d); if (fixed < 500) cloud::RefreshShell(Abs(cfg_.localRoot, p)); fixed++; }
    }
    if (fixed) Event("✔ 맞춰짐 표시 고침", cfg_.name, std::to_string(fixed) + "개 — 이름을 바꾸거나 옮긴 뒤 빠져 있던 표시");
    // 끝까지 했을 때만 '했음'을 적어 둠 (중간에 꺼지면 다음에 켤 때 다시)
    HANDLE mh = CreateFileW(mark.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, 0, nullptr);
    if (mh != INVALID_HANDLE_VALUE) CloseHandle(mh);
}

// ---------- 계속 맞추기: PC 폴더 감시 + NAS 변경 기다리기 ----------
std::shared_ptr<Engine::XState> Engine::BeginXfer(const std::string& p, bool up, int64_t total, int64_t sigS, int64_t sigM) {
    auto x = std::make_shared<XState>(); x->up = up; x->total = total; x->start = GetTickCount64(); x->sigS = sigS; x->sigM = sigM;
    std::lock_guard<std::mutex> g(xMu_); xfers_[p] = x;
    return x;
}
void Engine::EndXfer(const std::string& p) { std::lock_guard<std::mutex> g(xMu_); xfers_.erase(p); }
bool Engine::CancelTransfer(const std::string& p) {
    std::lock_guard<std::mutex> g(xMu_);
    auto it = xfers_.find(p);
    if (it == xfers_.end() || it->second->sigS < 0) return false;   // 이미 끝났거나, 탐색기에서 열 때 받는 중 (그건 연 프로그램이 멈춤)
    it->second->cancel = true;
    canceled_[p] = Canceled{it->second->up, it->second->sigS, it->second->sigM};
    return true;
}

std::vector<Transfer> Engine::Transfers() {
    std::lock_guard<std::mutex> g(xMu_);
    std::vector<Transfer> v;
    for (auto& [p, x] : xfers_) v.push_back({p, x->up, x->total, x->done.load(), x->start});
    return v;
}

// 막힘: 다른 프로그램이 1분 넘게 잡고 있음 / 같은 파일이 1분 넘게 3번 이상 실패
std::vector<StuckInfo> Engine::StuckList() {
    std::lock_guard<std::mutex> g(xMu_);
    std::vector<StuckInfo> v; uint64_t now = GetTickCount64();
    for (auto& [p, w] : waitNote_) if (now - w.second >= 60000) v.push_back({p, w.first, w.second, 0, true});
    for (auto& [p, f] : fails_) if (f.n >= 3 && f.last - f.first >= 60000) v.push_back({p, f.msg, f.first, f.n, false});
    return v;
}

// 탐색기 '동기화 상태 보기': rel 아래(또는 rel 그 자체)의 항목마다 base 와 비교
ItemStatus Engine::Status(const std::string& rel) {
    ItemStatus st;
    auto in = [&](const std::string& p) { return rel.empty() || Under(p, rel); };
    auto note = [&](const std::string& p, const std::string& why) { if (st.issues.size() < 15) st.issues.push_back({p, why}); };
    {
        std::lock_guard<std::recursive_mutex> g(mu_);
        std::set<std::string> all;
        for (auto& [p, _] : local_) if (in(p)) all.insert(p);
        for (auto& [p, _] : remote_) if (in(p)) all.insert(p);
        for (auto& [p, _] : base_) if (in(p)) all.insert(p);
        st.found = rel.empty() || all.count(rel) > 0;
        for (auto& p : all) {
            auto li = local_.find(p); auto ri = remote_.find(p); auto bi = base_.find(p);
            const LocalE* l = li == local_.end() ? nullptr : &li->second;
            const RemoteE* r = ri == remote_.end() ? nullptr : &ri->second;
            const BaseE* b = bi == base_.end() ? nullptr : &bi->second;
            bool d = l ? l->d : r ? r->d : b->d;
            int64_t size = r ? r->s : l ? l->s : b->rs;
            if (p == rel) st.dir = d;
            if (Excluded(p, d, size)) { if (p == rel) st.excluded = true; st.excludedN++; continue; }
            if (d) st.dirs++; else { st.files++; st.bytes += size; }
            if (!d && l) { if (cloud::IsDehydrated(l->a)) st.onlineOnly++; else st.localBytes += l->s; }
            bool sameL = l && b && l->d == b->d && (b->d || (l->s == b->ls && SameTime(l->m, b->lm)));
            bool sameR = r && b && r->d == b->d && (b->d || (r->s == b->rs && r->m == b->rm));
            if (sameL && sameR) { if (!d) st.synced++; continue; }   // 맞춰진 '파일' 수 (폴더는 세지 않음)
            if (!b) {
                if (l && !r) { st.upWait++; note(p, "PC에만 있음 → NAS로 올릴 차례"); }
                else if (r && !l) { st.downWait++; note(p, "NAS에만 있음 → PC로 받을 차례"); }
                else { st.checkWait++; note(p, "양쪽에 있음 → 내용이 같은지 확인할 차례"); }
                continue;
            }
            bool chL = !sameL, chR = !sameR;
            if (chL && chR && l && r) { st.both++; note(p, "PC와 NAS 양쪽에서 바뀜 → 둘 다 남길 차례"); }
            else if (chL) { if (l) { st.upWait++; note(p, "PC에서 바뀜 → NAS로 올릴 차례"); } else { st.delWait++; note(p, "PC에서 지움 → NAS에서도 지울 차례"); } }
            else if (chR) { if (r) { st.downWait++; note(p, "NAS에서 바뀜 → PC로 받을 차례"); } else { st.delWait++; note(p, "NAS에서 지움 → PC에서도 지울 차례"); } }
        }
    }
    {
        std::lock_guard<std::mutex> g(xMu_);
        uint64_t now = GetTickCount64();
        for (auto& [p, x] : xfers_) if (in(p)) st.transferring++;
        for (auto& [p, w] : waitNote_) if (in(p)) { st.locked++; if (now - w.second >= 5000) note(p, "다른 프로그램이 쓰는 중: " + w.first); }
        for (auto& [p, f] : fails_) if (in(p) && f.n > 0) { st.failing++; note(p, "옮기기 실패 " + std::to_string(f.n) + "번: " + f.msg); }
    }
    return st;
}

// 옮길 일 하나 처리 (전송 통로 스레드에서 불림 — 상태는 짧게 잠그고 고침)
bool Engine::RunJob(const Job& j) {
    const BaseE* b = j.hasBase ? &j.b : nullptr;
    switch (j.kind) {
    case Job::Up: return Upload(j.p, j.l, b);
    case Job::Down: return j.ph ? Placeholder(j) : Download(j.p, j.r, b);
    case Job::Hydrate: {   // '항상 유지': 온라인 전용 파일의 내용을 받아 둠 (받는 일은 CloudFetch 가 함)
        if (!cloud::Hydrate(Abs(cfg_.localRoot, j.p))) { Event("받기 실패", j.p, "항상 유지 — 잠시 뒤 다시"); return false; }
        return true;
    }
    case Job::Compare: {
        // 양쪽에 크기가 같은 파일: 내용이 같으면 옮기지 않고 그대로 맞춘 것으로, 다르면 둘 다 남김
        int same = SameContent(j.p);
        if (same < 0) return false;   // 확인 못 함 → 아무것도 바꾸지 않고 다음 차례에 다시
        if (same == 1) {
            LocalE now;
            std::lock_guard<std::recursive_mutex> g(mu_);
            if (StatLocal(j.p, now) && now.s == j.l.s && SameTime(now.m, j.l.m)) { SetBase(j.p, now, j.r); return true; }
            return false;   // 그사이 바뀜 → 다음 차례에
        }
        int broken = BrokenLocal(j.p, j.l.s);
        if (broken < 0) return false;   // 확인 못 함 → 다음 차례에 다시
        if (broken == 1) {
            // 깨진 PC 판은 충돌 사본으로 올리지 않음 — PC 휴지통으로 보내고(되살릴 수 있음) NAS 판을 받음
            Event("⚠ 깨진 PC 판", j.p, "끝부분이 0 으로만 차 있음 (덜 받은 채 크기만 맞춰진 파일) — PC 휴지통으로 보내고 NAS 판으로 바꿈");
            { std::lock_guard<std::recursive_mutex> g(mu_); if (!DeleteLocal(j.p)) return false; }
            return Download(j.p, j.r, nullptr);
        }
        if (!KeepBothLocal(j.p)) return false;
        return Download(j.p, j.r, nullptr);
    }
    }
    return false;
}

// 큰 파일 통로: 맞추기 차례가 끝나도 계속 옮김. 끝나면 한 번 더 맞추도록 깨움.
void Engine::BigWorker() {
    for (;;) {
        Job j;
        {
            std::unique_lock<std::mutex> lk(qMu_);
            qCv_.wait(lk, [&] { return stop_ || !bigQ_.empty(); });
            if (stop_) return;
            j = bigQ_.front(); bigQ_.pop_front();
        }
        {
            RankGuard hg(cfg_.rank);
            while (UpperBusy(cfg_.rank) && !stop_) Sleep(200);
            while (Paused() && !stop_) WaitForSingleObject(wake_, 1000);   // 멈춘 동안 큰 파일 통로도 쉼 (줄은 그대로 두었다가 이어서)
            if (!stop_) RunJob(j);
        }
        { std::lock_guard<std::recursive_mutex> g(mu_); inflight_.erase(j.p); } bigN--;
        Kick();
    }
}

// ---------- 탐색기 연동 (Cloud Files) ----------
static std::wstring PlainRoot(std::wstring r) {
    if (r.compare(0, 4, L"\\\\?\\") == 0) r = r.substr(4);
    while (r.size() > 3 && r.back() == L'\\') r.pop_back();
    return r;
}

// ---------- 마지막으로 연 시각 (열 때 받은 파일) ----------
void Engine::NoteOpened(const std::string& p) {
    FILETIME ft; GetSystemTimeAsFileTime(&ft);
    std::lock_guard<std::mutex> g(openMu_); opened_[p] = FtToNs(ft); openedDirty_ = true;
}
int64_t Engine::OpenedAt(const std::string& p) {
    std::lock_guard<std::mutex> g(openMu_);
    auto it = opened_.find(p); return it == opened_.end() ? 0 : it->second;
}
void Engine::SaveOpened() {
    std::map<std::string, int64_t> copy;
    { std::lock_guard<std::mutex> g(openMu_); if (!openedDirty_) return; copy = opened_; openedDirty_ = false; }
    std::wstring f = cfg_.stateDir + L"\\" + W(cfg_.name) + L".opened";
    if (FILE* fp = _wfopen(f.c_str(), L"wb")) { for (auto& [p, t] : copy) fprintf(fp, "%lld\t%s\n", (long long)t, p.c_str()); fclose(fp); }
}
void Engine::LoadOpened() {
    std::wstring f = cfg_.stateDir + L"\\" + W(cfg_.name) + L".opened";
    FILE* fp = _wfopen(f.c_str(), L"rb");
    if (!fp) return;
    char line[8192];
    std::lock_guard<std::mutex> g(openMu_);
    while (fgets(line, sizeof line, fp)) {
        char* tab = strchr(line, '\t'); if (!tab) continue;
        std::string p = tab + 1; while (!p.empty() && (p.back() == '\n' || p.back() == '\r')) p.pop_back();
        opened_[p] = _atoi64(line);
    }
    fclose(fp);
}

uint8_t Engine::ParentPin(const std::string& p) const {
    std::string par = Parent(p);
    if (par.empty()) return rootPin_;
    auto it = local_.find(par);
    return it == local_.end() ? 0 : it->second.pin;
}

// '항상 이 PC에 둘 폴더': 폴더째 '항상 유지'로 표시 → 다음 차례에 안의 파일을 모두 받아 둠.
// 설정에서 빠진 폴더는 표시를 풀어 '요청 시 받기'로 돌림 (이미 받은 내용은 그대로 — 지우지 않음)
void Engine::ApplyKeepDirs() {
    std::wstring f = cfg_.stateDir + L"\\" + W(cfg_.name) + L".keep";
    if (!keepLoaded_) {
        keepLoaded_ = true;
        if (FILE* fp = _wfopen(f.c_str(), L"rb")) {
            char line[4096];
            while (fgets(line, sizeof line, fp)) { std::string s = line; while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back(); if (!s.empty()) keepApplied_.insert(s); }
            fclose(fp);
        }
    }
    std::set<std::string> want(cfg_.keepDirs.begin(), cfg_.keepDirs.end());
    bool changed = false;
    {   // 받는 방식: 모두 받아 두기 ↔ 필요할 때 받기 (표시 "/" = 맨 위 폴더)
        std::wstring root = PlainRoot(cfg_.localRoot);
        bool all = !cfg_.onDemand, applied = keepApplied_.count("/") > 0;
        if (all && !applied) {
            if (cloud::PinDir(root)) { keepApplied_.insert("/"); changed = true; rootPin_ = 1; Event("📌 모두 받아 두기", cfg_.name, "모든 파일을 이 PC에 받아 둡니다"); }
        } else if (!all && applied) {
            cloud::ClearPin(root, true, true);
            keepApplied_.erase("/"); changed = true; rootPin_ = 0;
            Event("필요할 때 받기로 바꿈", cfg_.name, "이미 받은 파일은 그대로 둠");
        }
    }
    for (auto& d : want) {
        auto it = local_.find(d);
        if (it == local_.end() || !it->second.d) continue;   // 아직 PC 에 없는 폴더는 생기면 그때
        if (keepApplied_.count(d)) continue;   // 이미 표시함 (그 뒤 탐색기에서 바꾼 것은 그대로 존중)
        if (!cloud::IsPinned(it->second.a) && !cloud::PinDir(Abs(cfg_.localRoot, d))) continue;   // 실패하면 다음 차례에 다시
        Event("📌 항상 이 PC에 둠", d, "폴더 안 파일을 모두 받아 둡니다");
        keepApplied_.insert(d); changed = true;
    }
    for (auto it = keepApplied_.begin(); it != keepApplied_.end();) {
        if (*it == "/" || want.count(*it)) { ++it; continue; }
        if (local_.count(*it)) { cloud::ClearPin(Abs(cfg_.localRoot, *it), true, true); Event("요청 시 받기로 바꿈", *it, "이미 받은 파일은 그대로 둠"); }
        it = keepApplied_.erase(it); changed = true;
    }
    if (changed) {
        if (FILE* fp = _wfopen(f.c_str(), L"wb")) { for (auto& s : keepApplied_) { fputs(s.c_str(), fp); fputc('\n', fp); } fclose(fp); }
    }
}

void Engine::InSync(const std::string& p, bool d) {
    if (!cloudOn_ || unregister_) return;
    bool ok = cloud::MarkInSync(Abs(cfg_.localRoot, p), d, "");
    std::lock_guard<std::mutex> g(spMu_);
    if (ok) syncPending_.erase(p);
    else if (syncPending_.size() < 5000) syncPending_.insert(p);
}

// NAS 의 새 파일: 내용은 받지 않고 탐색기에 보이는 자리표시(온라인 전용)만 만들거나 고침
bool Engine::Placeholder(const Job& j) {
    std::string id = std::to_string(j.r.i);
    bool ok = j.hasL ? cloud::UpdateDehydrated(Abs(cfg_.localRoot, j.p), j.r.s, j.r.m, id)
                     : cloud::CreatePlaceholder(Abs(cfg_.localRoot, Parent(j.p)), W(Leaf(j.p)), false, j.r.s, j.r.m, id);
    if (!ok) { stats.errors++; Event("온라인 전용 만들기 실패", j.p); return false; }
    std::lock_guard<std::recursive_mutex> g(mu_);
    LocalE now;
    if (StatLocal(j.p, now)) SetBase(j.p, now, j.r);
    Event("☁ 온라인 전용", j.p, std::to_string(j.r.s) + " B");
    if (!j.hasL && IsConflictName(Leaf(j.p))) NoteConflict(j.p);
    return true;
}

// 탐색기(또는 다른 프로그램)가 온라인 전용 파일을 열 때: NAS 에서 받아 바로 넘겨줌
bool Engine::CloudFetch(const std::wstring& abs, int64_t off, const std::function<bool(const char*, size_t)>& sink) {
    std::wstring root = PlainRoot(cfg_.localRoot);
    if (abs.size() <= root.size() + 1 || _wcsnicmp(abs.c_str(), root.c_str(), root.size()) != 0 || abs[root.size()] != L'\\') return false;
    std::wstring rw = abs.substr(root.size() + 1);
    for (auto& c : rw) if (c == L'\\') c = L'/';
    std::string p = U(rw);
    int64_t total = 0;
    WIN32_FILE_ATTRIBUTE_DATA fa{};
    if (GetFileAttributesExW(abs.c_str(), GetFileExInfoStandard, &fa)) total = ((int64_t)fa.nFileSizeHigh << 32) | fa.nFileSizeLow;
    NoteOpened(p);
    auto xs = BeginXfer(p, false, total);
    xs->done = off;
    Resp res = fetch_.Stream("/v1/file", {{"root", cfg_.name}, {"path", p}}, off, sink, &xs->done);
    EndXfer(p);
    std::string who = U(cloud::FetchRequester()); if (who.empty()) who = "?";
    if (!res.ok()) { Event("받기 실패", p, "열 때 받기 — " + who + " — " + (res.error.empty() ? std::to_string(res.status) : res.error)); return false; }
    if (off == 0) { stats.down++; Event("↓ 받음", p, std::to_string(total) + " B · 열 때 받기 — " + who); }
    return true;
}

void Engine::CloudStart() {
    // 이 프로그램에는 자리표시(클라우드 파일)를 숨기지 말고 그대로 보여 달라고 함 (기본값은 프로그램에 따라 숨김)
    static bool once = [] {
        using Fn = char(NTAPI*)(char);
        if (auto f = (Fn)GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlSetProcessPlaceholderCompatibilityMode")) f(2 /*PHCM_EXPOSE_PLACEHOLDERS*/);
        return true;
    }();
    (void)once;
    std::wstring root = PlainRoot(cfg_.localRoot);
    auto ids = cloud::RegisteredIds();
    bool mine = std::find(ids.begin(), ids.end(), W(cfg_.name)) != ids.end();
    (void)mine;
    // 탐색기 연동(탐색 창·상태 아이콘·오른쪽 클릭)은 모든 짝에 늘 켬. 받는 방식은 ApplyKeepDirs 가 정함
    {
        wchar_t exe[MAX_PATH]; GetModuleFileNameW(nullptr, exe, MAX_PATH);
        if (!cloud::Register(W(cfg_.name), root, L"Mydrive · " + W(cfg_.name), std::wstring(exe) + L",0")) {
            Event("탐색기 연동 실패", cfg_.name, "등록할 수 없음 — 보통 동기화로 계속");
            return;
        }
    }
    if (!cloud::Connect(root, [this](const std::wstring& a, int64_t o, const std::function<bool(const char*, size_t)>& s) { return CloudFetch(a, o, s); }, cc_)) {
        Event("탐색기 연동 실패", cfg_.name, "연결할 수 없음 — 보통 동기화로 계속");
        unregister_ = false;
        return;
    }
    cloudOn_ = true;
    if (unregister_) Event("탐색기 연동 해제 중", cfg_.name, "온라인 전용 파일을 모두 받은 뒤 등록을 풂");
}
void Engine::CloudStop() { cloud::Disconnect(cc_); }

void Engine::Start() {
    stop_ = false;
    { std::lock_guard<std::mutex> g(g_engMu); if (std::find(g_engs.begin(), g_engs.end(), this) == g_engs.end()) g_engs.push_back(this); }
    CloudStart();
    for (int i = 0; i < 2; i++) bigWorkers_.emplace_back([this] { BigWorker(); });
    loop_ = std::thread([this] { LoopMain(); });
    pollThread_ = std::thread([this] { PollMain(); });
    watchThread_ = std::thread([this] { WatchMain(); });
}
void Engine::Stop() {
    { std::lock_guard<std::mutex> g(g_engMu); g_engs.erase(std::remove(g_engs.begin(), g_engs.end(), this), g_engs.end()); }
    if (stop_.exchange(true)) return;
    { std::lock_guard<std::mutex> g(qMu_); bigQ_.clear(); }
    qCv_.notify_all();
    http_.Abort(); poll_.Abort();
    SetEvent(wake_);
    for (auto& t : bigWorkers_) if (t.joinable()) t.join();
    if (watchDir_ != INVALID_HANDLE_VALUE) CancelIoEx(watchDir_, nullptr);
    if (loop_.joinable()) loop_.join();
    if (pollThread_.joinable()) pollThread_.join();
    if (watchThread_.joinable()) watchThread_.join();
    fetch_.Abort();
    if (cloudOn_) CloudStop();
    if (dirty_) Save();
    SaveOpened();
}

static int64_t EpochMsNow() {
    FILETIME ft; GetSystemTimeAsFileTime(&ft);
    return (int64_t)((((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime) / 10000ULL) - 11644473600000LL;
}

// 매일 정해진 시각: last 뒤에 오는 그 시각 (이 PC 의 시간대로)
static int64_t NextDaily(int64_t lastMs, int minutes) {
    auto toFt = [](int64_t ms) { uint64_t v = (uint64_t)(ms + 11644473600000LL) * 10000ULL; FILETIME f; f.dwLowDateTime = (DWORD)v; f.dwHighDateTime = (DWORD)(v >> 32); return f; };
    auto fromFt = [](const FILETIME& f) { return (int64_t)((((uint64_t)f.dwHighDateTime << 32) | f.dwLowDateTime) / 10000ULL) - 11644473600000LL; };
    FILETIME uf = toFt(lastMs); SYSTEMTIME u, lt, u2;
    FileTimeToSystemTime(&uf, &u); SystemTimeToTzSpecificLocalTime(nullptr, &u, &lt);
    lt.wHour = (WORD)(minutes / 60); lt.wMinute = (WORD)(minutes % 60); lt.wSecond = 0; lt.wMilliseconds = 0;
    TzSpecificLocalTimeToSystemTime(nullptr, &lt, &u2);
    FILETIME f2; SystemTimeToFileTime(&u2, &f2);
    int64_t t = fromFt(f2);
    while (t <= lastMs) t += 24LL * 3600000LL;
    return t;
}

int64_t Engine::NextPassMs() const {
    int64_t last = lastPassMs_;
    if (!last) return 0;
    if (cfg_.dailyAt >= 0) return NextDaily(last, cfg_.dailyAt);
    if (cfg_.everyHours > 0) return last + (int64_t)cfg_.everyHours * 3600000LL;
    return 0;
}

// 조건 확인 (20초 동안 기억): 0 맞음 · 1 PC 를 쓰는 중 · 2 드라이브가 바쁨
int Engine::CondNow() {
    uint64_t now = GetTickCount64();
    if (condAt_ && now - condAt_ < 20000) return condLast_;
    int r = 0;
    if (cfg_.idlePcMin > 0) {
        LASTINPUTINFO li{sizeof li};
        if (GetLastInputInfo(&li) && GetTickCount() - li.dwTime < (DWORD)cfg_.idlePcMin * 60000) r = 1;
    }
    if (!r && cfg_.idleDisk && cfg_.localRoot.size() >= 2 && cfg_.localRoot[1] == L':') {
        // 그 드라이브의 '쉬는 시간 %' 를 1초 동안 재서 85% 아래면 바쁨 (재지 못하면 따지지 않음)
        PDH_HQUERY q = nullptr; PDH_HCOUNTER c = nullptr;
        std::wstring path = std::wstring(L"\\LogicalDisk(") + (wchar_t)towupper(cfg_.localRoot[0]) + L":)\\% Idle Time";
        if (PdhOpenQueryW(nullptr, 0, &q) == ERROR_SUCCESS) {
            if (PdhAddEnglishCounterW(q, path.c_str(), 0, &c) == ERROR_SUCCESS && PdhCollectQueryData(q) == ERROR_SUCCESS) {
                Sleep(1000);
                PDH_FMT_COUNTERVALUE v{};
                if (PdhCollectQueryData(q) == ERROR_SUCCESS && PdhGetFormattedCounterValue(c, PDH_FMT_DOUBLE, nullptr, &v) == ERROR_SUCCESS && v.doubleValue < 85.0) r = 2;
            }
            PdhCloseQuery(q);
        }
    }
    condAt_ = GetTickCount64(); condLast_ = r;
    return r;
}

// 언제 맞출지: 때가 됐고 조건(PC·드라이브 유휴)도 맞으면 true. 아니면 잠깐 기다리고 false (바뀐 것 알림으로 깨어나도 맞추지 않음)
//   [지금 동기화]는 언제나·조건 없이 바로. 마지막으로 맞춘 때는 상태 폴더에 적어 두어 앱을 다시 켜도 이어 셈. 처음 한 번은 맞춤
bool Engine::WaitTurn() {
    if (chain_) return true;
    if (runNow_.exchange(false)) { condWait_ = 0; return true; }
    bool sched = cfg_.everyHours != 0 || cfg_.dailyAt >= 0;
    bool cond = cfg_.idlePcMin > 0 || cfg_.idleDisk;
    if (!sched && !cond) return true;
    // 기다리는 동안도 NAS 연결 여부는 알림 영역에 맞게 (1분마다 짧게)
    if (!lastPing_ || GetTickCount64() - lastPing_ > 60000) { lastPing_ = GetTickCount64(); stats.online = http_.Req(L"GET", "/v1/ping", {}, nullptr, 10).ok(); }
    if (sched) {
        if (!lastPassMs_) {
            FILE* fp = _wfopen((cfg_.stateDir + L"\\lastpass.txt").c_str(), L"rb");
            if (fp) { long long v = 0; if (fscanf(fp, "%lld", &v) == 1) lastPassMs_ = v; fclose(fp); }
        }
        if (lastPassMs_) {   // 처음이 아니면 때를 기다림 (원할 때만은 누를 때까지)
            int64_t now = EpochMsNow(), due = NextPassMs();
            if (!due || now < due) { condWait_ = 0; WaitForSingleObject(wake_, (DWORD)std::min<int64_t>(due ? due - now : 60000, 60000)); return false; }
        }
    }
    if (cond) {
        int c = CondNow();
        condWait_ = c;
        if (c) { WaitForSingleObject(wake_, 30000); return false; }
    }
    return true;
}

void Engine::LoopMain() {
    while (!stop_) {
        // 일시 중지 중: 풀리거나 다시 시작을 누를 때까지 기다림
        while (!stop_ && Paused()) WaitForSingleObject(wake_, (DWORD)std::min<uint64_t>(PausedLeftMs() + 50, 60000));
        if (stop_) break;
        if (!WaitTurn()) continue;
        if ((cfg_.everyHours != 0 || cfg_.dailyAt >= 0) && !chain_) {   // 맞추기 시작: 그때를 적어 둠
            lastPassMs_ = EpochMsNow();
            if (FILE* fp = _wfopen((cfg_.stateDir + L"\\lastpass.txt").c_str(), L"wb")) { fprintf(fp, "%lld", (long long)lastPassMs_.load()); fclose(fp); }
            LOG("[%s] 맞추기 시작 (언제: %s)", cfg_.name.c_str(), cfg_.dailyAt >= 0 ? "매일 정해진 시각" : cfg_.everyHours > 0 ? "지정 시간마다" : "원할 때만");
        }
        busy = true;
        uint64_t passAt = GetTickCount64();
        bool changed = RunPass();
        uint64_t took = GetTickCount64() - passAt;
        busy = false;
        static const uint64_t logMs = [] { wchar_t v[16]; return GetEnvironmentVariableW(L"MYDRIVE_PASSLOG_MS", v, 16) ? (uint64_t)_wtoi(v) : 3000ull; }();   // 이보다 오래 걸린 차례를 기록 (시험용으로 0)
        if (took >= logMs && !prof_[3]) LOG("[%s] 한 번 맞추기에 %.1f초 걸림 (중간에 멈춤)", cfg_.name.c_str(), took / 1000.0);
        else if (took >= logMs) LOG("[%s] 한 번 맞추기에 %.1f초 걸림 (NAS 목록 %.1f · PC 훑기 %.1f · 비교 %.1f · 옮기기 %.1f · 저장 등 %.1f, PC %zu개, 읽은 폴더 %s, 바뀌지 않아 검사 건너뜀 %d개)", cfg_.name.c_str(), took / 1000.0,
                             prof_[0] / 1000.0, (prof_[1] - prof_[0]) / 1000.0, (prof_[2] - prof_[1]) / 1000.0, (prof_[3] - prof_[2]) / 1000.0,
                             prof_[3] ? (took - prof_[3]) / 1000.0 : 0.0, local_.size(), scanDirs_ < 0 ? "전체" : std::to_string(scanDirs_).c_str(), fastN_);
        polledSeq_ = seq_;
        chain_ = (cfg_.everyHours != 0 || cfg_.dailyAt >= 0 || cfg_.idlePcMin > 0 || cfg_.idleDisk) && changed;   // 바뀐 게 있었으면 이어서 한 번 더 (다 맞출 때까지)
        if (stop_) break;
        // 바뀐 게 있었으면 곧바로 한 번 더 (연쇄 변경), 없으면 알림이 올 때까지 (최대 재검사 간격)
        uint64_t waitMs = changed ? 200 : (stats.online ? (uint64_t)std::max(1, cfg_.rescanMin) * 60000 : 15000);
        if (retryAt_) { uint64_t n = GetTickCount64(); waitMs = std::min<uint64_t>(waitMs, retryAt_ > n ? retryAt_ - n : 0); }
        if (uint64_t fr = failRetryAt_.exchange(0)) { uint64_t n = GetTickCount64(); waitMs = std::min<uint64_t>(waitMs, fr > n ? fr - n : 0); }
        DWORD w = WaitForSingleObject(wake_, (DWORD)waitMs);
        if (w == WAIT_OBJECT_0) {
            // 수정이 몰리는 동안은 미룸: quietSec 동안 조용해질 때까지 (최대 maxDeferSec)
            uint64_t t0 = GetTickCount64();
            while (!stop_ && GetTickCount64() - t0 < (uint64_t)cfg_.maxDeferSec * 1000 &&
                   WaitForSingleObject(wake_, (DWORD)std::max(1, cfg_.quietSec) * 1000) == WAIT_OBJECT_0) {}
        }
        // 한 번 맞추기가 오래 걸리는 큰 폴더: 다음 차례까지 그만큼은 쉼 (최대 5초) — 계속 바뀌는 동안 CPU 를 다 쓰지 않게
        uint64_t gap = std::min<uint64_t>(took, 5000);
        while (!stop_ && GetTickCount64() - (passAt + took) < gap) Sleep(100);
    }
}

void Engine::PollMain() {
    while (!stop_) {
        std::string ep = epoch_;
        int64_t since = std::max<int64_t>(polledSeq_, seq_);
        if (ep.empty()) { Sleep(1000); continue; }
        Resp r = poll_.Req(L"GET", "/v1/changes", {{"root", cfg_.name}, {"epoch", ep}, {"since", std::to_string(since)}, {"wait", "50"}}, nullptr, 70);
        if (stop_) break;
        if (!r.ok()) { Sleep(5000); continue; }
        json j = json::parse(r.body, nullptr, false);
        if (j.is_discarded()) { Sleep(2000); continue; }
        if (j.value("reset", false) || !j["changes"].empty()) {
            polledSeq_ = j.value("seq", since);
            SetEvent(wake_);
        }
    }
}

void Engine::WatchMain() {
    watchDir_ = CreateFileW(Abs(cfg_.localRoot, "").c_str(), FILE_LIST_DIRECTORY, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                            nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (watchDir_ == INVALID_HANDLE_VALUE) return;
    std::vector<char> buf(64 * 1024);
    scanAll_ = true; watchOk_ = true;   // 감시를 시작하기 전에 바뀐 것은 전체 훑기로 잡음
    while (!stop_) {
        DWORD got = 0;
        BOOL ok = ReadDirectoryChangesW(watchDir_, buf.data(), (DWORD)buf.size(), TRUE,
                                        FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_DIR_NAME | FILE_NOTIFY_CHANGE_SIZE | FILE_NOTIFY_CHANGE_LAST_WRITE |
                                        (cloudOn_ ? FILE_NOTIFY_CHANGE_ATTRIBUTES : 0),   // 탐색기의 '항상 유지'/'공간 확보'
                                        &got, nullptr, nullptr);
        if (!ok || stop_) break;
        if (got == 0) scanAll_ = true;   // 알림이 넘쳐서 무엇이 바뀌었는지 모름 → 전체를 읽음
        // 우리 임시 파일만 바뀐 것이면 무시
        bool real = got == 0;
        for (auto* e = (FILE_NOTIFY_INFORMATION*)buf.data(); got && e;) {
            std::wstring n(e->FileName, e->FileNameLength / sizeof(wchar_t));
            auto s = n.rfind(L'\\');
            if (!SkipLocalName(s == std::wstring::npos ? n : n.substr(s + 1))) real = true;
            {   // 바뀐 항목이 든 폴더를 적어 둠 (다음 훑기에서 이 폴더만 다시 읽음)
                std::string par = s == std::wstring::npos ? std::string() : U(n.substr(0, s)); for (auto& c : par) if (c == '\\') c = '/';
                std::lock_guard<std::mutex> g(ddMu_);
                if (dirtyDirs_.size() < 5000) dirtyDirs_.insert(std::move(par)); else scanAll_ = true;
            }
            if (e->Action == FILE_ACTION_REMOVED || e->Action == FILE_ACTION_RENAMED_OLD_NAME) {
                std::string rel = U(n); for (auto& c : rel) if (c == '\\') c = '/';
                std::lock_guard<std::mutex> g(xMu_);
                for (auto& [p, x] : xfers_) if (x->up && (p == rel || Under(p, rel))) { x->gone = true; x->cancel = true; }
            }
            if (!e->NextEntryOffset) break;
            e = (FILE_NOTIFY_INFORMATION*)((char*)e + e->NextEntryOffset);
        }
        if (real) SetEvent(wake_);
    }
    watchOk_ = false; scanAll_ = true;
    CloseHandle(watchDir_); watchDir_ = INVALID_HANDLE_VALUE;
}

} // namespace md
