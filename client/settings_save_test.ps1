# 설정 창을 열고 아무것도 안 바꾼 채 [저장] → config.json 내용이 그대로인지, 앱이 다시 연결되는지
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class ST { public delegate bool CB(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 public static IntPtr Find(string cls, uint pid, bool vis) { IntPtr f = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(64); GetClassName(h, c, 64); if (c.ToString() == cls && p == pid && (!vis || IsWindowVisible(h))) { f = h; return false; } return true; }, IntPtr.Zero); return f; } }
"@
$cfg = "$env:LOCALAPPDATA\Mydrive\config.json"
function Norm { (Get-Content $cfg -Raw -Encoding UTF8 | ConvertFrom-Json | ConvertTo-Json -Depth 8 -Compress) }
$before = Norm
$pid2 = [uint32](Get-Process Mydrive | select -first 1).Id
$m = [ST]::Find('MydriveMain', $pid2, $false)
[ST]::PostMessage($m, 0x111, [IntPtr]309, [IntPtr]::Zero) | Out-Null
Start-Sleep 2
$s = [ST]::Find('MydriveSettings', $pid2, $true)
if ($s -eq [IntPtr]::Zero) { "FAIL settings window not found"; exit 1 }
[ST]::PostMessage($s, 0x111, [IntPtr]512, [IntPtr]::Zero) | Out-Null   # IDS_SAVE
Start-Sleep 3
$gone = [ST]::Find('MydriveSettings', $pid2, $true) -eq [IntPtr]::Zero
$after = Norm
"settings closed after save: $gone"
"config unchanged: $($before -eq $after)"
if ($before -ne $after) { "BEFORE: $before"; "AFTER:  $after" }
