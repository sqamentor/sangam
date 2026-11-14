param([ValidateSet("compile", "unit")][string]$Mode, [string]$Log)
# Maven in the INTEGRATION tree (no lock: compile, or H2/unit tests only). Build output goes to target-ifmswf (gitignored).
$out = Join-Path $PSScriptRoot $Log
$it = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
"[run] $(Get-Date -Format o) mode=$Mode tree=$it" | Set-Content $out
Set-Location $it
if ($Mode -eq "compile") {
    & .\mvnw.cmd -o "-Dsangam.target=target-ifmswf" -pl sangam-server -am -DskipTests compile *>> $out
} else {
    & .\mvnw.cmd -o "-Dsangam.target=target-ifmswf" -pl sangam-packs,sangam-flowable-ports -am clean test *>> $out
}
"EXIT $LASTEXITCODE" | Add-Content $out
"[run] $(Get-Date -Format o) done" | Add-Content $out
