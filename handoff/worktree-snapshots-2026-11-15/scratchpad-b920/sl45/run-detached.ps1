$sp = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\sl45"
try {
    & "$sp\sl45-pg-redgreen.ps1" *> "$sp\pg-summary.txt"
} finally {
    "done $(Get-Date -Format o)" | Set-Content "$sp\pg-done.txt"
}
