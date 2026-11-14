# release.ps1 — PC 앱 새 판을 GitHub Releases 에 올림
#   1) src\version.h 의 판으로 빌드 (package.ps1)
#   2) 지금 커밋을 GitHub 에 push
#   3) 릴리스 v<판> 을 만들고 설치 파일 Mydrive-setup-<판>.exe 를 붙임 (GitHub 가 SHA-256 을 계산해 둠)
#   → 각 PC의 Mydrive가 6시간 안에(또는 설정 → 업데이트 → 지금 확인) 받아서 설치
# GitHub 로그인은 git 이 쓰는 것(Git Credential Manager)을 그대로 씀. 토큰을 파일에 적지 않음.
# 사용: powershell -File release.ps1 -Notes "바뀐 점 한 줄"
param([string]$Notes = "", [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$client = $PSScriptRoot
$repo = 'artchijun/mydrive'
$m = Select-String -Path "$client\src\version.h" -Pattern 'MYDRIVE_VERSION_STR "([\d.]+)"'
if (-not $m) { throw "version.h 에서 판을 찾지 못함" }
$ver = $m.Matches[0].Groups[1].Value
$tag = "v$ver"
if (-not $NoBuild) { & "$client\package.ps1" }
$name = "Mydrive-setup-$ver.exe"
$file = Join-Path $env:TEMP $name
Copy-Item "$client\build\setup.exe" $file -Force

Push-Location (Split-Path $client -Parent)
try {
    if (git status --porcelain) { throw "커밋하지 않은 변경이 있음 — 먼저 커밋하세요 (version.h 포함)" }
    git push origin HEAD 2>&1 | Out-Null
    $sha = (git rev-parse HEAD).Trim()
} finally { Pop-Location }

$cred = "protocol=https`nhost=github.com`n`n" | git credential fill
$tok = (($cred | Where-Object { $_ -like 'password=*' }) -replace '^password=', '')
if (-not $tok) { throw "GitHub 로그인 정보가 없음 — 한 번 git push 로 로그인하세요" }
$h = @{ Authorization = "Bearer $tok"; Accept = 'application/vnd.github+json'; 'User-Agent' = 'mydrive-release'; 'X-GitHub-Api-Version' = '2022-11-28' }
$api = "https://api.github.com/repos/$repo"

$rel = $null
try { $rel = Invoke-RestMethod -Headers $h "$api/releases/tags/$tag" } catch { }
if (-not $rel) {
    $body = @{ tag_name = $tag; target_commitish = $sha; name = "Mydrive $ver"; body = $Notes; draft = $false; prerelease = $false } | ConvertTo-Json
    $rel = Invoke-RestMethod -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) "$api/releases"
}
foreach ($a in $rel.assets) { if ($a.name -eq $name) { Invoke-RestMethod -Method Delete -Headers $h "$api/releases/assets/$($a.id)" | Out-Null } }
$up = "https://uploads.github.com/repos/$repo/releases/$($rel.id)/assets?name=$name"
$asset = Invoke-RestMethod -Method Post -Headers $h -ContentType 'application/octet-stream' -InFile $file $up
Remove-Item $file
"OK $tag  $name  $($asset.size) bytes  $($asset.digest)"
$rel.html_url
