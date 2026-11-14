param([string]$Class = "MydrivePopup", [int]$X, [int]$Y, [switch]$Right, [switch]$Double, [int]$Wheel = 0)
# 창 안의 (X, Y) 화소 자리를 누름 (마우스 이동 → 누르기 → 떼기)
Add-Type @"
using System; using System.Runtime.InteropServices;
public class ClickW {
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
  [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, int m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
}
"@
[ClickW]::SetProcessDpiAwarenessContext([IntPtr]-4) | Out-Null
$h = [ClickW]::FindWindow($Class, [NullString]::Value)
if ($h -eq [IntPtr]::Zero) { "no window"; exit 1 }
$lp = [IntPtr](($Y -shl 16) -bor ($X -band 0xFFFF))
[ClickW]::SendMessage($h, 0x200, [IntPtr]0, $lp) | Out-Null
if ($Wheel -ne 0) { "wheel"; exit 0 }
if ($Right) { [ClickW]::SendMessage($h, 0x205, [IntPtr]0, $lp) | Out-Null; "right"; exit 0 }
if ($Double) { [ClickW]::SendMessage($h, 0x203, [IntPtr]1, $lp) | Out-Null; "double"; exit 0 }
[ClickW]::SendMessage($h, 0x201, [IntPtr]1, $lp) | Out-Null
[ClickW]::SendMessage($h, 0x202, [IntPtr]0, $lp) | Out-Null
"clicked"
