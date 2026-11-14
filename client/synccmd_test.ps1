# 오른쪽 클릭 동기화 명령 시험: 메뉴와 같은 명령줄로 실행하고 결과를 확인
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class X {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern IntPtr PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  public delegate bool CB(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, System.Text.StringBuilder s, int n);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  public static IntPtr Dlg(uint pid) { IntPtr found = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new System.Text.StringBuilder(64); GetClassName(h, c, 64); if (p == pid && c.ToString() == "#32770" && IsWindowVisible(h)) { found = h; return false; } return true; }, IntPtr.Zero); return found; }
  public struct RECT { public int L, T, R, B; }
}
"@
[X]::SetProcessDPIAware() | Out-Null
$exe = "$env:LOCALAPPDATA\Programs\Mydrive\Mydrive.exe"
$cfg = "$env:LOCALAPPDATA\Mydrive\config.json"
function Run($a, $p) { $pr = Start-Process $exe -ArgumentList $a, "`"$p`"" -PassThru; $pr.WaitForExit(20000) | Out-Null; "  $a -> exit $($pr.ExitCode)" }
function Shot($name) {
  $h = [IntPtr]::Zero
  for ($i = 0; $i -lt 40 -and $h -eq [IntPtr]::Zero; $i++) { Start-Sleep -Milliseconds 250; $h = [X]::Dlg([uint32](Get-Process Mydrive).Id) }
  if ($h -eq [IntPtr]::Zero) { "  status window not found"; return }
  [X]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 600
  $r = New-Object X+RECT; [X]::GetWindowRect($h, [ref]$r) | Out-Null
  $w = $r.R - $r.L; $hh = $r.B - $r.T
  $bmp = New-Object System.Drawing.Bitmap $w, $hh
  [System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen($r.L, $r.T, 0, 0, (New-Object System.Drawing.Size $w, $hh))
  $bmp.Save("$PSScriptRoot\build\$name.png")
  [X]::PostMessage($h, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
  "  saved $name.png ($w x $hh)"
  Start-Sleep -Milliseconds 500
}
$folder = 'C:\MydriveApp\speedtest'
"1) 상태 보기 (폴더)"; Run '--status' $folder; Shot 'st_folder'
$f = gci "C:\MydriveApp\대량 시험 2\프로젝트 01" -File | select -first 1
"2) 상태 보기 (파일)"; Run '--status' $f.FullName; Shot 'st_file'
"3) 일시 중지 30분 → 상태"; Run '--pause30' $folder; Start-Sleep 1; Run '--status' $folder; Shot 'st_paused'
"4) 다시 시작 → 상태"; Run '--resume' $folder; Start-Sleep 1; Run '--status' $folder; Shot 'st_resumed'
"5) 제외"; Run '--toggle-skip' $folder; Start-Sleep 3; "  skipDirs: " + ((gc $cfg -Raw -Encoding UTF8 | ConvertFrom-Json).connections[0].skipDirs -join ', '); Run '--status' $folder; Shot 'st_skipped'
"6) 다시 포함"; Run '--toggle-skip' $folder; Start-Sleep 3; "  skipDirs: " + ((gc $cfg -Raw -Encoding UTF8 | ConvertFrom-Json).connections[0].skipDirs -join ', ')
"7) 지금 동기화: 새 파일을 만들고 바로 명령"
$n = "C:\MydriveApp\speedtest\now-$(Get-Date -Format HHmmss).txt"; Set-Content $n 'now'; $t0 = Get-Date; Run '--sync-now' $folder
$rel = 'speedtest/' + (Split-Path $n -Leaf)
for ($i = 0; $i -lt 60; $i++) { try { $c = (Invoke-WebRequest -UseBasicParsing -Headers @{Authorization="Bearer $env:MYDRIVE_TOKEN"} "http://192.168.0.6:8642/v1/file?root=test&path=$rel").Content; break } catch { Start-Sleep -Milliseconds 250 } }
"  NAS에 생김: $([math]::Round(((Get-Date) - $t0).TotalSeconds, 1))초"
