$env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8"
$sp = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$srv = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\sangam-server"
$cp = (Get-Content -Raw "$sp\server-cp-w4b.txt").Trim().Replace([char]92, [char]47)
$outd = Join-Path $sp "srv-javac"
if (Test-Path $outd) { Get-ChildItem $outd | Remove-Item -Recurse -Force }
New-Item -ItemType Directory -Force $outd | Out-Null
$srcs = Get-ChildItem -Recurse "$srv\src\main\java", "$srv\src\test\java" -Filter *.java | ForEach-Object { '"' + $_.FullName.Replace([char]92, [char]47) + '"' }
$srcs | Set-Content -Encoding ascii "$sp\srv-srcs.txt"
('-cp "' + $cp + '"') | Set-Content -Encoding ascii "$sp\srv-args.txt"
& "$env:JAVA_HOME\bin\javac.exe" -proc:none -nowarn -encoding UTF-8 --release 25 -Xmaxerrs 500 -d $outd "@$sp\srv-args.txt" "@$sp\srv-srcs.txt" *> "$sp\srv-javac.log"
"EXIT $LASTEXITCODE"
Select-String -Path "$sp\srv-javac.log" -Pattern "error:" | ForEach-Object { $_.Line.Substring(0, [Math]::Min(250, $_.Line.Length)) } | Select-Object -First 25
