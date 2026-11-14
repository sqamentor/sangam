# Emits test-result lines of the given run logs and the runner-log milestones; exits when every runner log says RUNNER DONE.
param([string[]]$Tags = @("a401-it3"))
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$seen = @{}
function Emit([string]$key, [string]$line) {
    if (-not $seen.ContainsKey($key)) { $seen[$key] = $true; [Console]::Out.WriteLine($line); [Console]::Out.Flush() }
}
while ($true) {
    $done = 0
    foreach ($t in $Tags) {
        $r = "$S\$t.runner.log"
        if (Test-Path $r) {
            foreach ($l in Get-Content $r -ErrorAction SilentlyContinue) {
                if ($l -match "RUNNER DONE|exit|mismatches|lock acquired|start") { Emit "$t|$l" "[$t] $l" }
            }
            if (Select-String -Quiet -Path $r -Pattern "RUNNER DONE") { $done++ }
        }
        foreach ($g in @("$S\$t.log", "$S\$t-green.log")) {
            if (Test-Path $g) {
                foreach ($m in Select-String -Path $g -Pattern "Tests run:.*-- in org|COMPILATION ERROR|BUILD FAILURE" -ErrorAction SilentlyContinue) {
                    Emit "$g|$($m.LineNumber)" ("[$t] " + $m.Line.Substring(0, [Math]::Min(230, $m.Line.Length)))
                }
            }
        }
    }
    if ($done -eq $Tags.Count) { [Console]::Out.WriteLine("ALL DONE: " + ($Tags -join ",")); break }
    Start-Sleep -Seconds 10
}
