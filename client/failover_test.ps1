# 집 밖 전환 시험
#  집 주소 = 127.0.0.1:18642 (처음엔 아무것도 없음 = 집 밖처럼), 밖 주소 = NAS의 Tailscale 주소
#  1) 켜면 Tailscale 주소로 바뀌는지, 그 상태로 파일이 NAS에 올라가는지
#  2) 127.0.0.1:18642 → 192.168.0.6:8642 중계를 켜면(집에 돌아옴) 집 주소로 돌아오는지
$cfgPath = "$env:LOCALAPPDATA\Mydrive\config.json"
$exe = "$env:LOCALAPPDATA\Programs\Mydrive\Mydrive.exe"
$alt = $env:MYDRIVE_ALT   # 밖에서 쓸 주소 (예: Tailscale http://100.x.x.x:8642)
function Conns { $p = (Get-Process Mydrive -ErrorAction SilentlyContinue | select -first 1).Id; if ($p) { (Get-NetTCPConnection -OwningProcess $p -State Established -ErrorAction SilentlyContinue | % { "$($_.RemoteAddress):$($_.RemotePort)" } | sort -Unique) -join ' ' } }
function SetCfg($server, $alt) { $j = gc $cfgPath -Raw -Encoding UTF8 | ConvertFrom-Json; $j.server = $server; if ($j.PSObject.Properties['serverAlt']) { $j.serverAlt = $alt } else { $j | Add-Member serverAlt $alt }; [IO.File]::WriteAllText($cfgPath, ($j | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding $false)) }
Copy-Item $cfgPath "$cfgPath.before-failover" -Force
SetCfg 'http://127.0.0.1:18642' $alt
$t0 = Get-Date
Start-Process $exe '--tray'
for ($i = 0; $i -lt 30; $i++) { Start-Sleep 2; $c = Conns; if ($c -match '100\.118\.161\.25') { break } }
"1) Tailscale로 바뀜: $([math]::Round(((Get-Date)-$t0).TotalSeconds))초 뒤 · 연결: $c"
$n = "C:\MydriveApp\speedtest\away-$(Get-Date -Format HHmmss).txt"; Set-Content $n 'from outside'; $t1 = Get-Date
$rel = 'speedtest/' + (Split-Path $n -Leaf)
for ($i = 0; $i -lt 60; $i++) { try { $r = (Invoke-WebRequest -UseBasicParsing -Headers @{Authorization="Bearer $env:MYDRIVE_TOKEN"} "http://192.168.0.6:8642/v1/file?root=test&path=$rel").Content; break } catch { Start-Sleep -Milliseconds 500 } }
"   밖 주소로 올리기: $([math]::Round(((Get-Date)-$t1).TotalSeconds,1))초 ($r)"
# 집에 돌아옴: 중계 시작
$relay = Start-Process python -ArgumentList "-u", "`"$PSScriptRoot\relay.py`"", "18642", "192.168.0.6", "8642" -PassThru -WindowStyle Hidden
$t2 = Get-Date
for ($i = 0; $i -lt 50; $i++) { Start-Sleep 2; $c = Conns; if ($c -match '127\.0\.0\.1:18642' -and $c -notmatch '100\.118\.161\.25') { break } }
"2) 집 주소로 돌아옴: $([math]::Round(((Get-Date)-$t2).TotalSeconds))초 뒤 · 연결: $c"
gc "$env:LOCALAPPDATA\Mydrive\alerts.log" -Tail 4 -Encoding UTF8
# 원래대로: 앱 끄고, 설정 되돌리고(밖 주소는 남김), 중계 끄고, 다시 켬
& "$PSScriptRoot\quitapp.ps1"
Stop-Process -Id $relay.Id -Force -ErrorAction SilentlyContinue
SetCfg 'http://192.168.0.6:8642' $alt
Start-Process $exe '--tray'
Start-Sleep 8
"3) 원래 설정으로 다시 켬 · 연결: $(Conns)"
