# SG-36 green runs, one Maven run at a time (no lock: unit tests and compile only):
#  1. the sangam-integration unit suite, upstream built with -am (tests limited to org/sangam/integration/**)
#  2. sangam-server compile with -am
$S = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
& "$S\sg36-run.ps1" -Pl "sangam-integration" -Goal "test" -Tests "org/sangam/integration/**/*" -Log "sg36-green-suite.log"
& "$S\sg36-run.ps1" -Pl "sangam-server" -Goal "-DskipTests compile" -Log "sg36-server-compile.log"
