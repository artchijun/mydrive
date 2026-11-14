# 화면 전체를 찍음 (실제 픽셀)
param([string]$Out = "$PSScriptRoot\build\screen.png")
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class SC { [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v); }
"@
[SC]::SetProcessDpiAwarenessContext([IntPtr]-4) | Out-Null
$b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bmp = New-Object Drawing.Bitmap $b.Width, $b.Height
[Drawing.Graphics]::FromImage($bmp).CopyFromScreen($b.X, $b.Y, 0, 0, $bmp.Size)
$bmp.Save($Out); "saved $($b.Width)x$($b.Height)"
