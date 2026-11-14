param([string]$Log = "step7-check.log")
# Step-7 preparation in the PRIVATE worktree only (no lock: H2 unit tests and a compile):
#  1. green: SectorConformanceTest with the BUDGET_DISTRIBUTION powers table (gov_ifms.py of the worktree);
#  2. red:   the same new test with the integration tree's gov_ifms.py (no table), packs regenerated;
#  3. restore the worktree gov_ifms.py, regenerate, --check;
#  4. sangam-server test-compile (the prepared IfmsPacksIT set-up).
$sp = $PSScriptRoot
$out = Join-Path $sp $Log
$wt = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-ifmswf\sangam"
$it = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$gov = "sangam-packs\tools\packgen\gov_ifms.py"
$mine = Join-Path $sp "ifmswf-step7\$gov"
$test = "org.sangam.packs.SectorConformanceTest"
Set-Location $wt
"== 1 green (worktree powers table)" | Set-Content $out
& .\mvnw.cmd -o "-Dsangam.target=target-ifmswf" -pl sangam-packs -am test "-Dtest=SectorConformanceTest" "-Dsurefire.failIfNoSpecifiedTests=false" *>> $out
"GREEN_EXIT $LASTEXITCODE" | Add-Content $out
try {
    "== 2 red (integration-tree gov_ifms.py, no BUDGET_DISTRIBUTION table)" | Add-Content $out
    Copy-Item (Join-Path $it $gov) (Join-Path $wt $gov) -Force
    python sangam-packs/tools/packgen/main.py *>> $out
    & .\mvnw.cmd -o "-Dsangam.target=target-ifmswf" -pl sangam-packs -am test "-Dtest=SectorConformanceTest#ifmsBudgetDistributionByThePrincipalAccountsOfficer" "-Dsurefire.failIfNoSpecifiedTests=false" *>> $out
    "RED_EXIT $LASTEXITCODE" | Add-Content $out
} finally {
    "== 3 restore the worktree gov_ifms.py" | Add-Content $out
    Copy-Item $mine (Join-Path $wt $gov) -Force
    python sangam-packs/tools/packgen/main.py *>> $out
    python sangam-packs/tools/packgen/main.py --check *>> $out
    "CHECK_EXIT $LASTEXITCODE" | Add-Content $out
}
"== 4 sangam-server test-compile" | Add-Content $out
& .\mvnw.cmd -o -q "-Dsangam.target=target-ifmswf" -pl sangam-server -am -DskipTests test-compile *>> $out
"COMPILE_EXIT $LASTEXITCODE" | Add-Content $out
