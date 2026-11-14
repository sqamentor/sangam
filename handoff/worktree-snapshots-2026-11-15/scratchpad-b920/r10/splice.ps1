param([string]$File, [string]$StartMarker, [string]$EndMarker, [string]$NewFile, [switch]$KeepEnd)
# Replaces the text from StartMarker up to and including EndMarker (or up to EndMarker when -KeepEnd) with the
# content of NewFile, keeping the file's line ending (CRLF or LF) and writing UTF-8 without BOM.
$text = [IO.File]::ReadAllText($File)
$crlf = $text.Contains("`r`n")
$new = [IO.File]::ReadAllText($NewFile).Replace("`r`n", "`n")
if ($crlf) { $new = $new.Replace("`n", "`r`n") }
$s = $text.IndexOf($StartMarker)
if ($s -lt 0) { throw "start marker not found" }
if ($text.IndexOf($StartMarker, $s + 1) -ge 0) { throw "start marker not unique" }
$e = $text.IndexOf($EndMarker, $s)
if ($e -lt 0) { throw "end marker not found" }
if (-not $KeepEnd) { $e += $EndMarker.Length }
$out = $text.Substring(0, $s) + $new + $text.Substring($e)
[IO.File]::WriteAllText($File, $out, (New-Object Text.UTF8Encoding $false))
"spliced $File crlf=$crlf"
