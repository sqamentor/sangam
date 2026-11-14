$ErrorActionPreference = 'Stop'
$scratch = Split-Path -Parent $MyInvocation.MyCommand.Path
$data = [ordered]@{}
foreach ($l in [IO.File]::ReadAllLines((Join-Path $scratch 'sl36.tsv'))) { if ($l.Trim()) { $p = $l.Split("`t"); $data[$p[0]] = $p } }
function E($s) { ([string]$s).Replace('|','\|') }
# 1. register section rows
$sb = New-Object Text.StringBuilder
foreach ($k in $data.Keys) { $d = $data[$k]; $fam = ($k -split '-')[0]
  [void]$sb.AppendLine("| $k | $fam | $($d[1]) | $(E $d[2]) | $(E $d[3]) | $(E $d[4]) |") }
[IO.File]::WriteAllText((Join-Path $scratch 'reg-rows.md'), $sb.ToString())
# 2. recheck: replace column 5 of NG rows
$rp = 'D:\GITHUB  Project\Custom WorkFlow\spike\results\PREMIUM-GAP-RECHECK.md'
$raw = [IO.File]::ReadAllText($rp); $nl = if ($raw.Contains("`r`n")) { "`r`n" } else { "`n" }
$lines = $raw -split "`r?`n"; $n = 0; $inFindings = $false
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($lines[$i] -like '| ID | Feature | Vendor | Source | Sangam file or MISSING | Confidence |') {
    $lines[$i] = '| ID | Feature | Vendor | Source | Verified 2026-11-08: status; evidence file; test (class#method or "no test") | Confidence |'; $inFindings = $true; continue }
  if ($inFindings -and $lines[$i] -match '^\| (NG-\d\d) \|') {
    $id = $Matches[1]; $d = $data[$id]
    $cells = $lines[$i].Split('|')   # '', id, feature, vendor, source, mapping, conf, ''
    # feature/vendor/source cells contain no escaped pipes in this table
    $cells[5] = " **$($d[1])**; $(E $d[2]); $(E $d[3])" + $(if ($d[4]) { "; $(E $d[4])" } else { '' }) + ' '
    $lines[$i] = $cells -join '|'; $n++ }
  if ($inFindings -and $lines[$i] -match '^## ') { $inFindings = $false }
}
[IO.File]::WriteAllText($rp, ($lines -join $nl))
"recheck rows: $n"
