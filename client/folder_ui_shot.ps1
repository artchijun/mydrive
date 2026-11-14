# 설정 → 동기화 폴더: 위 / 맨 아래(상태 아이콘 설명) / [+] 추가 창 / 폴더별 설정 창을 찍고 모두 닫음
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class FU { public delegate bool CB(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindowEx(IntPtr p, IntPtr a, string c, string t);
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 public struct RECT { public int L, T, R, B; }
 public static IntPtr Find(string cls, uint pid) { IntPtr f = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(64); GetClassName(h, c, 64); if (c.ToString() == cls && p == pid && IsWindowVisible(h)) { f = h; return false; } return true; }, IntPtr.Zero); return f; } }
"@
[FU]::SetProcessDPIAware() | Out-Null
$pid2 = [uint32](Get-Process Mydrive | select -first 1).Id
function Shot($h, $name) {
  [FU]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 700
  $r = New-Object FU+RECT; [FU]::GetWindowRect($h, [ref]$r) | Out-Null
  $bmp = New-Object System.Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T)
  $g = [System.Drawing.Graphics]::FromImage($bmp); $dc = $g.GetHdc(); [FU]::PrintWindow($h, $dc, 2) | Out-Null; $g.ReleaseHdc($dc); $g.Dispose()
  $bmp.Save("$PSScriptRoot\build\$name.png"); "saved $name"
}
function Wait($cls) { for ($i = 0; $i -lt 20; $i++) { Start-Sleep -Milliseconds 400; $w = [FU]::Find($cls, $pid2); if ($w -ne [IntPtr]::Zero) { return $w } }; return [IntPtr]::Zero }
$s = [FU]::Find('MydriveSettings', $pid2)
if ($s -eq [IntPtr]::Zero) { $m = [FU]::Find('MydriveMain', $pid2); if ($m -eq [IntPtr]::Zero) { $m = [IntPtr]0 }
  Add-Type 'using System; using System.Runtime.InteropServices; using System.Text; public static class FM { public delegate bool CB(IntPtr h, IntPtr l); [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l); [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p); [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n); public static IntPtr Find(string cls, uint pid) { IntPtr f = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(64); GetClassName(h, c, 64); if (c.ToString() == cls && p == pid) { f = h; return false; } return true; }, IntPtr.Zero); return f; } }'
  $m = [FM]::Find('MydriveMain', $pid2)
  [FU]::PostMessage($m, 0x111, [IntPtr]309, [IntPtr]::Zero) | Out-Null; $s = Wait 'MydriveSettings'; $opened = $true }
[FU]::SendMessage($s, 0x111, [IntPtr]901, [IntPtr]::Zero) | Out-Null; Start-Sleep -Milliseconds 400
Shot $s 'f_top'
$panels = @(); $c = [FU]::FindWindowEx($s, [IntPtr]::Zero, "MydrivePanel", $null); while ($c -ne [IntPtr]::Zero) { $panels += $c; $c = [FU]::FindWindowEx($s, $c, "MydrivePanel", $null) }
[FU]::SendMessage($panels[1], 0x115, [IntPtr]7, [IntPtr]::Zero) | Out-Null; Start-Sleep -Milliseconds 400   # 맨 아래
Shot $s 'f_bottom'
[FU]::SendMessage($panels[1], 0x115, [IntPtr]6, [IntPtr]::Zero) | Out-Null
[FU]::PostMessage($s, 0x111, [IntPtr]537, [IntPtr]::Zero) | Out-Null   # IDS_ADDPAIR
$a = Wait 'MydriveAdd'; Start-Sleep 2
if ($a -ne [IntPtr]::Zero) { Shot $a 'f_add'; [FU]::PostMessage($a, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null; Start-Sleep 1 } else { "add not found" }
[FU]::PostMessage($s, 0x111, [IntPtr]515, [IntPtr]::Zero) | Out-Null   # IDS_PICK
$p = Wait 'MydrivePick'; Start-Sleep 3
if ($p -ne [IntPtr]::Zero) { Shot $p 'f_pick'; [FU]::PostMessage($p, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null; Start-Sleep 1 } else { "pick not found" }
[FU]::PostMessage($s, 0x111, [IntPtr]513, [IntPtr]::Zero) | Out-Null   # 취소 (바꾼 것 없음)
