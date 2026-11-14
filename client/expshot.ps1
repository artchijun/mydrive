# 탐색기로 폴더를 열고 그 창을 찍음:  expshot.ps1 -Dir "C:\폴더" -Out build\x.png
param([string]$Dir, [string]$Out)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class EX { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r); public struct RECT { public int L, T, R, B; } }
"@
[EX]::SetProcessDPIAware() | Out-Null
Start-Process explorer.exe "`"$Dir`""
Start-Sleep 4
$h = [EX]::GetForegroundWindow(); $r = New-Object EX+RECT; [EX]::GetWindowRect($h, [ref]$r) | Out-Null
$bmp = New-Object Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T)
[Drawing.Graphics]::FromImage($bmp).CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size)
$bmp.Save($Out); "saved $Out"
