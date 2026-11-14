param([int]$Cmd, [switch]$ConfirmOk)
# Mydrive 도구 창에 단추 명령을 보내고, 확인 창이 뜨면 [확인]을 누름
Add-Type @"
using System; using System.Runtime.InteropServices;
public class TC {
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, int m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern IntPtr GetWindow(IntPtr h, uint c);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindowEx(IntPtr p, IntPtr a, string c, string t);
}
"@
$w = [TC]::FindWindow("MydriveTool", [NullString]::Value)
if ($w -eq [IntPtr]::Zero) { "no tool window"; exit 1 }
[TC]::PostMessage($w, 0x111, [IntPtr]$Cmd, [IntPtr]::Zero) | Out-Null
if ($ConfirmOk) {
  $pid0 = 0; [TC]::GetWindowThreadProcessId($w, [ref]$pid0) | Out-Null
  for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 300
    $d = [IntPtr]::Zero
    while (($d = [TC]::FindWindowEx([IntPtr]::Zero, $d, "#32770", [NullString]::Value)) -ne [IntPtr]::Zero) {
      $p = 0; [TC]::GetWindowThreadProcessId($d, [ref]$p) | Out-Null
      if ($p -eq $pid0) { [TC]::PostMessage($d, 0x111, [IntPtr]1, [IntPtr]::Zero) | Out-Null; "confirmed"; exit 0 }
    }
  }
  "no confirm dialog"
}
