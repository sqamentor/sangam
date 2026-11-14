$ErrorActionPreference = 'Stop'
Set-Location 'D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524'
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$dir = "D:\GITHUB  Project\sangam-handoff\snapshots\$stamp"
New-Item -ItemType Directory -Path $dir | Out-Null
git rev-parse HEAD | Set-Content "$dir\BASE-COMMIT.txt"
git diff --binary HEAD "--output=$dir\tracked.patch"
$untracked = git ls-files --others --exclude-standard
$untracked | Set-Content "$dir\untracked-list.txt"
tar -cf "$dir\untracked.tar" -T "$dir\untracked-list.txt"
git status --porcelain | Set-Content "$dir\status.txt"
# memory backup refresh
$mem = 'C:\Users\Lokendra Singh\.claude\projects\D--GITHUB--Project-Custom-WorkFlow\memory'
Copy-Item $mem "$dir\memory" -Recurse
"dir=$dir"
"patch bytes=" + (Get-Item "$dir\tracked.patch").Length
"untracked files=" + @($untracked).Count + " tar bytes=" + (Get-Item "$dir\untracked.tar").Length
"status lines=" + @(Get-Content "$dir\status.txt").Count
# verify: patch applies cleanly to base in a temp index
$ErrorActionPreference = 'Continue'
git apply --check -R "$dir\tracked.patch" 2>&1 | Select-Object -First 3
"verify-reverse-exit=$LASTEXITCODE"
