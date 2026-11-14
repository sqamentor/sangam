$ErrorActionPreference = "Continue"
$lock = "C:/Users/LOKEND~2/AppData/Local/Temp/claude/D--GITHUB--Project-IFMS/ff9f2caf-1246-4228-ae70-82a16cf209ae/scratchpad/heavy_lock.py"
$dir = $PSScriptRoot
python -E -X utf8 $lock acquire customwf "WS-DEPLOY JDK 25 compile check + native worker JVM smoke"
try {
  $env:JAVA_HOME = "$env:USERPROFILE\tools\jdk25\jdk-25.0.5.0.2+1"
  $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
  Set-Location "D:\GITHUB  Project\Custom WorkFlow\sangam"
  $mvn = ".\mvnw.cmd"
  & $mvn -B -ntp -DskipTests test-compile *> "$dir\all.log"
  "REACTOR EXIT=$LASTEXITCODE" | Out-File -Encoding utf8 "$dir\summary.txt"
  & $mvn -B -ntp -DskipTests -pl sdks/java -am install *> "$dir\sdk.log"
  "SDK EXIT=$LASTEXITCODE" | Out-File -Append -Encoding utf8 "$dir\summary.txt"
  & $mvn -B -ntp -f deploy/native/sdk-worker/pom.xml package *> "$dir\native.log"
  "NATIVE-MODULE EXIT=$LASTEXITCODE" | Out-File -Append -Encoding utf8 "$dir\summary.txt"
  $jar = Get-ChildItem deploy\native\sdk-worker\target -Filter "*-jar-with-dependencies.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($jar) { & java -jar $jar.FullName --smoke *> "$dir\smoke.log"; "SMOKE EXIT=$LASTEXITCODE" | Out-File -Append -Encoding utf8 "$dir\summary.txt" }
} finally { python -E -X utf8 $lock release customwf }
