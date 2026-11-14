# 열려 있는 설정 창에서 [폴더별 설정…]을 누름 (창은 닫지 않음)
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class PO { public delegate bool CB(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 public static IntPtr Find(string cls, uint pid) { IntPtr f = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(64); GetClassName(h, c, 64); if (c.ToString() == cls && p == pid && IsWindowVisible(h)) { f = h; return false; } return true; }, IntPtr.Zero); return f; } }
"@
$pid2 = [uint32](Get-Process Mydrive | select -first 1).Id
$s = [PO]::Find('MydriveSettings', $pid2)
if ($s -eq [IntPtr]::Zero) { "no settings"; exit 1 }
$p = [PO]::Find('MydrivePick', $pid2)
if ($p -eq [IntPtr]::Zero) { [PO]::PostMessage($s, 0x111, [IntPtr]515, [IntPtr]::Zero) | Out-Null; "posted" } else { "already open" }
