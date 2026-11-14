# 탐색기에서 파일을 오른쪽 클릭(Shift+F10)하고 Mydrive 하위 메뉴를 펼친 화면을 찍음
param([string]$Target, [string]$Out)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing, UIAutomationClient, UIAutomationTypes
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class W {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
}
"@
$shell = New-Object -ComObject Shell.Application
$before = @($shell.Windows() | % { $_.HWND })
if ($env:CTX_SEPARATE) { Start-Process explorer.exe "/separate,/select,`"$Target`"" } else { Start-Process explorer.exe "/select,`"$Target`"" }
$win = $null
for ($i = 0; $i -lt 40 -and -not $win; $i++) {
    Start-Sleep -Milliseconds 250
    $win = $shell.Windows() | ? { $before -notcontains $_.HWND } | select -First 1
}
if (-not $win) { "explorer window not found"; exit 1 }
$h = [IntPtr]$win.HWND
[W]::ShowWindow($h, 3) | Out-Null
Start-Sleep -Milliseconds 1500
[W]::SetForegroundWindow($h) | Out-Null
Start-Sleep -Milliseconds 400
[System.Windows.Forms.SendKeys]::SendWait("+{F10}")
Start-Sleep -Milliseconds 1500
$root = [System.Windows.Automation.AutomationElement]::RootElement
$cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, $(if ($env:CTX_NAME) { $env:CTX_NAME } else { "Mydrive" }))
$item = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.AndCondition($cond, (New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::ControlTypeProperty, [System.Windows.Automation.ControlType]::MenuItem)))))
if ($item) {
    $r = $item.Current.BoundingRectangle
    [W]::SetCursorPos([int]($r.X + 40), [int]($r.Y + $r.Height / 2)) | Out-Null
    Start-Sleep -Milliseconds 1500
    "found Mydrive item at $r"
} elseif ($env:CTX_HOVER) {
    $xy = $env:CTX_HOVER.Split(','); [W]::SetCursorPos([int]$xy[0], [int]$xy[1]) | Out-Null
    Start-Sleep -Milliseconds 1500
    "hovered $($env:CTX_HOVER)"
} else { "Mydrive item not found" }
$b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
[System.Windows.Forms.SendKeys]::SendWait("{ESC}{ESC}{ESC}")
Start-Sleep -Milliseconds 300
$win.Quit()
"saved $Out"
