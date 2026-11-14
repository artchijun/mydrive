# DSM 계정 연결 창: 없는 계정으로 눌러서 실패 안내가 나오는지 찍음 (실제 비밀번호는 쓰지 않음)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class PT { public delegate bool CB(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, string l);
 [DllImport("user32.dll")] public static extern IntPtr GetDlgItem(IntPtr h, int id);
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 public struct RECT { public int L, T, R, B; }
 public static IntPtr Find(string cls, uint pid) { IntPtr f = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(64); GetClassName(h, c, 64); if (c.ToString() == cls && p == pid && (cls == "MydriveMain" || IsWindowVisible(h))) { f = h; return false; } return true; }, IntPtr.Zero); return f; } }
"@
[PT]::SetProcessDPIAware() | Out-Null
$pid2 = [uint32](Get-Process Mydrive | select -first 1).Id
$m = [IntPtr]::Zero
Add-Type -Name F7 -Namespace G7 -MemberDefinition '[DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern System.IntPtr FindWindowEx(System.IntPtr p, System.IntPtr a, string c, string t);'
# 주 창 (숨은 창도 찾음)
$m = [PT]::Find('MydriveSettings', $pid2)
if ($m -eq [IntPtr]::Zero) {
  $main = $null; Get-Process -Id $pid2 | Out-Null
  $mw = [PT]::Find('MydriveMain', $pid2)
  [PT]::PostMessage($mw, 0x111, [IntPtr]309, [IntPtr]::Zero) | Out-Null; Start-Sleep 2
  $m = [PT]::Find('MydriveSettings', $pid2)
}
[PT]::PostMessage($m, 0x111, [IntPtr]529, [IntPtr]::Zero) | Out-Null; Start-Sleep 2
$q = [PT]::Find('MydrivePair', $pid2)
[PT]::SendMessage([PT]::GetDlgItem($q, 1200), 0x0C, [IntPtr]::Zero, 'mydrive-ui-test') | Out-Null
[PT]::SendMessage([PT]::GetDlgItem($q, 1201), 0x0C, [IntPtr]::Zero, 'x') | Out-Null
[PT]::PostMessage($q, 0x111, [IntPtr]1204, [IntPtr]::Zero) | Out-Null
Start-Sleep 10
[PT]::SetForegroundWindow($q) | Out-Null; Start-Sleep -Milliseconds 500
$r = New-Object PT+RECT; [PT]::GetWindowRect($q, [ref]$r) | Out-Null
$w = $r.R - $r.L; $hh = $r.B - $r.T
$bmp = New-Object System.Drawing.Bitmap $w, $hh
[System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen($r.L, $r.T, 0, 0, (New-Object System.Drawing.Size $w, $hh))
$bmp.Save("$PSScriptRoot\build\pair_fail.png"); "saved"
[PT]::PostMessage($q, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null; Start-Sleep 1
[PT]::PostMessage($m, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
