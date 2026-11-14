# 빌드 후 설치 파일을 "YYYYMMDD HHMM Mydrive 설치.exe" 이름으로 프로젝트 폴더에 둠 (이전 것은 _이전 설치파일 로)
$ErrorActionPreference = 'Stop'
$client = $PSScriptRoot
$env:VSCMD_SKIP_SENDTELEMETRY = '1'   # vctip 가 남아 있으면 -Wait 가 안 끝남
$proj = Split-Path $client -Parent
$log = "$env:TEMP\mydrive-build.txt"
# Start-Process -Wait 는 남아 있는 자식(vctip)까지 기다려서 안 끝남 → 빌드 프로세스만 기다림
$p = Start-Process cmd -ArgumentList '/c', "`"$client\build.cmd`"" -NoNewWindow -PassThru -RedirectStandardOutput $log
$null = $p.Handle   # 이걸 잡아 둬야 끝난 뒤 ExitCode 가 남음
$p.WaitForExit()
if ($p.ExitCode -ne 0) { Get-Content "$env:TEMP\mydrive-build.txt" | Select-String 'error' | Select -First 20; throw "빌드 실패" }
$old = Join-Path $proj '_이전 설치파일'
Get-ChildItem $proj -Filter '* Mydrive 설치.exe' | ForEach-Object {
    if (-not (Test-Path $old)) { New-Item -ItemType Directory $old | Out-Null }
    Move-Item $_.FullName $old -Force
}
$name = (Get-Date -Format 'yyyyMMdd HHmm') + ' Mydrive 설치.exe'
Copy-Item "$client\build\setup.exe" (Join-Path $proj $name)
"OK $name $((Get-Item (Join-Path $proj $name)).Length)"