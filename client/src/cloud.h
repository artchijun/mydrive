// cloud.h — 탐색기 연동 (Windows Cloud Files API: OneDrive · Synology Drive 와 같은 방식)
//   · 동기화 폴더를 탐색기에 등록 → 왼쪽 탐색 창 항목, 파일마다 상태 아이콘(구름/체크/맞추는 중)
//   · 온라인 전용 파일(자리표시 파일): 목록만 두고 열 때 NAS 에서 받음
//   · '항상 이 기기에 유지' / '공간 확보' (탐색기 기본 메뉴)
#pragma once
#include "util.h"
#include <functional>

namespace md::cloud {

// 탐색기에 등록 (id 는 연결 이름). 이미 있으면 정보만 고침.
bool Register(const std::wstring& id, const std::wstring& path, const std::wstring& display, const std::wstring& icon);
bool Unregister(const std::wstring& id);
bool IsRegistered(const std::wstring& path);
std::vector<std::wstring> RegisteredIds();   // Mydrive 가 등록한 것
// 탐색 창에는 'Mydrive' 하나만: 짝 폴더로 가는 '폴더 바로 가기'를 hubDir 에 모아 그 폴더를 보여 줌.
// 짝마다 Windows 가 만드는 탐색 창 항목은 숨김. pairs = (이름, PC 폴더)
void NavHub(const std::vector<std::pair<std::wstring, std::wstring>>& pairs, const std::wstring& hubDir, const std::wstring& icon);
void NavHubRemove(const std::wstring& hubDir);
// 동기화 폴더 자체(맨 위)의 상태 표시: 다 맞으면 체크, 아니면 맞추는 중 — 탐색 창 'Mydrive' 안의 그 작업 항목에도 같이
bool SetRootInSync(const std::wstring& id, const std::wstring& root, bool inSync);

// 열 때 받기: fetch(offset, sink) 가 offset 부터 끝까지 내용을 sink 로 흘려 보냄
using FetchFn = std::function<bool(const std::wstring& absPath, int64_t offset, const std::function<bool(const char*, size_t)>& sink)>;
struct Connection { int64_t key = 0; };
bool Connect(const std::wstring& path, FetchFn fetch, Connection& out);
void Disconnect(Connection& c);

// 자리표시 파일
bool CreatePlaceholder(const std::wstring& parentAbs, const std::wstring& leaf, bool dir, int64_t size, int64_t mtimeNs, const std::string& identity);
bool UpdateDehydrated(const std::wstring& abs, int64_t size, int64_t mtimeNs, const std::string& identity);   // NAS 판이 바뀜 → 메타데이터만 새로
bool MarkInSync(const std::wstring& abs, bool dir, const std::string& identity);   // 보통 파일이면 자리표시로 바꾸고, 맞춰짐 표시
bool Hydrate(const std::wstring& abs);     // 내용 받기 (항상 유지)
bool ClearPin(const std::wstring& abs, bool dir, bool recurse = false);   // '항상 유지'/'공간 확보' 표시를 지움
bool PinDir(const std::wstring& abs);   // 폴더와 그 안 전부를 '항상 유지'로
bool PinOne(const std::wstring& abs, bool dir);
bool SetKeep(const std::wstring& abs, bool keep);   // 오른쪽 클릭 '항상 이 장치에 유지'(true) / '공간 확보'(false) — 폴더면 안쪽 전부   // 이 항목 하나만 '항상 유지'로 (항상 유지 폴더에 새로 생긴 것)
bool Dehydrate(const std::wstring& abs);   // 공간 확보
bool Revert(const std::wstring& abs, bool dir);   // 자리표시를 보통 파일로 되돌림 (연동을 끌 때, 내용이 다 있어야 함)

// 파일 속성으로 상태 판단
inline bool IsCloudReparse(DWORD attr, DWORD tag) { return (attr & FILE_ATTRIBUTE_REPARSE_POINT) && (tag & 0xFFFF0FFF) == 0x9000001A; }
inline bool IsDehydrated(DWORD attr) { return (attr & (FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS | FILE_ATTRIBUTE_OFFLINE)) != 0; }
inline bool IsPinned(DWORD attr) { return (attr & FILE_ATTRIBUTE_PINNED) != 0; }
inline bool IsUnpinned(DWORD attr) { return (attr & FILE_ATTRIBUTE_UNPINNED) != 0; }

} // namespace md::cloud
