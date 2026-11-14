// http.h — NAS 서버(mydrived)와 주고받기 (WinHTTP)
#pragma once
#include "util.h"
#include <winhttp.h>
#include <map>
#include <vector>
#include <atomic>
#include <mutex>
#include <set>
#include <functional>

namespace md {

struct Resp {
    int status = 0;          // 0 = 연결 실패
    std::string body;        // JSON 등 (파일로 받을 때는 비어 있음)
    std::string error;       // 연결 실패 사유
    std::string entryHdr;    // X-Mydrive-Entry (내려받기: s:m:i)
    bool ok() const { return status >= 200 && status < 300; }
};

// 모든 연결에 함께 적용되는 속도 제한 (바이트/초, 0 = 제한 없음)
void SetRateLimit(int64_t upBps, int64_t downBps);

class Http {
public:
    Http(const std::wstring& baseUrl, const std::string& token);
    ~Http();
    // q: 쿼리 (값은 UTF-8, 여기서 인코딩)
    Resp Req(const wchar_t* method, const std::string& path, const std::map<std::string, std::string>& q,
             const std::string* body = nullptr, int timeoutSec = 60);
    // 로컬 파일을 그대로 올림 (스트리밍). progress: 보낸 바이트
    Resp Upload(const std::string& path, const std::map<std::string, std::string>& q, const std::wstring& file,
                std::atomic<int64_t>* progress = nullptr);
    // 받아서 로컬 파일(임시)에 씀
    Resp Download(const std::string& path, const std::map<std::string, std::string>& q, const std::wstring& outFile,
                  std::atomic<int64_t>* progress = nullptr);
    // offset 부터 끝까지 받아 sink 로 넘김 (탐색기에서 온라인 전용 파일을 열 때). sink 가 false 면 멈춤
    Resp Stream(const std::string& path, const std::map<std::string, std::string>& q, int64_t offset,
                const std::function<bool(const char*, size_t)>& sink, std::atomic<int64_t>* progress = nullptr);
    std::atomic<bool> cancel{false};
    bool plain = false;   // true: 토큰·PC 이름 머리글을 붙이지 않음 (GitHub 등 바깥 서버)
    void Abort();
private:
    HINTERNET ses_ = nullptr, con_ = nullptr;
    std::mutex mu_;
    std::set<HINTERNET> live_;
    void Close(HINTERNET h);
    bool https_ = false;
    std::string token_;
    std::wstring basePath_;
    HINTERNET Open(const wchar_t* method, const std::string& path, const std::map<std::string, std::string>& q, int timeoutSec);
    static std::string Enc(const std::string& s);
    Resp Finish(HINTERNET h, HANDLE outFile, std::atomic<int64_t>* progress);
};

} // namespace md
