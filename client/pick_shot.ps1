# 설정 → 동기화 폴더 → [폴더별 설정] 창을 열어 찍고 닫음 (build\pick.png)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class PK { public delegate bool CB(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] static extern bool EnumWindows(CB cb, IntPtr l);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 public struct RECT { public int L, T, R, B; }
 public static IntPtr Find(string cls, uint pid, bool vis) { IntPtr f = IntPtr.Zero; EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); var c = new StringBuilder(64); GetClassName(h, c, 64); if (c.ToString() == cls && p == pid && (!vis || IsWindowVisible(h))) { f = h; return false; } return true; }, IntPtr.Zero); return f; } }
"@
[PK]::SetProcessDPIAware() | Out-Null
$pid2 = [uint32](Get-Process Mydrive | select -first 1).Id
function Shot($h, $name) {
  [PK]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 700
  $r = New-Object PK+RECT; [PK]::GetWindowRect($h, [ref]$r) | Out-Null
  $bmp = New-Object System.Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T)
  [System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size)
  $bmp.Save("$PSScriptRoot\build\$name.png"); "saved $name"
}
$m = [PK]::Find('MydriveMain', $pid2, $false)
[PK]::PostMessage($m, 0x111, [IntPtr]309, [IntPtr]::Zero) | Out-Null; Start-Sleep 2
$s = [PK]::Find('MydriveSettings', $pid2, $true)
[PK]::SendMessage($s, 0x111, [IntPtr]901, [IntPtr]::Zero) | Out-Null; Start-Sleep -Milliseconds 400
[PK]::PostMessage($s, 0x111, [IntPtr]515, [IntPtr]::Zero) | Out-Null   # IDS_PICK
for ($i = 0; $i -lt 20; $i++) { Start-Sleep -Milliseconds 500; $p = [PK]::Find('MydrivePick', $pid2, $true); if ($p -ne [IntPtr]::Zero) { break } }
Start-Sleep 3
if ($p -ne [IntPtr]::Zero) { Shot $p 'pick'; [PK]::PostMessage($p, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null; Start-Sleep 1 } else { "pick not found" }
[PK]::PostMessage($s, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
