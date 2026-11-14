# 설정 → 동기화 작업 페이지, [편집] 창의 탭 3개, [만들기] 창을 찍고 모두 닫음 (PrintWindow: 가려져도 찍힘)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class TU { public delegate bool CB(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
 public struct RECT { public int L, T, R, B; }
 public static IntPtr Find(string cls, uint pid, bool vis) { IntPtr f = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(64); GetClassName(h, c, 64); if (c.ToString() == cls && p == pid && (!vis || IsWindowVisible(h))) { f = h; return false; } return true; }, IntPtr.Zero); return f; } }
"@
[TU]::SetProcessDPIAware() | Out-Null
$pid2 = [uint32](Get-Process Mydrive | select -first 1).Id
function Shot($h, $name) {
  Start-Sleep -Milliseconds 600
  $r = New-Object TU+RECT; [TU]::GetWindowRect($h, [ref]$r) | Out-Null
  $bmp = New-Object System.Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T)
  $g = [System.Drawing.Graphics]::FromImage($bmp); $dc = $g.GetHdc(); [TU]::PrintWindow($h, $dc, 2) | Out-Null; $g.ReleaseHdc($dc); $g.Dispose()
  $bmp.Save("$PSScriptRoot\build\$name.png"); "saved $name"
}
function Wait($cls) { for ($i = 0; $i -lt 25; $i++) { Start-Sleep -Milliseconds 400; $w = [TU]::Find($cls, $pid2, $true); if ($w -ne [IntPtr]::Zero) { return $w } }; return [IntPtr]::Zero }
$s = [TU]::Find('MydriveSettings', $pid2, $true)
$opened = $false
if ($s -eq [IntPtr]::Zero) { $m = [TU]::Find('MydriveMain', $pid2, $false); [TU]::PostMessage($m, 0x111, [IntPtr]309, [IntPtr]::Zero) | Out-Null; $s = Wait 'MydriveSettings'; $opened = $true }
[TU]::SendMessage($s, 0x111, [IntPtr]901, [IntPtr]::Zero) | Out-Null
$lb = [IntPtr]::Zero
Shot $s 't_page'
# 첫 작업을 골라 편집
Add-Type 'using System; using System.Runtime.InteropServices; public static class TF { [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindowEx(IntPtr p, IntPtr a, string c, string t); }'
$panel = [TF]::FindWindowEx($s, [IntPtr]::Zero, "MydrivePanel", $null); $panel = [TF]::FindWindowEx($s, $panel, "MydrivePanel", $null)
$lb = [TF]::FindWindowEx($panel, [IntPtr]::Zero, "ListBox", $null)
[TU]::SendMessage($lb, 0x0186, [IntPtr]0, [IntPtr]::Zero) | Out-Null   # LB_SETCURSEL 0
[TU]::SendMessage($s, 0x111, [IntPtr](504 -bor (1 -shl 16)), $lb) | Out-Null   # IDS_LIST, LBN_SELCHANGE
[TU]::PostMessage($s, 0x111, [IntPtr]515, [IntPtr]::Zero) | Out-Null   # 편집
$p = Wait 'MydrivePick'
if ($p -ne [IntPtr]::Zero) {
  Start-Sleep 3; Shot $p 't_edit_folders'
  [TU]::SendMessage($p, 0x111, [IntPtr]721, [IntPtr]::Zero) | Out-Null; Shot $p 't_edit_mode'
  [TU]::SendMessage($p, 0x111, [IntPtr]722, [IntPtr]::Zero) | Out-Null; Shot $p 't_edit_general'
  [TU]::PostMessage($p, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null; Start-Sleep 1
} else { "edit not found" }
[TU]::PostMessage($s, 0x111, [IntPtr]537, [IntPtr]::Zero) | Out-Null   # 만들기
$p = Wait 'MydrivePick'
if ($p -ne [IntPtr]::Zero) { Start-Sleep 2; Shot $p 't_new'; [TU]::PostMessage($p, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null; Start-Sleep 1 } else { "new not found" }
if ($opened) { [TU]::PostMessage($s, 0x111, [IntPtr]513, [IntPtr]::Zero) | Out-Null }
