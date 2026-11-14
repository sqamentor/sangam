$ErrorActionPreference = 'Stop'
$scratch = Split-Path -Parent $MyInvocation.MyCommand.Path
$csvPath = 'D:\GITHUB  Project\Custom WorkFlow\spike\results\TRACEABILITY-REGISTER.csv'
$data = @{}
foreach ($l in [IO.File]::ReadAllLines((Join-Path $scratch 'sl36.tsv'))) {
  if (-not $l.Trim()) { continue }
  $p = $l.Split("`t"); $data[$p[0]] = $p
}
$raw = [IO.File]::ReadAllText($csvPath)
$nl = if ($raw.Contains("`r`n")) { "`r`n" } else { "`n" }
$lines = $raw -split "`r?`n"
$header = $lines[0]
function Q($s) { if ($s -match '[",\r\n]') { '"' + $s.Replace('"','""') + '"' } else { $s } }
$done = 0
for ($i = 1; $i -lt $lines.Count; $i++) {
  $ln = $lines[$i]; if (-not $ln) { continue }
  $id = $ln.Split(',')[0]
  if (-not $data.ContainsKey($id)) { continue }
  $r = ($header + "`n" + $ln) | ConvertFrom-Csv
  $d = $data[$id]
  $r.status = $d[1]; $r.'where (module:file / pack)' = $d[2]; $r.'tests that prove it' = $d[3]
  $r.note = 'verified 2026-11-08 (SL-36): ' + $d[4]
  $cols = $header.Split(',')
  $lines[$i] = ($cols | ForEach-Object { Q ([string]$r.$_) }) -join ','
  $done++
}
[IO.File]::WriteAllText($csvPath, ($lines -join $nl))
"rows updated: $done"
