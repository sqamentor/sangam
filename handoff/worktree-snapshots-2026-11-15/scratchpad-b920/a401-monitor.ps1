# Emits one line per new event of the APPROVE-401 runs (runner-log lines and per-class test results), exits when the
# red/green chain and the PostgreSQL run 2 are both done.
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$runners = @("a401-jdk25-red", "a401-jdk25-red-1", "a401-jdk25-red-2-all", "a401-jdk25-red-2", "a401-it2")
$logs = @("a401-jdk25-red-1", "a401-jdk25-red-2-all", "a401-jdk25-red-2", "a401-jdk25-red-green", "a401-it2")
$seen = @{}
function Emit([string]$key, [string]$line) {
    if (-not $seen.ContainsKey($key)) { $seen[$key] = $true; [Console]::Out.WriteLine($line); [Console]::Out.Flush() }
}
while ($true) {
    foreach ($r in $runners) {
        $p = "$S\$r.runner.log"
        if (Test-Path $p) {
            foreach ($l in Get-Content $p -ErrorAction SilentlyContinue) {
                if ($l -match "RUNNER DONE|maven exit|exit \d|mismatches|ACQUIRE|lock acquired|green-final|corrected run") { Emit "$r|$l" "[$r] $l" }
            }
        }
    }
    foreach ($g in $logs) {
        $p = "$S\$g.log"
        if (Test-Path $p) {
            foreach ($m in Select-String -Path $p -Pattern "Tests run:.*-- in org|COMPILATION ERROR|BUILD FAILURE" -ErrorAction SilentlyContinue) {
                $t = $m.Line.Substring(0, [Math]::Min(230, $m.Line.Length))
                Emit "$g|$($m.LineNumber)" "[$g] $t"
            }
        }
    }
    $chain = (Test-Path "$S\a401-jdk25-red.runner.log") -and (Select-String -Quiet -Path "$S\a401-jdk25-red.runner.log" -Pattern "RUNNER DONE")
    $pg = (Test-Path "$S\a401-it2.runner.log") -and (Select-String -Quiet -Path "$S\a401-it2.runner.log" -Pattern "RUNNER DONE")
    if ($chain -and $pg) { [Console]::Out.WriteLine("ALL DONE"); break }
    Start-Sleep -Seconds 15
}
