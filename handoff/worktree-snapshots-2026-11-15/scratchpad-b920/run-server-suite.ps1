# SL-53 side check (unit tests, no lock): the same server test selection on the committed 1105d9c snapshot (no SL-53
# change, -Which base) or on wt-dst (-Which mine). One Maven run at a time.
# Default selection = surefire's default includes (Test*, *Test, *Tests, *TestCase), restricted to sangam-server's package:
# no *IT acceptance class is selected (they belong to failsafe, profile sangam-db-it, under the heavy lock).
param([string]$Which = "base",
      [string]$Tests = "org/sangam/server/**/Test*,org/sangam/server/**/*Test,org/sangam/server/**/*Tests,org/sangam/server/**/*TestCase",
      [string]$Log = "")
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$dir = if ($Which -eq "base") { "$S\base-1105d9c\sangam" } else { "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam" }
$target = if ($Which -eq "base") { "target-dstbase" } else { "target-dst" }
if (-not $Log) { $Log = "server-suite-$Which.log" }
$out = "$S\$Log"
[Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", $null)
Push-Location $dir
"ARGS: $Which $Tests" | Set-Content $out
& .\mvnw.cmd -o "-Dsangam.target=$target" -pl sangam-server -am test "-Dtest=$Tests" "-Dsurefire.failIfNoSpecifiedTests=false" *>> $out
"EXIT $LASTEXITCODE" | Add-Content $out
Pop-Location
Select-String $out -Pattern "Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$|^EXIT" | Select-Object -Last 3 | % Line
Select-String $out -Pattern "Tests run:.*(Failures: [1-9]|Errors: [1-9]).*-- in " | % { $_.Line -replace '.*-- in ','' } | Sort-Object -Unique
