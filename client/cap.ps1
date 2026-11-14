param([string]$Class = "MydriveMain", [string]$Out = "$PSScriptRoot\build\cap.png")
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public class CapW {
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
  public struct RECT { public int L, T, R, B; }
}
"@
[CapW]::SetProcessDpiAwarenessContext([IntPtr]-4) | Out-Null
$h = [CapW]::FindWindow($Class, [NullString]::Value)
if ($h -eq [IntPtr]::Zero) { "no window $Class"; exit 1 }
$r = New-Object CapW+RECT; [CapW]::GetWindowRect($h, [ref]$r) | Out-Null
$w = $r.R - $r.L; $hh = $r.B - $r.T
$bmp = New-Object Drawing.Bitmap $w, $hh
$g = [Drawing.Graphics]::FromImage($bmp); $dc = $g.GetHdc()
[CapW]::PrintWindow($h, $dc, 2) | Out-Null
$g.ReleaseHdc($dc); $g.Dispose()
$bmp.Save($Out, [Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
"saved $Out $w x $hh"
