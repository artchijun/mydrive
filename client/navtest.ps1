# Experiment: own nav-pane entry (like Synology/OneDrive) for C:\MydriveApp
$g = '{12CBFE2C-788E-4922-890E-77C1D3D1E071}'
$k = "HKCU:\Software\Classes\CLSID\$g"
New-Item $k -Force | Out-Null
Set-ItemProperty $k '(default)' 'Mydrive test'
New-ItemProperty $k -Name 'System.IsPinnedToNameSpaceTree' -Value 1 -PropertyType DWord -Force | Out-Null
New-ItemProperty $k -Name 'SortOrderIndex' -Value 0x42 -PropertyType DWord -Force | Out-Null
New-Item "$k\DefaultIcon" -Force | Out-Null; Set-ItemProperty "$k\DefaultIcon" '(default)' "$env:LOCALAPPDATA\Programs\Mydrive\Mydrive.exe,0"
New-Item "$k\InProcServer32" -Force | Out-Null; New-ItemProperty "$k\InProcServer32" -Name '(default)' -Value '%SystemRoot%\system32\shell32.dll' -PropertyType ExpandString -Force | Out-Null
New-Item "$k\Instance\InitPropertyBag" -Force | Out-Null
Set-ItemProperty "$k\Instance" 'CLSID' '{0E5AAE11-A475-4c5b-AB00-C66DE400274E}'
New-ItemProperty "$k\Instance\InitPropertyBag" -Name 'Attributes' -Value 0x11 -PropertyType DWord -Force | Out-Null
New-ItemProperty "$k\Instance\InitPropertyBag" -Name 'TargetFolderPath' -Value 'C:\MydriveApp' -PropertyType ExpandString -Force | Out-Null
New-Item "$k\ShellFolder" -Force | Out-Null
New-ItemProperty "$k\ShellFolder" -Name 'FolderValueFlags' -Value 0x28 -PropertyType DWord -Force | Out-Null
New-ItemProperty "$k\ShellFolder" -Name 'Attributes' -Value 0xF080004D -PropertyType DWord -Force | Out-Null
$ns = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Desktop\NameSpace\$g"
New-Item $ns -Force | Out-Null; Set-ItemProperty $ns '(default)' 'Mydrive test'
New-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\HideDesktopIcons\NewStartPanel" -Name $g -Value 1 -PropertyType DWord -Force | Out-Null
"added"
