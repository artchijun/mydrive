param([string]$Parent = "MydriveSettings", [string]$Child = "SysTabControl32", [int]$Msg, [int]$W = 0, [int]$L = 0)
Add-Type @"
using System; using System.Runtime.InteropServices;
public class ChildW {
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindowEx(IntPtr p, IntPtr a, string c, string t);
  [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, int m, IntPtr w, IntPtr l);
}
"@
$p = [ChildW]::FindWindow($Parent, [NullString]::Value)
if ($p -eq [IntPtr]::Zero) { "no parent"; exit 1 }
$c = [ChildW]::FindWindowEx($p, [IntPtr]::Zero, $Child, [NullString]::Value)
if ($c -eq [IntPtr]::Zero) { "no child"; exit 1 }
[ChildW]::SendMessage($c, $Msg, [IntPtr]$W, [IntPtr]$L) | Out-Null
"sent"
