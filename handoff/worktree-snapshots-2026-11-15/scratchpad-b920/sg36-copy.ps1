# SG-36: copy sangam\ from the integration tree into the private worktree wt-sg36.
# Excludes ONLY build outputs: every directory named target-* and a module-root target\ (a directory that has a
# pom.xml next to it). Source folders named "target" (sangam-programme ...\programme\target) are kept.
$ErrorActionPreference = 'Stop'
$src = 'D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam'
$dst = 'C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\wt-sg36\sangam'

$exclude = New-Object System.Collections.Generic.List[string]
$stack = New-Object System.Collections.Generic.Stack[string]
$stack.Push($src)
while ($stack.Count -gt 0) {
    $d = $stack.Pop()
    foreach ($c in [System.IO.Directory]::EnumerateDirectories($d)) {
        $n = [System.IO.Path]::GetFileName($c)
        if ($n -like 'target-*') { $exclude.Add($c); continue }
        if ($n -eq 'target' -and (Test-Path -LiteralPath (Join-Path $d 'pom.xml'))) { $exclude.Add($c); continue }
        $stack.Push($c)
    }
}
"excluded build-output dirs: $($exclude.Count)"
$exclude | ForEach-Object { '  ' + $_.Substring($src.Length) }

if (Test-Path -LiteralPath $dst) { throw "destination already exists: $dst" }
New-Item -ItemType Directory -Force -Path $dst | Out-Null
$args = @($src, $dst, '/E', '/R:1', '/W:1', '/MT:16', '/NFL', '/NDL', '/NP', '/NJH')
if ($exclude.Count -gt 0) { $args += '/XD'; $args += $exclude }
& robocopy @args | Select-Object -Last 12
$rc = $LASTEXITCODE
"robocopy exit code: $rc"
if ($rc -ge 8) { throw "robocopy failed: $rc" }

# sanity: the source folder named target in sangam-programme must be present
$keep = Join-Path $dst 'sangam-programme\src\main\java\org\sangam\programme\target'
"programme target source kept: $(Test-Path -LiteralPath $keep) ($((Get-ChildItem -LiteralPath $keep -File -Recurse | Measure-Object).Count) files)"
$srcCount = (Get-ChildItem -LiteralPath (Join-Path $src 'sangam-programme\src\main\java\org\sangam\programme\target') -File -Recurse | Measure-Object).Count
"programme target source files in integration tree: $srcCount"
