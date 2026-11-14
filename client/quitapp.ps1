# Mydrive 앱을 정상 종료 (주 창에 끝내기 메시지) — FindWindow 로는 못 찾아서 EnumWindows 로 찾음
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class QA { public delegate bool CB(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 public static IntPtr Find(uint pid) { IntPtr f = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(64); GetClassName(h, c, 64); if (p == pid && c.ToString() == "MydriveMain") { f = h; return false; } return true; }, IntPtr.Zero); return f; } }
"@
$p = Get-Process Mydrive -ErrorAction SilentlyContinue | select -first 1
if (-not $p) { "not running"; return }
$h = [QA]::Find([uint32]$p.Id)
[QA]::PostMessage($h, 0x8004, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
for ($i = 0; $i -lt 30 -and (Get-Process Mydrive -ErrorAction SilentlyContinue); $i++) { Start-Sleep 1 }
"stopped: $(-not (Get-Process Mydrive -ErrorAction SilentlyContinue))"
