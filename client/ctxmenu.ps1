param([string]$Path, [switch]$Extended)
# 파일의 실제 오른쪽 클릭 메뉴(클래식) 항목을 하위 메뉴까지 글자로 뽑음
Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices; using System.Text; using System.Collections.Generic;
public static class Ctx {
  [ComImport, Guid("000214E4-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IContextMenu { [PreserveSig] int QueryContextMenu(IntPtr hmenu, uint i, uint first, uint last, uint flags); void InvokeCommand(IntPtr p); void GetCommandString(UIntPtr id, uint t, IntPtr r, StringBuilder s, uint n); }
  [DllImport("shell32.dll", CharSet=CharSet.Unicode)] static extern int SHParseDisplayName(string p, IntPtr bc, out IntPtr pidl, uint i, out uint o);
  [DllImport("shell32.dll")] static extern int SHBindToParent(IntPtr pidl, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object ppv, out IntPtr last);
  [ComImport, Guid("000214E6-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IShellFolder { void P1(); void P2(); void P3(); void P4(); void P5(); void P6(); void P7();
    [PreserveSig] int GetUIObjectOf(IntPtr hwnd, uint c, [MarshalAs(UnmanagedType.LPArray)] IntPtr[] pidls, ref Guid riid, IntPtr r, [MarshalAs(UnmanagedType.Interface)] out object ppv); }
  [DllImport("user32.dll")] static extern IntPtr CreatePopupMenu();
  [DllImport("user32.dll")] static extern int GetMenuItemCount(IntPtr m);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetMenuString(IntPtr m, uint id, StringBuilder s, int n, uint f);
  [DllImport("user32.dll")] static extern IntPtr GetSubMenu(IntPtr m, int pos);
  [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [ComImport, Guid("000214F4-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IContextMenu2 { [PreserveSig] int QueryContextMenu(IntPtr hmenu, uint i, uint first, uint last, uint flags); void InvokeCommand(IntPtr p); void GetCommandString(UIntPtr id, uint t, IntPtr r, StringBuilder s, uint n); [PreserveSig] int HandleMenuMsg(uint msg, IntPtr w, IntPtr l); }
  static void Walk(IntPtr m, string ind, List<string> o, object cm) {
    int n = GetMenuItemCount(m);
    for (int i = 0; i < n; i++) {
      var sb = new StringBuilder(256); GetMenuString(m, (uint)i, sb, 256, 0x400);
      IntPtr sub = GetSubMenu(m, i);
      if (sub != IntPtr.Zero) { var c2 = cm as IContextMenu2; if (c2 != null) c2.HandleMenuMsg(0x0117, sub, (IntPtr)i); }
      if (sb.Length > 0) o.Add(ind + sb.ToString().Replace("&", ""));
      if (sub != IntPtr.Zero) Walk(sub, ind + "    ", o, cm);
    }
  }
  public static string[] Items(string path, bool ext) {
    IntPtr pidl; uint o; SHParseDisplayName(path, IntPtr.Zero, out pidl, 0, out o);
    Guid g = typeof(IShellFolder).GUID; object f; IntPtr last; SHBindToParent(pidl, ref g, out f, out last);
    Guid gc = typeof(IContextMenu).GUID; object cmo;
    ((IShellFolder)f).GetUIObjectOf(IntPtr.Zero, 1, new[] { last }, ref gc, IntPtr.Zero, out cmo);
    IntPtr menu = CreatePopupMenu();
    ((IContextMenu)cmo).QueryContextMenu(menu, 0, 1, 0x7FFF, ext ? 0x100u : 0u);
    var list = new List<string>(); Walk(menu, "", list, cmo); return list.ToArray();
  }
}
"@
[Ctx]::Items($Path, $Extended.IsPresent)
