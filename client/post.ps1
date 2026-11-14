param([string]$Class = "MydriveMain", [int]$Msg, [int]$W = 0, [int]$L = 0)
Add-Type @"
using System; using System.Runtime.InteropServices;
public class PostW {
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, int m, IntPtr w, IntPtr l);
}
"@
$h = [PostW]::FindWindow($Class, [NullString]::Value)
if ($h -eq [IntPtr]::Zero) { "no window $Class"; exit 1 }
[PostW]::PostMessage($h, $Msg, [IntPtr]$W, [IntPtr]$L) | Out-Null
"posted"
