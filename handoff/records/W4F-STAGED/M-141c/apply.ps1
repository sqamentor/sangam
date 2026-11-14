$ErrorActionPreference = "Stop"
$root = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$s = $PSScriptRoot
Set-Location $root
$enc = New-Object Text.UTF8Encoding $false
# refuse to overwrite a main file that changed since W4-F staged this (checkpoint content at 2026-11-12T18:00Z)
$expected = @{
    "sangam-rest/src/main/java/org/sangam/rest/support/TxSupport.java" = "CDF926A1B1DBA4492775BF65574B789ABBDE9369C813319922E9A4BA404B6147"
    "sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/ReadTransactions.java" = "B357AD3C06A21D95A004A9E5FF8A33513BD658963DDCB9998EE9AB26F1D11CC8"
    "sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonOpsAdapter.java" = "0FD62BB2D57921D43FC734EEF94449D12A717720C1346DE83069D575EA920D7D"
    "sangam-rest/src/main/java/org/sangam/rest/adapter/jdbc/JdbcAuditExportAdapter.java" = "0F5B1F5890EE4268CE1ECC121170BCD290C30FA1FFFEE58EE4467544715DB4D2"
}
foreach ($k in $expected.Keys) {
    $h = (Get-FileHash (Join-Path $root $k) -Algorithm SHA256).Hash
    if ($h -ne $expected[$k]) { throw "$k changed since staging ($h); re-apply the M-141c edit by hand instead" }
}# 1. main files and new files from the staged copies
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
