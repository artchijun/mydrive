// webdrive.h — 웹드라이브: NAS 폴더를 PC에 파일을 두지 않고 드라이브(M:) 또는 PC 폴더로 붙임 (WinFsp)
//
//   목록: 붙일 때 NAS 전체 목록을 한 번 받고, 그 뒤로는 서버 변경 기록(/v1/changes)으로 늘 최신
//   읽기: 프로그램이 읽는 조각만 그때그때 NAS 에서 받음 (Range)
//   쓰기: 고치는 동안은 PC 임시 파일에 쓰고, 파일을 닫으면 NAS 로 올림
//   지우기: NAS 휴지통(#recycle)으로 / 이름 바꾸기·옮기기: NAS 안에서
//   WinFsp - Windows File System Proxy, Copyright (C) Bill Zissimopoulos — https://github.com/winfsp/winfsp
#pragma once
#include <string>
#include <memory>
#include <functional>
#include <atomic>

namespace md {

struct WebDriveCfg {
    std::string name;          // 연결 이름 (서버의 root)
    std::wstring serverUrl;
    std::string token;
    std::wstring mount;        // "M:" 같은 드라이브 문자, 또는 PC 폴더 전체 경로 (없거나 빈 폴더)
    std::wstring cacheDir;     // 고치는 중인 파일을 잠시 두는 곳 (%LOCALAPPDATA%\Mydrive\webdrive)
    std::wstring label;        // 탐색기에 보일 드라이브 이름 (비면 연결 이름)
};

class WebDrive {
public:
    explicit WebDrive(WebDriveCfg c);
    ~WebDrive();
    void Start();              // 뒤에서 목록을 받고 붙임 (실패하면 onEvent 로 알리고 다시 시도)
    void Stop();
    const WebDriveCfg& cfg() const;
    bool Mounted() const;      // 지금 드라이브가 붙어 있음
    bool Online() const;       // NAS 와 통신됨
    int Uploading() const;     // 올리는 중인 파일 수
    std::function<void(const std::string& kind, const std::string& path, const std::string& extra)> onEvent;
    struct Impl;
private:
    std::unique_ptr<Impl> d_;
};

bool WinFspInstalled();                     // WinFsp 드라이버가 깔려 있나
std::wstring FreeDriveLetters();            // 쓸 수 있는 드라이브 문자들 (예: L"MNOPQ...")

} // namespace md
