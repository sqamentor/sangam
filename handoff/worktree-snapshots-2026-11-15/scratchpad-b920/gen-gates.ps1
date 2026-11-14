$ErrorActionPreference = 'Stop'
Set-Location 'D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524'
$outs = @('sangam/sangam-packs/src/main/resources/profiles','sangam/sangam-persistence/src/main/resources/db/changelog',
  'sangam/sangam-control-plane/src/main/resources/db/changelog','sangam/sangam-rollup/src/main/resources/db/changelog',
  'sangam/sangam-packs/src/main/resources/packs','sangam/deploy/compose/keycloak/realm')
function Snap { $h=@{}; foreach ($o in $outs) { Get-ChildItem $o -Recurse -File | ForEach-Object { $h[$_.FullName] = (Get-FileHash $_.FullName).Hash } }; $h }
$before = Snap
$tmp = Join-Path $env:TEMP 'sangam-api-gate.yaml'
$steps = @(
  @('python','-I','sangam/sangam-packs/tools/profilegen/main.py'),
  @('python','-I','sangam/sangam-persistence/tools/generate_changelogs.py'),
  @('python','-I','sangam/sangam-control-plane/tools/generate_scale_changelogs.py'),
  @('python','sangam/sangam-packs/tools/packgen/main.py','--check'),
  @('python','sangam/sangam-rest/api/gen_openapi.py',$tmp)
)
foreach ($s in $steps) { & $s[0] $s[1..($s.Count-1)] 2>&1 | Select-Object -Last 3 | ForEach-Object { "  [$($s[-1])] $_" }; "EXIT $LASTEXITCODE : $($s -join ' ')" }
Push-Location sangam/deploy; & python -I compose/keycloak/generate_realm.py 2>&1 | Select-Object -Last 3; "EXIT $LASTEXITCODE : generate_realm"; Pop-Location
$api = (Get-FileHash $tmp).Hash -eq (Get-FileHash 'sangam/sangam-rest/api/sangam-api.yaml').Hash
"OPENAPI identical: $api"
$after = Snap
$changed = @($after.Keys | Where-Object { $before[$_] -ne $after[$_] }); $gone = @($before.Keys | Where-Object { -not $after.ContainsKey($_) })
"CHANGED: $($changed.Count)  NEW-OR-CHANGED list:"; $changed | ForEach-Object { "  $_" }
"REMOVED: $($gone.Count)"; $gone | ForEach-Object { "  $_" }
