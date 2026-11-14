# 설정 창을 열어 왼쪽 메뉴 4개를 차례로 찍음 (build\settings_1..4.png), 마지막에 닫음
param([switch]$Pair)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class SS { public delegate bool CB(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindowEx(IntPtr p, IntPtr a, string c, string t);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 public struct RECT { public int L, T, R, B; }
 public static IntPtr Find(string cls, uint pid, bool vis) { IntPtr f = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(64); GetClassName(h, c, 64); if (c.ToString() == cls && p == pid && (!vis || IsWindowVisible(h))) { f = h; return false; } return true; }, IntPtr.Zero); return f; } }
"@
[SS]::SetProcessDPIAware() | Out-Null
$pid2 = [uint32](Get-Process Mydrive | select -first 1).Id
function Shot($h, $name) {
  [SS]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 700
  $r = New-Object SS+RECT; [SS]::GetWindowRect($h, [ref]$r) | Out-Null
  $w = $r.R - $r.L; $hh = $r.B - $r.T
  $bmp = New-Object System.Drawing.Bitmap $w, $hh
  [System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen($r.L, $r.T, 0, 0, (New-Object System.Drawing.Size $w, $hh))
  $bmp.Save("$PSScriptRoot\build\$name.png"); "saved $name ($w x $hh)"
}
$m = [SS]::Find('MydriveMain', $pid2, $false)
[SS]::PostMessage($m, 0x111, [IntPtr]309, [IntPtr]::Zero) | Out-Null
Start-Sleep 2
$s = [SS]::Find('MydriveSettings', $pid2, $true)
if ($s -eq [IntPtr]::Zero) { "settings not found"; exit 1 }
for ($i = 0; $i -lt 4; $i++) {
  [SS]::SendMessage($s, 0x111, [IntPtr](900 + $i), [IntPtr]::Zero) | Out-Null
  Start-Sleep -Milliseconds 500
  Shot $s "settings_$($i + 1)"
}
[SS]::SendMessage($s, 0x111, [IntPtr]901, [IntPtr]::Zero) | Out-Null
Start-Sleep -Milliseconds 300
$panels = @(); $c = [SS]::FindWindowEx($s, [IntPtr]::Zero, "MydrivePanel", $null); while ($c -ne [IntPtr]::Zero) { $panels += $c; $c = [SS]::FindWindowEx($s, $c, "MydrivePanel", $null) }
if ($panels.Count -ge 2) { [SS]::SendMessage($panels[1], 0x115, [IntPtr]7, [IntPtr]::Zero) | Out-Null; Start-Sleep -Milliseconds 400; Shot $s "settings_2b" }
[SS]::SendMessage($s, 0x111, [IntPtr]900, [IntPtr]::Zero) | Out-Null
if ($Pair) {
  [SS]::PostMessage($s, 0x111, [IntPtr]529, [IntPtr]::Zero) | Out-Null   # IDS_PAIR
  Start-Sleep 2
  $q = [SS]::Find('MydrivePair', $pid2, $true)
  if ($q -ne [IntPtr]::Zero) { Shot $q 'settings_pair'; [SS]::PostMessage($q, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null; Start-Sleep 1 }
}
[SS]::PostMessage($s, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
