param([string]$Pl = "sangam-engine", [string]$Tests = "", [string]$Log = "dst-unit.log", [string]$Goal = "test", [string]$Extra = "",
      [string]$JavaHome = "", [string]$Target = "target-dst")
# Unit/compile runs in wt-dst (no Testcontainers, no failsafe: no heavy lock). -JavaHome selects the JDK (SL-63 gate: JDK 25).
$W = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-dst\sangam"
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$out = Join-Path $S $Log
[Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", $null)
if ($JavaHome) { $env:JAVA_HOME = $JavaHome; $env:PATH = "$JavaHome\bin;" + $env:PATH }
Push-Location $W
try {
    $args2 = @("-o", "-Dsangam.target=$Target", "-pl", $Pl, "-am", $Goal)
    if ($Tests) { $args2 += @("-Dtest=$Tests", "-Dsurefire.failIfNoSpecifiedTests=false") }
    if ($Extra) { $args2 += ($Extra -split ' ') }
    "ARGS: $($args2 -join ' ')  JAVA_HOME=$env:JAVA_HOME" | Set-Content $out
    & .\mvnw.cmd --version *>> $out
    & .\mvnw.cmd @args2 *>> $out
    "EXIT $LASTEXITCODE" | Add-Content $out
} finally { Pop-Location }
