# AppliesTo 조건을 바꿔 가며 실제 탐색기 메뉴에 Mydrive가 나오는지 확인
$k = 'HKCU:\Software\Classes\AllFilesystemObjects\shell\Mydrive'
$orig = (Get-ItemProperty $k).AppliesTo
$f = gci "C:\MydriveApp\대량 시험 2\프로젝트 01" -File | select -first 1
Add-Type -Name N -Namespace S -MemberDefinition '[DllImport("shell32.dll")] public static extern void SHChangeNotify(int e, uint f, System.IntPtr a, System.IntPtr b);'
$variants = @(
  '',
  'System.ItemPathDisplay:~<"C:\MydriveApp"',
  'System.ItemFolderPathDisplay:~<"C:\MydriveApp"',
  'System.ParsingPath:~<"C:\MydriveApp"'
)
foreach ($v in $variants) {
  if ($v -eq '') { Remove-ItemProperty $k -Name AppliesTo -ErrorAction SilentlyContinue } else { Set-ItemProperty $k -Name AppliesTo -Value $v }
  [S.N]::SHChangeNotify(0x08000000, 0, [IntPtr]::Zero, [IntPtr]::Zero)
  Start-Sleep 1
  $r = powershell -STA -ExecutionPolicy Bypass -File "$PSScriptRoot\ctxshot.ps1" -Target $f.FullName -Out "$env:TEMP\ctx_t.png" | select -first 1
  "[$v] => $r"
}
Set-ItemProperty $k -Name AppliesTo -Value $orig
"restored"
