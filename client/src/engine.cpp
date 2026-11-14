#include "engine.h"
#include "../third_party/json.hpp"
#include <shellapi.h>
#include <shlobj.h>
#include <bcrypt.h>
#pragma comment(lib, "bcrypt.lib")
#include <shlwapi.h>
#include <restartmanager.h>
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
}

// ---------- NAS 목록 ----------
static RemoteE FromJ(const json& j) {
    RemoteE e; e.d = j.value("d", false); e.s = j.value("s", (int64_t)0); e.m = j.value("m", (int64_t)0); e.i = j.value("i", (uint64_t)0); return e;
}

void Engine::ApplyRemoteEntry(const std::string& p, const RemoteE& e) { remote_[p] = e; }

bool Engine::RefreshRemote() {
    for (int round = 0; round < 50; round++) {
        if (!epoch_.empty()) {
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
        // 다시 받은 뒤에도 그 사이 변경을 이어 받도록 한 번 더 돈다
    }
    return true;
}

// ---------- 로컬 폴더 훑기 (폴더 하나를 한 번에 읽는 방식: 이름·크기·시각·파일 번호를 같이 받음) ----------
bool Engine::ScanLocal() {
    std::map<std::string, LocalE> out;
    std::vector<std::string> stack{""};
    std::vector<char> buf(256 * 1024);
    DWORD attr = GetFileAttributesW(Abs(cfg_.localRoot, "").c_str());
    if (attr == INVALID_FILE_ATTRIBUTES || !(attr & FILE_ATTRIBUTE_DIRECTORY)) {
        LOG("[%s] 로컬 폴더를 열 수 없음 — 이번에는 건너뜀 (지우지 않음)", cfg_.name.c_str());
        return false;
    }
    // '항상 유지'(1) / '공간 확보'(2): 폴더에 붙은 표시는 아래 항목이 물려받음
    auto ownPin = [](DWORD a) -> uint8_t { return cloud::IsPinned(a) ? 1 : cloud::IsUnpinned(a) ? 2 : 0; };
    rootPin_ = ownPin(attr);
    std::map<std::string, uint8_t> dirPin;
    while (!stack.empty()) {
        std::string dir = stack.back(); stack.pop_back();
        uint8_t parentPin = dir.empty() ? rootPin_ : dirPin[dir];
        HANDLE h = CreateFileW(Abs(cfg_.localRoot, dir).c_str(), FILE_LIST_DIRECTORY, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                               nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr);
        if (h == INVALID_HANDLE_VALUE) {
            if (dir.empty()) return false;
            // 읽을 수 없는 폴더: 그 아래는 "모름" — base 에 있는 항목을 그대로 둬서 지우는 것으로 오해하지 않게
            std::string pre = dir + "/";
            for (auto it = base_.lower_bound(pre); it != base_.end() && it->first.compare(0, pre.size(), pre) == 0; ++it)
                out[it->first] = LocalE{it->second.d, it->second.ls, it->second.lm, it->second.lid};
            continue;
        }
        FILE_INFO_BY_HANDLE_CLASS cls = FileIdBothDirectoryRestartInfo;
        while (GetFileInformationByHandleEx(h, cls, buf.data(), (DWORD)buf.size())) {
            cls = FileIdBothDirectoryInfo;
            auto* e = (FILE_ID_BOTH_DIR_INFO*)buf.data();
            for (;;) {
                std::wstring n(e->FileName, e->FileNameLength / sizeof(wchar_t));
                // 링크(정션 등)는 따라가지 않음. 단, 탐색기 연동의 자리표시 파일(클라우드 파일)은 보통 파일처럼 다룸
                // (FILE_ATTRIBUTE_REPARSE_POINT 이면 EaSize 칸에 재분석 태그가 들어 있음)
                bool link = (e->FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) && !cloud::IsCloudReparse(e->FileAttributes, e->EaSize);
                if (n != L"." && n != L".." && !SkipLocalName(n) && !link) {
                    std::string rel = dir.empty() ? U(n) : dir + "/" + U(n);
                    LocalE l;
                    l.d = (e->FileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
                    l.s = l.d ? 0 : e->EndOfFile.QuadPart;
                    l.m = FtToNs(*(FILETIME*)&e->LastWriteTime);
                    l.id = (uint64_t)e->FileId.QuadPart;
                    l.a = e->FileAttributes;
                    l.at = FtToNs(*(FILETIME*)&e->LastAccessTime);
                    uint8_t own = ownPin(l.a);
                    l.pin = own ? own : parentPin;
                    // 이름 패턴·선택 동기화로 뺀 것은 아예 보지 않음 (크기 제한은 맞출 때 양쪽을 함께 봄)
                    if (!Excluded(rel, true, 0)) {
                        out[rel] = l;
                        if (l.d) { stack.push_back(rel); dirPin[rel] = l.pin; }
                    }
                }
                if (!e->NextEntryOffset) break;
                e = (FILE_ID_BOTH_DIR_INFO*)((char*)e + e->NextEntryOffset);
            }
        }
        CloseHandle(h);
    }
    local_ = std::move(out);
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

void Engine::SetBase(const std::string& p, const LocalE& l, const RemoteE& r) {
    std::lock_guard<std::recursive_mutex> g(mu_);
    base_[p] = BaseE{l.d, l.s, l.m, l.id, r.s, r.m, r.i};
    LocalE keep = l;
    if (!keep.pin) { auto it = local_.find(p); keep.pin = it != local_.end() ? it->second.pin : ParentPin(p); }   // 물려받은 표시는 유지
    local_[p] = keep; remote_[p] = r; dirty_ = true;
    InSync(p, l.d);   // 탐색기에 '맞춰짐' 표시
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
    if (r.status == 409) { Event("충돌", p, "그사이 NAS에서도 바뀜 — 다음 차례에 둘 다 남김"); return false; }
    if (!r.ok()) { stats.errors++; Event("올리기 실패", p, r.error.empty() ? std::to_string(r.status) + " " + r.body : r.error); return false; }
    json j = json::parse(r.body, nullptr, false);
    RemoteE re = FromJ(j);
    LocalE now;
    {
        std::lock_guard<std::recursive_mutex> g(mu_);
        bool here = StatLocal(p, now);
        if (here && now.s == l.s && SameTime(now.m, l.m)) SetBase(p, now, re);
        else if (!here) SetBase(p, l, re);   // 다 올린 뒤 지워짐 → 다음 차례에 NAS에서도 지움 (예전에는 PC로 도로 받아 와서 되살아났음)
        else { remote_[p] = re; dirty_ = true; }
    }   // 올리는 사이 또 바뀜 → 다음 차례에 다시 올림
    stats.up++;
    Event("↑ 올림", p, std::to_string(l.s) + " B");
    return true;
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

// ---------- 이름 바꾸기 알아채기 (지우고 새로 올리는 대신 이름만 바꿈) ----------
bool Engine::DetectRenames() {
    bool any = false;
    std::unordered_map<uint64_t, std::string> lById, rByIno;
    for (auto& [p, l] : local_) if (l.id && !Excluded(p, l.d, l.s)) lById[l.id] = p;
    for (auto& [p, r] : remote_) if (r.i && !Excluded(p, r.d, r.s)) rByIno[r.i] = p;
    std::vector<std::string> keys;
    for (auto& [p, b] : base_) keys.push_back(p);
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

// ---------- 한 번 맞추기 ----------
bool Engine::RunPass() {
    std::unique_lock<std::recursive_mutex> L(mu_);   // 작은 파일을 옮기는 동안만 풀어 줌
    retryAt_ = 0;
    if (!RefreshRemote()) return false;
    if (!ScanLocal()) return false;
    bool any = DetectRenames();

    enum Act { None, Up, Down, MkR, MkL, DelR, DelL, Conflict, SyncBase, DropBase, ReplR, ReplL, Compare };   // Repl: 파일↔폴더로 종류가 바뀜
    struct Item { std::string p; Act a; };
    std::vector<Item> items;

    std::set<std::string> all;
    for (auto& [p, _] : local_) all.insert(p);
    for (auto& [p, _] : remote_) all.insert(p);
    for (auto& [p, _] : base_) all.insert(p);

    std::set<std::string> exclUnderL, exclUnderR;   // 필터로 뺀 파일이 들어 있는 폴더 (통째로 지우지 않음)
    std::map<std::wstring, std::string> caseWinner;  // 소문자 이름 → 처음 나온 NAS 경로 (all 은 정렬돼 있어 늘 같은 것이 이김)
    for (auto& [p, l] : local_) { std::wstring lw = W(p); CharLowerBuffW(lw.data(), (DWORD)lw.size()); caseWinner.emplace(lw, p); }   // PC 에 이미 있는 이름이 우선
    for (auto& [p, b] : base_) { std::wstring lw = W(p); CharLowerBuffW(lw.data(), (DWORD)lw.size()); caseWinner.emplace(lw, p); }
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
    for (auto& p : all) {
        auto li = local_.find(p); auto ri = remote_.find(p); auto bi = base_.find(p);
        const LocalE* l = li == local_.end() ? nullptr : &li->second;
        const RemoteE* r = ri == remote_.end() ? nullptr : &ri->second;
        const BaseE* b = bi == base_.end() ? nullptr : &bi->second;
        if (r && (BadWinName(p) || SkipRemoteRel(p))) {
            if (!l && !b) { if (warned_.insert(p).second) Event("건너뜀", p, "Windows 에서 쓸 수 없는 이름"); }
            continue;
        }
        // NAS 에 대소문자만 다른 이름이 둘 이상 (Windows 에서는 같은 이름): 정렬해서 첫 것만 맞추고 나머지는 건너뜀
        if (r && !l && !b) {
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
        bool lc, rc;   // 바뀌었나
        if (!b) { lc = l != nullptr; rc = r != nullptr; }
        else {
            lc = !l || l->d != b->d || (!b->d && (l->s != b->ls || !SameTime(l->m, b->lm)));
            rc = !r || r->d != b->d || (!b->d && (r->s != b->rs || r->m != b->rm));
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
        else if (cloudOn_ && l && b) {
            // 맞춰져 있는 항목: 탐색기에서 고른 '항상 유지'/'공간 확보'를 따름
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
        }
    }

    // 대량 삭제 확인: 지울 항목 수(아래 항목 포함)가 한도를 넘으면 그 방향의 삭제는 멈추고 확인을 기다림
    int nDelL = 0, nDelR = 0;
    for (auto& it : items) { if (it.a == DelL) nDelL++; if (it.a == DelR) nDelR++; }
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
    bool holdL = nDelL > cfg_.massDeleteLimit && !confirmDelete_;
    bool holdR = nDelR > cfg_.massDeleteLimit && !confirmDelete_;
    stats.heldDeletes = (holdL ? nDelL : 0) + (holdR ? nDelR : 0);
    if (stats.heldDeletes != lastHeld_ && stats.heldDeletes > 0)
        Event("⏸ 대량 삭제 보류", cfg_.name, std::to_string(nDelR) + "개 NAS 삭제 / " + std::to_string(nDelL) + "개 PC 삭제 — 확인을 누르면 진행");
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
        if (stop_) return any;
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
        if (stop_) return any;
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
                if (pin != 1 && (!j.hasL || cloud::IsDehydrated(j.l.a))) j.ph = true;
            }
            j.size = j.ph ? 0 : std::max(j.l.s, j.r.s);
            if (j.size >= kBig && !bigWorkers_.empty()) {
                inflight_.insert(p);
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
    // 작은 파일: 통로 3개로 함께 옮김 (하나가 느려도 나머지는 계속)
    if (!smallJobs.empty()) {
        std::atomic<size_t> next{0};
        std::atomic<bool> anyJob{false};
        auto work = [&] {
            for (size_t k; !stop_ && (k = next++) < smallJobs.size();) {
                while (UpperBusy(cfg_.rank) && !stop_) Sleep(200);   // 우선순위 '높음' 연결이 먼저
                if (RunJob(smallJobs[k])) anyJob = true;
                stats.pending--;
            }
        };
        RankGuard hg2(cfg_.rank);
        L.unlock();
        int nThreads = (int)std::min<size_t>(3, smallJobs.size());
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
            inflight_.insert(p);
            { std::lock_guard<std::mutex> g(qMu_); bigQ_.push_back(j); }
            qCv_.notify_one();
        }
        for (auto& p : dehy) {
            if (stop_) return any;
            std::wstring a = Abs(cfg_.localRoot, p);
            if (!cloud::Dehydrate(a)) continue;   // 쓰는 중이면 다음에
            if (cloud::IsUnpinned(local_[p].a)) cloud::ClearPin(a, false);
            LocalE now; if (StatLocal(p, now)) local_[p] = now;
            Event("☁ 공간 확보", p, std::to_string(now.s) + " B");
            any = true;
        }
        for (auto& p : autoFree) {
            if (stop_) return any;
            if (!cloud::Dehydrate(Abs(cfg_.localRoot, p))) continue;   // 열려 있으면 그대로 둠
            LocalE now; if (StatLocal(p, now)) local_[p] = now;
            Event("☁ 자동 공간 확보 (오래 안 씀)", p, std::to_string(now.s) + " B");
            any = true;
        }
        for (auto& p : unpinDirs) cloud::ClearPin(Abs(cfg_.localRoot, p), true);
        if (!unregister_) ApplyKeepDirs();
        for (auto& p : conv) { if (stop_) return any; InSync(p, local_[p].d); }
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
        if (stop_) return any;
        auto& p = it.p;
        if (keep.count(p)) { Event("폴더 유지", p, "안에 새로 생기거나 고친 파일이 있음"); stats.pending--; continue; }
        if ((it.a == DelL && exclUnderL.count(p)) || (it.a == DelR && exclUnderR.count(p))) { Event("폴더 유지", p, "필터로 뺀 파일이 들어 있음"); stats.pending--; continue; }
        bool under = false;
        for (std::string q = Parent(p); !q.empty(); q = Parent(q)) if (gone.count(q)) { under = true; break; }
        if (under) { base_.erase(p); continue; }
        if (it.a == DelL && !local_.count(p)) { stats.pending--; continue; }   // 위에서 이미 지워짐
        if (it.a == DelL && !holdL && !local_[p].d && Busy(p, true)) { stats.pending--; continue; }
        if (it.a == DelL && !holdL) { if (DeleteLocal(p)) { gone.insert(p); any = true; } }
        if (it.a == DelR && !holdR) { auto bi = base_.find(p); if (bi != base_.end() && DeleteRemote(p, bi->second)) { gone.insert(p); any = true; } }
        stats.pending--;
    }
    if (confirmDelete_ && (nDelL || nDelR)) confirmDelete_ = false;
    stats.pending = 0;
    { std::lock_guard<std::mutex> g(xMu_); stats.waiting = (int)waitNote_.size(); }
    if (dirty_) Save();
    SaveOpened();
    if (!healed_ && !stop_) { healed_ = true; L.unlock(); HealInSync(); }
    return any;
}

// 예전 판에서 이름을 바꾸거나 옮긴 파일은 Windows 가 지운 '맞춰짐' 표시가 그대로 빠져 있어 탐색기에 '보류 중'으로 보임.
// 켠 뒤 첫 차례가 끝나면 한 번, 양쪽이 마지막 상태와 같은 자리표시만 골라 표시를 확인하고 빠졌으면 붙임 (파일은 받지 않음).
void Engine::HealInSync() {
    if (!cloudOn_ || unregister_) return;
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
            if (!stop_) RunJob(j);
        }
        { std::lock_guard<std::recursive_mutex> g(mu_); inflight_.erase(j.p); }
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
    CloudStart();
    for (int i = 0; i < 2; i++) bigWorkers_.emplace_back([this] { BigWorker(); });
    loop_ = std::thread([this] { LoopMain(); });
    pollThread_ = std::thread([this] { PollMain(); });
    watchThread_ = std::thread([this] { WatchMain(); });
}
void Engine::Stop() {
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

void Engine::LoopMain() {
    while (!stop_) {
        // 일시 중지 중: 풀리거나 다시 시작을 누를 때까지 기다림
        while (!stop_ && Paused()) WaitForSingleObject(wake_, (DWORD)std::min<uint64_t>(PausedLeftMs() + 50, 60000));
        if (stop_) break;
        busy = true;
        bool changed = RunPass();
        busy = false;
        polledSeq_ = seq_;
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
    while (!stop_) {
        DWORD got = 0;
        BOOL ok = ReadDirectoryChangesW(watchDir_, buf.data(), (DWORD)buf.size(), TRUE,
                                        FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_DIR_NAME | FILE_NOTIFY_CHANGE_SIZE | FILE_NOTIFY_CHANGE_LAST_WRITE |
                                        (cloudOn_ ? FILE_NOTIFY_CHANGE_ATTRIBUTES : 0),   // 탐색기의 '항상 유지'/'공간 확보'
                                        &got, nullptr, nullptr);
        if (!ok || stop_) break;
        // 우리 임시 파일만 바뀐 것이면 무시
        bool real = got == 0;
        for (auto* e = (FILE_NOTIFY_INFORMATION*)buf.data(); got && e;) {
            std::wstring n(e->FileName, e->FileNameLength / sizeof(wchar_t));
            auto s = n.rfind(L'\\');
            if (!SkipLocalName(s == std::wstring::npos ? n : n.substr(s + 1))) real = true;
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
    CloseHandle(watchDir_); watchDir_ = INVALID_HANDLE_VALUE;
}

} // namespace md
