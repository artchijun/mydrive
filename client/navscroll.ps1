param([int]$Pages = 1, [int]$Lines = 0)
# Scroll the Explorer navigation pane (tree) of the front Explorer window
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public class NavS {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
  [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr p, EnumProc f, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, int m, IntPtr w, IntPtr l);
  public static IntPtr tree = IntPtr.Zero;
  public static void Find(IntPtr top) {
    EnumChildWindows(top, (h, l) => { var sb = new StringBuilder(64); GetClassName(h, sb, 64); if (sb.ToString() == "SysTreeView32") { tree = h; return false; } return true; }, IntPtr.Zero);
  }
}
"@
$w = [NavS]::FindWindow("CabinetWClass", [NullString]::Value)
[NavS]::Find($w)
if ([NavS]::tree -eq [IntPtr]::Zero) { "no tree"; exit 1 }
for ($i = 0; $i -lt $Lines; $i++) { [NavS]::SendMessage([NavS]::tree, 0x115, [IntPtr]1, [IntPtr]0) | Out-Null }   # SB_LINEDOWN
if ($Lines) { $Pages = 0 }
for ($i = 0; $i -lt $Pages; $i++) { [NavS]::SendMessage([NavS]::tree, 0x115, [IntPtr]3, [IntPtr]0) | Out-Null }   # WM_VSCROLL SB_PAGEDOWN
"scrolled"
