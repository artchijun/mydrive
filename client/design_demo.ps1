# 디자인 확인용 시험 동기화: C:\MydriveApp\디자인 시험 에 파일을 만들고 창을 찍음
$here = $PSScriptRoot
$dst = "C:\MydriveApp\디자인 시험"
New-Item -ItemType Directory -Force $dst | Out-Null
& "$here\post.ps1" -Msg 0x8003 | Out-Null          # 창 띄우기
Start-Sleep 1
$rnd = New-Object Random
function MakeFile($p, $n) { $b = New-Object byte[] $n; $rnd.NextBytes($b); [IO.File]::WriteAllBytes($p, $b) }
# 큰 파일 먼저 (진행 막대 보이게)
fsutil file createnew "$dst\현장 영상.mp4" 900000000 | Out-Null
fsutil file createnew "$dst\도면 묶음.zip" 250000000 | Out-Null
Start-Sleep -Milliseconds 300
$names = "회의록_1113.docx","예산안.xlsx","발표자료.pptx","배치도.dwg","입면도.dwg","현장사진_01.jpg","현장사진_02.jpg","현장사진_03.jpg","계약서.pdf","설계개요.hwp","메모.txt","일정표.xlsx"
foreach ($n in $names) { MakeFile "$dst\$n" ($rnd.Next(20000, 3000000)); Start-Sleep -Milliseconds 150 }
$shots = 2, 5, 9, 15, 25, 40
$t0 = Get-Date; $i = 0
foreach ($s in $shots) {
  while (((Get-Date) - $t0).TotalSeconds -lt $s) { Start-Sleep -Milliseconds 200 }
  $i++
  & "$here\cap.ps1" -Class MydrivePopup -Out "$here\build\design_$i.png"
}
