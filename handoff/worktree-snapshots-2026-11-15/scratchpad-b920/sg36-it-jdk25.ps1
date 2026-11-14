# Post-apply checks in the INTEGRATION TREE, on JDK 25 (CI compiles with 21.0.13 / 25 / 27; SL-63).
# Unit tests only (no Testcontainers in these modules' surefire tests), so no heavy lock. Never "install".
param([string]$Log = "sg36-it-jdk25.log", [string]$Tests = "", [string]$Jdk = "25")
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$IT = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$out = Join-Path $S $Log
if ($Jdk -eq "25") { $env:JAVA_HOME = "D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8" } else { $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot" }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
[Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", $null)
Push-Location -LiteralPath $IT
try {
    "[run] $(Get-Date -Format o) JAVA_HOME=$env:JAVA_HOME" | Set-Content $out
    & "$env:JAVA_HOME\bin\java.exe" -version *>> $out
    $a = @("-o", "-Dsangam.target=target-sg36", "-pl", "sangam-integration", "-am", "test")
    if ($Tests) { $a += @("-Dtest=$Tests", "-Dsurefire.failIfNoSpecifiedTests=false") }
    "[run] ARGS: $($a -join ' ')" | Add-Content $out
    & .\mvnw.cmd @a *>> $out
    "EXIT $LASTEXITCODE" | Add-Content $out
    "[run] $(Get-Date -Format o) done" | Add-Content $out
} finally {
    Pop-Location
}
Select-String -LiteralPath $out -Pattern "SUCCESS \[|FAILURE \[|Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+|^EXIT|BUILD (SUCCESS|FAILURE)|COMPILATION ERROR" | ForEach-Object { $_.Line }

