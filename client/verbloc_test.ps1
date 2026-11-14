# HKCU 여러 위치에 시험용 메뉴 항목을 넣고, 실제 탐색기 메뉴에 어느 것이 보이는지 확인
param([switch]$Cleanup)
$f = gci "C:\MydriveApp\대량 시험 2\프로젝트 01" -Filter *.txt -File | select -first 1
$uc = (gp 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\.txt\UserChoice' -ErrorAction SilentlyContinue).ProgId
$prog = (gp -LiteralPath 'Registry::HKEY_CLASSES_ROOT\.txt').'(default)'
$locs = [ordered]@{
  'ZzA' = 'Software\Classes\SystemFileAssociations\.txt\shell\ZzA'
  'ZzB' = "Software\Classes\$prog\shell\ZzB"
  'ZzC' = 'Software\Classes\AllFilesystemObjects\shell\ZzC'
  'ZzD' = 'Software\Classes\.txt\shell\ZzD'
}
if ($uc) { $locs['ZzE'] = "Software\Classes\$uc\shell\ZzE" }
foreach ($n in $locs.Keys) {
  $k = $locs[$n]
  if ($Cleanup) { [Microsoft.Win32.Registry]::CurrentUser.DeleteSubKeyTree($k, $false); continue }
  $c = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey("$k\command"); $c.SetValue('', 'notepad.exe "%1"'); $c.Close()
  $v = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($k, $true); $v.SetValue('MUIVerb', $n); $v.Close()
}
if ($Cleanup) { "cleaned"; exit }
"prog=$prog userchoice=$uc file=$($f.Name)"
$locs.GetEnumerator() | % { "$($_.Key) -> HKCU\$($_.Value)" }
$env:CTX_NAME = 'ZzA'
powershell -STA -ExecutionPolicy Bypass -File "$PSScriptRoot\ctxshot.ps1" -Target $f.FullName -Out "$PSScriptRoot\build\ctx_locs.png"
