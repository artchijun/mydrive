# 열려 있는 탐색기 창 중 제목이 맞는 것을 앞으로 가져오지 않고 찍음 (PrintWindow)
param([string]$Title, [string]$Out, [long]$Hwnd = 0)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class CB2 { public delegate bool CB(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
 [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
 public struct RECT { public int L, T, R, B; }
 public static IntPtr Find(string title) { IntPtr f = IntPtr.Zero; EnumWindows((h, l) => { var c = new StringBuilder(64); GetClassName(h, c, 64); var t = new StringBuilder(256); GetWindowText(h, t, 256); if (c.ToString() == "CabinetWClass" && t.ToString().StartsWith(title)) { f = h; return false; } return true; }, IntPtr.Zero); return f; } }
"@
[CB2]::SetProcessDpiAwarenessContext([IntPtr]-4) | Out-Null
$h = if ($Hwnd) { [IntPtr]$Hwnd } else { [CB2]::Find($Title) }
if ($h -eq [IntPtr]::Zero) { "no window '$Title'"; exit 1 }
$r = New-Object CB2+RECT; [CB2]::GetWindowRect($h, [ref]$r) | Out-Null
$bmp = New-Object Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T)
$g = [Drawing.Graphics]::FromImage($bmp); $dc = $g.GetHdc(); [CB2]::PrintWindow($h, $dc, 2) | Out-Null; $g.ReleaseHdc($dc); $g.Dispose()
$bmp.Save($Out); "saved $Out"
