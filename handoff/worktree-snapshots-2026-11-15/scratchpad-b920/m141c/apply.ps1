$ErrorActionPreference = "Stop"
$root = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$s = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\m141c"
Set-Location $root
$enc = New-Object Text.UTF8Encoding $false
# 1. main files and new files from the staged copies
Get-ChildItem $s -Filter "*__*" | ForEach-Object {
    $rel = $_.Name -replace "__", "/"
    $dst = Join-Path $root $rel
    New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
    Copy-Item $_.FullName $dst -Force
    "restored $rel"
}
# 2. consumers: constructor rename plus one import
function Fix($rel, $from, $to, $import) {
    $p = Join-Path $root $rel
    $t = [IO.File]::ReadAllText($p)
    $nl = if ($t.Contains("`r`n")) { "`r`n" } else { "`n" }
    $n = $t.Replace($from, $to)
    if ($n -eq $t) { throw "no '$from' in $rel" }
    if ($import -and -not $n.Contains("import $import;")) {
        $m = [regex]::Match($n, "(?m)^import [^\r\n]+;\r?\n")
        $n = $n.Insert($m.Index, "import $import;$nl")
    }
    [IO.File]::WriteAllText($p, $n, $enc)
    "fixed $rel"
}
$cfg = "sangam-rest/src/main/java/org/sangam/rest/config/SangamRestAutoConfiguration.java"
Fix $cfg "new TxSupport(" "new SpringTxSupport(" $null
Fix $cfg "new org.sangam.rest.adapter.operaton.ReadTransactions(" "new SpringReadTransactions(" $null
$tests = git grep -l "new TxSupport(" -- "*/src/test/*.java"
foreach ($f in $tests) { Fix $f "new TxSupport(" "new SpringTxSupport(" "org.sangam.rest.config.SpringTxSupport" }
Fix "sangam-server/src/test/java/org/sangam/server/restadapter/TaskReadTransactionsH2Test.java" "new ReadTransactions(" "new SpringReadTransactions(" "org.sangam.rest.config.SpringReadTransactions"
