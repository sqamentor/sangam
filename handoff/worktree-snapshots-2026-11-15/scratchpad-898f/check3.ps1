$ErrorActionPreference = "Continue"
$lock = "C:/Users/LOKEND~2/AppData/Local/Temp/claude/D--GITHUB--Project-IFMS/ff9f2caf-1246-4228-ae70-82a16cf209ae/scratchpad/heavy_lock.py"
$dir = $PSScriptRoot
$p = "D:\GITHUB  Project\Custom WorkFlow\sangam\deploy\native\sdk-worker\pom.xml"
$s = [IO.File]::ReadAllText($p)
$a = $s.IndexOf("  Not a reactor module"); $b = $s.IndexOf("  Container: deploy/docker")
$s = $s.Substring(0,$a) + "  Not a reactor module on purpose (it needs GraalVM, which the normal build does not). Build commands and the`n  smoke check are in deploy/SUPPORT-MATRIX.md section 5 and in the java-native job of sdks.yml. From sangam/, after`n  installing sdks/java: mvn -f deploy/native/sdk-worker/pom.xml package (JVM jar), and add -Pnative for the executable.`n`n" + $s.Substring($b)
[IO.File]::WriteAllText($p,$s)
python -E -X utf8 $lock acquire customwf "WS-DEPLOY JDK 25: SCALE apps + native worker JVM smoke"
try {
  $env:JAVA_HOME = "$env:USERPROFILE\tools\jdk25\jdk-25.0.5.0.2+1"
  $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
  Set-Location "D:\GITHUB  Project\Custom WorkFlow\sangam"
  & .\mvnw.cmd -B -ntp -f deploy/native/sdk-worker/pom.xml package *> "$dir\native.log"
  "NATIVE-MODULE EXIT=$LASTEXITCODE" | Out-File -Append -Encoding utf8 "$dir\summary2.txt"
  $jar = Get-ChildItem deploy\native\sdk-worker\target -Filter "*-jar-with-dependencies.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($jar) { & java -jar $jar.FullName --smoke *> "$dir\smoke.log"; "SMOKE EXIT=$LASTEXITCODE" | Out-File -Append -Encoding utf8 "$dir\summary2.txt" }
} finally { python -E -X utf8 $lock release customwf }
Get-Content "$dir\summary2.txt"
foreach($f in 'scale','native','smoke'){ "== $f"; Get-Content "$dir\$f.log" -ErrorAction SilentlyContinue | Select-String 'BUILD |ERROR.*(\.java|Failed|Non-parse)|native smoke|FAIL ' | Select-Object -First 8 | ForEach-Object { $_.Line } }
