# NAS 서버 새 판 올리기 (DSM 로그인 필요 없음, 서버 0.6.13+): 서명된 실행 파일을 보내면 서버가 확인 후 스스로 바꾸고 다시 켜짐
#   1) 리눅스용으로 빌드: server/build.sh  →  2) 서명: python sign-server.py mydrived  (_서명열쇠\server-update.key 필요)
#   3) 보내기: powershell -File deploy-server.ps1 -bin mydrived-0.6.14 [-force]
#   같은 폴더에 <파일>.sig 가 있어야 함. 집 안 주소가 안 되면 인터넷 주소로. 연결 열쇠는 이 PC 의 Mydrive 설정에서 읽음.
#   GitHub 에 server-v<판> 릴리스(mydrived + mydrived.sig)를 올려 두면 NAS 가 6시간마다 스스로 받아 바꿈.
param([Parameter(Mandatory)][string]$bin, [switch]$force)
$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'
$cfg = Get-Content "$env:LOCALAPPDATA\Mydrive\config.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$f = if ([IO.Path]::IsPathRooted($bin)) { $bin } else { Join-Path $PSScriptRoot $bin }
$sig = [uri]::EscapeDataString((Get-Content "$f.sig" -Raw).Trim())
$h = @{ Authorization = "Bearer $($cfg.token)" }
foreach ($srv in @('http://192.168.0.6:8642', 'https://drive.cdluos.com')) {
    try { $null = Invoke-WebRequest "$srv/v1/ping" -UseBasicParsing -TimeoutSec 5 } catch { continue }
    $q = "sig=$sig" + $(if ($force) { '&force=1' } else { '' })
    try { $r = Invoke-WebRequest "$srv/v1/server/update?$q" -Method Post -InFile $f -Headers $h -UseBasicParsing -TimeoutSec 300; $r.Content }
    catch { $_.ErrorDetails.Message; exit 1 }
    foreach ($i in 1..24) { Start-Sleep 5; try { "지금 서버: " + (Invoke-WebRequest "$srv/v1/ping" -UseBasicParsing -TimeoutSec 5).Content; exit 0 } catch { } }
    '다시 켜지기를 2분 기다렸지만 응답 없음 — DSM 컨테이너 관리자에서 mydrived 확인'; exit 1
}
'서버에 닿지 않음'; exit 1
