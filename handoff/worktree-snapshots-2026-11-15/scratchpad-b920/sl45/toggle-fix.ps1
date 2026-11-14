param([ValidateSet("fix","nofix")][string]$Mode)
$wt = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-iso\sangam"
$src = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$bundle = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\sl45\fix-bundle"
$product = @("sangam-engine\src\main\java\org\sangam\engine\deploy\TenantIsolationException.java",
             "sangam-engine\src\main\java\org\sangam\engine\SangamProcessEnginePlugin.java",
             "sangam-server\src\main\java\org\sangam\server\config\ObservabilityConfig.java",
             "sangam-rest\src\main\java\org\sangam\rest\web\ProblemHandler.java")
$new = "sangam-engine\src\main\java\org\sangam\engine\deploy\TenantScopedCreation.java"
if ($Mode -eq "fix") {
  foreach ($f in $product + $new) { Copy-Item (Join-Path $bundle $f) (Join-Path $wt $f) -Force }
} else {
  foreach ($f in $product) { Copy-Item (Join-Path $src $f) (Join-Path $wt $f) -Force }
  Remove-Item (Join-Path $wt $new) -Force -ErrorAction SilentlyContinue
}
foreach ($f in $product + $new) { $p = Join-Path $wt $f; "{0}: {1}" -f $f, $(if (Test-Path $p) { (Get-FileHash $p).Hash.Substring(0,12) } else { "absent" }) }


