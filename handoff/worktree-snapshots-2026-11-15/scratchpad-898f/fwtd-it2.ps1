$L = "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad"
$W = "$S\wt-fwtd"
$web = "sangam/sangam-server/src/main/java/org/sangam/server/web"
$sec = "sangam/sangam-server/src/main/java/org/sangam/server/config/SecurityConfig.java"
python $L acquire customwf-fwtd "PG FwtWorkerCompatIT with fix and with fix reverted"
if ($LASTEXITCODE -ne 0) { "ACQUIRE FAILED ($LASTEXITCODE): not running"; exit 2 }
function RunIt($log) {
    Push-Location "$W\sangam"
    .\mvnw.cmd "-Dsangam.target=target-fwtd" -Psangam-db-it "-Dsangam.db=postgres" -pl sangam-server -am verify `
        "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=FwtWorkerCompatIT" "-Dfailsafe.failIfNoSpecifiedTests=false" *> "$S\$log"
    "$log mvn exit $LASTEXITCODE"
    Pop-Location
}
$bak = "$S\fwtd-bak"; New-Item -ItemType Directory -Force $bak | Out-Null
try {
    RunIt "fwtd-it-fixed.log"
    Copy-Item "$W\$web\FlowableWorkerCompatController.java", "$W\$web\FlowableWorkerCompatProblemAdvice.java", "$W\$sec" $bak -Force
    # without the fixes: controller and SecurityConfig as in the main tree before this change, no advice
    Copy-Item "D:\GITHUB  Project\Custom WorkFlow\$web\FlowableWorkerCompatController.java" "$W\$web\" -Force
    (Get-Content "$bak\SecurityConfig.java") | Where-Object { $_ -notmatch 'dispatcherTypeMatchers\(jakarta.servlet.DispatcherType.ERROR\)' } | Set-Content "$W\$sec"
    Remove-Item "$W\$web\FlowableWorkerCompatProblemAdvice.java"
    RunIt "fwtd-it-reverted.log"
} finally {
    Copy-Item "$bak\FlowableWorkerCompatController.java", "$bak\FlowableWorkerCompatProblemAdvice.java" "$W\$web\" -Force
    Copy-Item "$bak\SecurityConfig.java" "$W\$sec" -Force
    python $L release customwf-fwtd
}
foreach ($f in "fwtd-it-fixed.log", "fwtd-it-reverted.log") { "== $f"; Select-String "$S\$f" -Pattern "Tests run:.*IT$|^\[ERROR\]   \w" | % Line }
