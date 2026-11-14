// mydrive-cli — 동기화 엔진 시험용 콘솔 프로그램
//   mydrive-cli --server http://localhost:8642 --token test --root test --local C:\MydriveTest [--state DIR] [--once] [--confirm-delete] [--limit N]
#include "engine.h"
#include <io.h>
#include <fcntl.h>

int wmain(int argc, wchar_t** argv) {
    SetConsoleOutputCP(CP_UTF8);
    md::Config c;
    bool once = false, confirm = false;
    c.quietSec = 0;   // 시험용: 방금 바뀐 파일도 바로 올림 (--quiet N 으로 바꿈)
    int64_t upK = 0, downK = 0;
    auto split = [](const std::wstring& s) { std::vector<std::wstring> v; size_t i = 0; while (i <= s.size()) { size_t j = s.find(L';', i); if (j == std::wstring::npos) j = s.size(); if (j > i) v.push_back(s.substr(i, j - i)); i = j + 1; } return v; };
    for (int i = 1; i < argc; i++) {
        std::wstring a = argv[i];
        auto next = [&]() { return i + 1 < argc ? std::wstring(argv[++i]) : std::wstring(); };
        if (a == L"--server") c.serverUrl = next();
        else if (a == L"--token") c.token = md::U(next());
        else if (a == L"--root") c.name = md::U(next());
        else if (a == L"--local") c.localRoot = next();
        else if (a == L"--state") c.stateDir = next();
        else if (a == L"--limit") c.massDeleteLimit = _wtoi(next().c_str());
        else if (a == L"--pc") c.pcName = next();
        else if (a == L"--exclude") c.excludes = split(next());
        else if (a == L"--maxsize") c.maxFileSize = _wtoi64(next().c_str()) * 1024 * 1024;
        else if (a == L"--skip") { for (auto& s : split(next())) c.skipDirs.push_back(md::U(s)); }
        else if (a == L"--prio") { for (auto& s : split(next())) c.priorityDirs.push_back(md::U(s)); }
        else if (a == L"--high") c.rank = 0;
        else if (a == L"--quiet") c.quietSec = _wtoi(next().c_str());
        else if (a == L"--up-kbps") upK = _wtoi64(next().c_str());
        else if (a == L"--down-kbps") downK = _wtoi64(next().c_str());
        else if (a == L"--once") once = true;
        else if (a == L"--confirm-delete") confirm = true;
    }
    if (c.serverUrl.empty() || c.name.empty() || c.localRoot.empty()) {
        fputs("사용: mydrive-cli --server URL --token T --root 이름 --local 폴더 [--state 폴더] [--once] [--confirm-delete] [--limit N]\n", stderr);
        return 2;
    }
    if (c.stateDir.empty()) c.stateDir = c.localRoot + L"\\..\\.mydrive-state";
    md::SetRateLimit(upK * 1024, downK * 1024);
    md::Engine e(c);
    if (confirm) e.ConfirmMassDelete();
    if (once) {
        e.RunPass();
        // 이름 바꾸기·충돌 뒤에는 한 번 더 돌아야 마무리됨
        for (int k = 0; k < 3 && e.RunPass(); k++) {}
        printf("RESULT up=%lld down=%lld delL=%lld delR=%lld moved=%lld conflicts=%lld errors=%lld held=%d online=%d waiting=%d\n",
               (long long)e.stats.up, (long long)e.stats.down, (long long)e.stats.delLocal, (long long)e.stats.delRemote,
               (long long)e.stats.moved, (long long)e.stats.conflicts, (long long)e.stats.errors, (int)e.stats.heldDeletes, (int)e.stats.online, (int)e.stats.waiting.load());
        return e.stats.online ? 0 : 1;
    }
    e.Start();
    LOG("감시 중 — 끝내려면 Ctrl+C");
    for (;;) Sleep(1000);
}
