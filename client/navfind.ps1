param([string]$Like = "Mydrive*")
# List top-level items of the Explorer navigation pane (SysTreeView32) via UI Automation, find one by name
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$A = [System.Windows.Automation.AutomationElement]
$root = $A::RootElement
$win = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, (New-Object System.Windows.Automation.PropertyCondition($A::ClassNameProperty, "CabinetWClass")))
if (-not $win) { "no explorer window"; exit 1 }
$tree = $win.FindFirst([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.PropertyCondition($A::ClassNameProperty, "SysTreeView32")))
if (-not $tree) { "no tree"; exit 1 }
$items = $tree.FindAll([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.PropertyCondition($A::ControlTypeProperty, [System.Windows.Automation.ControlType]::TreeItem)))
"items: " + $items.Count
"names: " + (($items | select -first 60 | % { $_.Current.Name }) -join " | ")
$hit = $items | ? { $_.Current.Name -like $Like } | select -first 1
if ($hit) {
  try { $hit.GetCurrentPattern([System.Windows.Automation.ScrollItemPattern]::Pattern).ScrollIntoView() } catch {}
  "found: " + $hit.Current.Name
} else { "not found" }
