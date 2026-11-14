param($db,$tgt,$it,$tag,$tz)
$L="C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
$log="C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\$tag.log"
python $L acquire customwf-matrix "$tag acceptance"
if ($LASTEXITCODE -ne 0) { "ACQUIRE FAILED ($LASTEXITCODE) - not running"; exit 3 }
try {
  if ($tz) { $env:JAVA_TOOL_OPTIONS="-Duser.timezone=$tz" } else { $env:JAVA_TOOL_OPTIONS=$null }
  Set-Location "D:\GITHUB  Project\Custom WorkFlow\sangam"
  .\mvnw.cmd -q -pl sangam-server -am clean verify "-Dsangam.target=$tgt" "-Dtest=NoUnit" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false" "-Dsangam.db=$db" "-Dit.test=$it" *> $log
} finally { python $L release customwf-matrix }
Get-ChildItem "D:\GITHUB  Project\Custom WorkFlow\sangam\sangam-server\$tgt\failsafe-reports\*.txt" | % { (Get-Content $_ | Select-String "Tests run").Line }
