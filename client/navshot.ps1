# 새 탐색기 창을 열어 왼쪽 목록(탐색 창)을 찍음
param([string]$Out)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class N {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  public struct RECT { public int L, T, R, B; }
}
"@
[N]::SetProcessDPIAware() | Out-Null
$shell = New-Object -ComObject Shell.Application
$before = @($shell.Windows() | % { $_.HWND })
Start-Process explorer.exe 'C:\MydriveApp'
$win = $null
for ($i = 0; $i -lt 40 -and -not $win; $i++) { Start-Sleep -Milliseconds 250; $win = $shell.Windows() | ? { $before -notcontains $_.HWND } | select -First 1 }
if (-not $win) { "no window"; exit 1 }
$h = [IntPtr]$win.HWND
[N]::ShowWindow($h, 3) | Out-Null; Start-Sleep -Milliseconds 2000; [N]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 800
$r = New-Object N+RECT; [N]::GetWindowRect($h, [ref]$r) | Out-Null
$w = [Math]::Min(900, $r.R - $r.L); $hh = $r.B - $r.T
$bmp = New-Object System.Drawing.Bitmap $w, $hh
[System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen([Math]::Max(0,$r.L), [Math]::Max(0,$r.T), 0, 0, (New-Object System.Drawing.Size $w, $hh))
$bmp.Save($Out)
$win.Quit()
"saved"
