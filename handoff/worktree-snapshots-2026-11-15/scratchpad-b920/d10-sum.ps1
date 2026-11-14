param([string]$Label)
$L = 'C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\d10-logs'
$log = Join-Path $L ($Label + '.log')
foreach ($m in Select-String -Path $log -Pattern '^\[\w+\] Tests run: (\d+), Failures: (\d+), Errors: (\d+).* -- in (\S+)') {
    $g = $m.Matches[0].Groups
    '{0,4} F{1} E{2}  {3}' -f $g[1].Value, $g[2].Value, $g[3].Value, ($g[4].Value -replace '^org\.sangam\.(server|integration)\.', '')
}
Select-String -Path $log -Pattern '^\[(INFO|ERROR|WARNING)\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$|^\[\d{4}-.*EXIT|BUILD (SUCCESS|FAILURE)|Total time|random seed|runOrder' | ForEach-Object { $_.Line }
'D-10 messages: ' + (Select-String -Path $log -Pattern 'D-10: a second Sangam engine').Count
'Double NPE: ' + (Select-String -Path $log -Pattern 'Double.doubleValue').Count
