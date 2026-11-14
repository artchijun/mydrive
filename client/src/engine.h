// engine.h — 연결 하나(로컬 폴더 ↔ NAS 공유 폴더)의 양방향 동기화
//
// 세 가지 목록을 비교한다
//   local_  : 지금 PC 폴더의 상태
//   remote_ : 지금 NAS 의 상태 (서버의 변경 기록으로 늘 최신)
//   base_   : 마지막으로 양쪽이 같았을 때의 상태
// base 와 비교해 어느 쪽이 바뀌었는지 판단 → 바뀐 쪽을 다른 쪽에 옮긴다.
// 양쪽 다 바뀌면 지우거나 덮지 않고 둘 다 남긴다 (충돌 사본).
#pragma once
#include "util.h"
#include "http.h"
#include "cloud.h"
#include <map>
#include <set>
#include <unordered_map>
#include <vector>
#include <thread>
#include <atomic>
#include <functional>
#include <mutex>
#include <condition_variable>
#include <deque>
#include <memory>

namespace md {

struct LocalE  { bool d = false; int64_t s = 0, m = 0; uint64_t id = 0; uint32_t a = 0; uint8_t pin = 0; int64_t at = 0; };   // a: 파일 속성, pin: 0 없음 / 1 항상 유지 / 2 공간 확보 (위 폴더에서 물려받음 포함), at: 마지막으로 연 시각
struct RemoteE { bool d = false; int64_t s = 0, m = 0; uint64_t i = 0; };
struct BaseE   { bool d = false; int64_t ls = 0, lm = 0; uint64_t lid = 0; int64_t rs = 0, rm = 0; uint64_t ri = 0; };

struct Config {
    std::string name;            // 연결 이름 (서버의 root 이름과 같게)
    std::wstring localRoot;      // 예: C:\MydriveTest
    std::wstring serverUrl;      // 예: http://192.168.0.6:8642
    std::string token;
    std::wstring stateDir;       // 상태 파일 저장 위치
    int massDeleteLimit = 50;    // 한 번에 이만큼 넘게 지우면 멈추고 확인
    std::wstring pcName;         // 충돌 사본 이름에 넣을 PC 이름
    // 필터 (제외한 것은 양쪽 모두 건드리지 않음 — 지우지도 옮기지도 않음)
    std::vector<std::wstring> excludes;      // 이름 패턴: *.bak, node_modules, ~*  (경로의 어느 부분이든 맞으면 제외)
    int64_t maxFileSize = 0;                 // 이보다 큰 파일은 제외 (0 = 제한 없음)
    std::vector<std::string> skipDirs;       // 선택 동기화: 이 폴더(연결 안 경로)는 맞추지 않음
    std::vector<std::string> priorityDirs;   // 이 폴더 안 파일을 먼저 맞춤
    int rank = 0;                             // 폴더 짝 순서 (0 = 맨 위 = 가장 먼저). 위 짝이 옮기는 동안 아래 짝은 기다림
    int quietSec = 2;                        // 마지막 변경 뒤 이만큼 조용해지면 맞춤 (수정이 몰리면 미룸)
    int maxDeferSec = 30;                    // 그래도 이 시간이 지나면 맞춤
    int rescanMin = 5;                       // 알림이 없어도 이 간격으로 전체를 다시 확인
    bool onDemand = false;                   // 받는 방식: true = 필요할 때 받기 (새 파일은 자리만) / false = 모두 받아 두기. 탐색기 연동은 늘 켬
    std::vector<std::string> keepDirs;       // 항상 이 PC에 둘 폴더 (내용까지 늘 받아 둠) — 나머지는 요청 시 받기
    int autoFreeDays = 0;                    // 이만큼 안 연 파일은 온라인 전용으로 (0 = 끔)
};

struct Stats {
    std::atomic<int64_t> up{0}, down{0}, delLocal{0}, delRemote{0}, moved{0}, conflicts{0}, errors{0};
    std::atomic<int> heldDeletes{0};      // 확인을 기다리는 대량 삭제 개수
    std::atomic<int> pending{0};          // 이번에 할 일 남은 개수
    std::atomic<bool> online{false};
    std::atomic<int> waiting{0};          // 다른 프로그램이 쓰는 중이라 기다리는 파일 수
};

// 화면에 보여 줄 것: 지금 옮기는 파일 / 막힌 파일 / 파일을 잡고 있는 프로그램
struct Transfer { std::string p; bool up = false; int64_t total = 0, done = 0; uint64_t startTick = 0; };
struct StuckInfo { std::string p; std::string why; uint64_t since = 0; int fails = 0; bool locked = false; };
struct Holder { DWORD pid = 0; std::wstring name; FILETIME start{}; };
std::vector<Holder> FileHolders(std::wstring absPath);

// 탐색기 '동기화 상태 보기': 한 파일 또는 폴더 아래 전체가 NAS와 맞는지
struct ItemStatus {
    bool found = false, dir = false, excluded = false;
    int files = 0, dirs = 0, synced = 0, upWait = 0, downWait = 0, delWait = 0, both = 0, checkWait = 0;
    int onlineOnly = 0, transferring = 0, failing = 0, locked = 0, excludedN = 0;
    int64_t bytes = 0, localBytes = 0;
    std::vector<std::pair<std::string, std::string>> issues;   // (경로, 까닭) — 앞의 몇 개만
};

// 옮길 일 하나 (전송 통로에서 처리)
struct Job {
    enum Kind { Up, Down, Compare, Hydrate } kind;
    std::string p;
    LocalE l; RemoteE r;
    bool hasL = false;      // PC 에 이미 있음
    bool ph = false;        // 받지 않고 자리표시(온라인 전용)만 만들거나 고침
    bool hasBase = false; BaseE b;
    int64_t size = 0;
};

class Engine {
public:
    explicit Engine(Config c);
    ~Engine();
    bool RunPass();                       // 한 번 맞추기 (변경이 있었으면 true)
    void Start();                         // 감시하며 계속 맞추기 (스레드)
    void Stop();
    void ConfirmMassDelete() { confirmDelete_ = true; Kick(); }
    void RestoreMassDelete() { restoreDelete_ = true; Kick(); }   // 보류된 삭제를 하지 않고, 사라진 쪽에 다시 만듦
    void Kick() { SetEvent(wake_); }
    bool CancelTransfer(const std::string& p);   // 지금 옮기는 파일 하나를 멈추고, 그 판은 다시 옮기지 않음
    void ClearCanceled() { std::lock_guard<std::mutex> g(xMu_); canceled_.clear(); }   // [지금 동기화]: 취소한 것도 다시 맞춤
    // 일시 중지: minutes 동안 (0 = 다시 시작)
    void Pause(int minutes) { pausedUntil_ = minutes > 0 ? GetTickCount64() + (uint64_t)minutes * 60000 : 0; Kick(); }
    bool Paused() const { return pausedUntil_ > GetTickCount64(); }
    uint64_t PausedLeftMs() const { uint64_t t = pausedUntil_, n = GetTickCount64(); return t > n ? t - n : 0; }
    std::atomic<bool> busy{false};
    const Config& cfg() const { return cfg_; }
    Stats stats;
    // 창에 보여 줄 활동 한 줄: 종류, 경로, 덧붙임 (다른 스레드에서 불림)
    std::function<void(const std::string& kind, const std::string& path, const std::string& extra)> onEvent;
    std::vector<Transfer> Transfers();      // 지금 옮기는 중 (진행률)
    std::vector<StuckInfo> StuckList();     // 다른 프로그램이 잡고 있거나 계속 실패하는 파일
    ItemStatus Status(const std::string& rel);   // rel: 연결 안 경로 ("" = 연결 전체)
private:
    Config cfg_;
    Http http_, poll_, fetch_;   // fetch_: 탐색기에서 온라인 전용 파일을 열 때
    // 탐색기 연동 (Cloud Files)
    bool cloudOn_ = false;       // 자리표시·상태 아이콘을 다룸
    bool unregister_ = false;    // 연동을 끄는 중: 온라인 전용 파일을 모두 받은 뒤 등록을 풂
    uint8_t rootPin_ = 0;
    cloud::Connection cc_;
    std::mutex spMu_;
    std::set<std::string> syncPending_;   // '맞춤' 표시를 못 한 항목 (다른 프로그램이 잡고 있었음) — 다음에 다시
    std::set<std::string> keepApplied_;   // '항상 둘 폴더'로 표시해 둔 폴더 (설정에서 빠지면 표시를 풂)
    bool keepLoaded_ = false;
    // 마지막으로 열어서(받아서) 쓴 시각 — Windows 가 '마지막 접근 시각'을 안 남기는 PC 가 많아서 직접 기록
    std::mutex openMu_;
    std::map<std::string, int64_t> opened_;
    bool openedDirty_ = false;
    void NoteOpened(const std::string& p);
    int64_t OpenedAt(const std::string& p);
    void SaveOpened();
    void LoadOpened();
    uint64_t lastAutoFree_ = 0;
    void ApplyKeepDirs();
    void CloudStart();
    void CloudStop();
    void InSync(const std::string& p, bool d);
    bool Placeholder(const Job& j);
    bool CloudFetch(const std::wstring& abs, int64_t off, const std::function<bool(const char*, size_t)>& sink);
    uint8_t ParentPin(const std::string& p) const;
    std::map<std::string, LocalE> local_;
    std::map<std::string, RemoteE> remote_;
    std::map<std::string, BaseE> base_;
    std::string epoch_;
    int64_t seq_ = 0;
    std::atomic<int64_t> polledSeq_{0};
    std::atomic<bool> stop_{false}, confirmDelete_{false}, restoreDelete_{false};
    std::atomic<uint64_t> pausedUntil_{0};
    HANDLE wake_ = nullptr;
    // 상태(local_/remote_/base_)를 여러 통로가 함께 쓰므로 잠금
    std::recursive_mutex mu_;
    // 큰 파일 통로: 맞추기 차례와 상관없이 뒤에서 옮김 → 작은 파일이 큰 파일 뒤에 줄 서지 않음
    std::set<std::string> inflight_;
    std::deque<Job> bigQ_;
    std::mutex qMu_;
    std::condition_variable qCv_;
    std::vector<std::thread> bigWorkers_;
    void BigWorker();
    bool RunJob(const Job& j);
    std::thread loop_, pollThread_, watchThread_;
    HANDLE watchDir_ = INVALID_HANDLE_VALUE;
    bool dirty_ = false;
    int lastHeld_ = -1;
    int rootSync_ = 0;   // 맨 위 폴더에 알린 상태: 0 아직, 1 맞춰짐, 2 맞추는 중
    std::map<std::string, std::pair<std::string, uint64_t>> waitNote_;   // 기다리는 파일 → (알린 내용, 처음 본 시각) — xMu_ 로 보호
    struct XState { bool up; int64_t total; std::atomic<int64_t> done{0}; uint64_t start; std::atomic<bool> cancel{false}, gone{false}; int64_t sigS = -1, sigM = 0; };   // gone: 올리는 중에 PC에서 지움
    struct Canceled { bool up; int64_t s, m; };   // 취소한 파일: 그 판(크기·시각)이 그대로인 동안은 다시 옮기지 않음
    std::map<std::string, Canceled> canceled_;   // xMu_ 로 보호
    struct FailInfo { int n = 0; uint64_t first = 0, last = 0; std::string msg; };
    std::mutex xMu_;
    std::map<std::string, std::shared_ptr<XState>> xfers_;
    std::map<std::string, FailInfo> fails_;
    std::atomic<uint64_t> failRetryAt_{0};
    std::shared_ptr<XState> BeginXfer(const std::string& p, bool up, int64_t total, int64_t sigS = -1, int64_t sigM = 0);
    void EndXfer(const std::string& p);
    uint64_t retryAt_ = 0;                          // 기다리는 파일을 다시 볼 시각
    bool Excluded(const std::string& p, bool d, int64_t size) const;
    bool Busy(const std::string& p, bool forDownload);   // 다른 프로그램이 쓰는 중이면 알리고 true
    std::set<std::string> warned_;

    bool RefreshRemote();
    bool ScanLocal();
    void ApplyRemoteEntry(const std::string& p, const RemoteE& e);
    bool DetectRenames();
    // 실행
    bool Upload(const std::string& p, const LocalE& l, const BaseE* b);
    bool Download(const std::string& p, const RemoteE& r, const BaseE* b);
    bool MkdirRemote(const std::string& p);
    bool MkdirLocal(const std::string& p, const RemoteE& r);
    bool DeleteRemote(const std::string& p, const BaseE& b);
    bool DeleteLocal(const std::string& p);
    bool KeepBothLocal(const std::string& p);   // 로컬 판을 충돌 사본 이름으로 바꿔 둠
    void HealInSync();                          // 켠 뒤 한 번: 맞춰진 항목인데 '맞춰짐' 표시가 빠진 것에 다시 붙임
    bool healed_ = false;
    int SameContent(const std::string& p);      // 크기·시각이 같아도 내용(SHA-256)까지 같은지: 1 같음, 0 다름, -1 확인 못 함
    bool StatLocal(const std::string& p, LocalE& out);
    void SetBase(const std::string& p, const LocalE& l, const RemoteE& r);
    void Rekey(const std::string& from, const std::string& to);   // 폴더째 이름이 바뀐 경우 아래 항목까지
    void Load();
    void Save();
    void Event(const char* kind, const std::string& p, const std::string& extra = "");
    void LoopMain();
    void PollMain();
    void WatchMain();
};

} // namespace md
