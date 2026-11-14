# SG-36 / SL-61 / SL-62 unit runs from the private worktree wt-sg36 (no lock: plain unit tests / compile, no
# Testcontainers, no failsafe). One Maven run at a time. Never "install". JDK 25 (CI compiles with 21.0.13 / 25 / 27).
param(
    [string]$Pl = "sangam-integration",
    [string]$Goal = "test",
    [string]$Tests = "",
    [string]$Log = "sg36.log",
    [string]$Jdk = "25"
)
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$W = "$S\wt-sg36\sangam"
$out = Join-Path $S $Log
if ($Jdk -eq "25") { $env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8" } else { $env:JAVA_HOME = (Split-Path (Split-Path (Get-Command java).Source)) }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
[Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", $null)
$a = @("-o", "-Dsangam.target=target-sg36", "-pl", $Pl, "-am")
$a += ($Goal -split ' ')
if ($Tests) { $a += @("-Dtest=$Tests", "-Dsurefire.failIfNoSpecifiedTests=false") }
Push-Location $W
try {
    "[run] $(Get-Date -Format o) JAVA_HOME=$env:JAVA_HOME ARGS: $($a -join ' ')" | Set-Content $out
    & .\mvnw.cmd @a *>> $out
    "EXIT $LASTEXITCODE" | Add-Content $out
    "[run] $(Get-Date -Format o) done" | Add-Content $out
} finally {
    Pop-Location
}
Select-String -LiteralPath $out -Pattern "Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+|^EXIT|BUILD (SUCCESS|FAILURE)|COMPILATION ERROR|\[ERROR\] .*\.java" | Select-Object -Last 40 | ForEach-Object { $_.Line }

