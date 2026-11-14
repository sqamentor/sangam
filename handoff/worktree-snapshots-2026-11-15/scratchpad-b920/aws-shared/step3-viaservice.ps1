$ErrorActionPreference = "Stop"
$env:AWS_DEFAULT_REGION = "ap-south-1"
$A = "196337091345"
$D = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\aws-shared"
foreach ($name in "sangam-ssm-read", "sangam-ci-ssm-read") {
  $arn = "arn:aws:iam::${A}:policy/$name"
  $v = aws iam get-policy --policy-arn $arn --query Policy.DefaultVersionId --output text
  $doc = aws iam get-policy-version --policy-arn $arn --version-id $v --query PolicyVersion.Document --output json | ConvertFrom-Json
  $st = $doc.Statement | Where-Object { @($_.Action) -contains "kms:Decrypt" }
  if (-not $st) { throw "$name has no kms:Decrypt statement" }
  $cond = [pscustomobject]@{ StringEquals = [pscustomobject]@{ "kms:ViaService" = "ssm.ap-south-1.amazonaws.com" } }
  $st | Add-Member -NotePropertyName Condition -NotePropertyValue $cond -Force
  $file = Join-Path $D "$name-v3.json"
  $doc | ConvertTo-Json -Depth 8 | Set-Content $file -Encoding ascii
  $nv = aws iam create-policy-version --policy-arn $arn --policy-document "file://$file" --set-as-default --query PolicyVersion.VersionId --output text
  "$name : $v -> $nv"
  $old = aws iam list-policy-versions --policy-arn $arn --query "Versions[?IsDefaultVersion==``false``].VersionId" --output text
  foreach ($o in ($old -split "\s+")) { if ($o) { aws iam delete-policy-version --policy-arn $arn --version-id $o; "  deleted old $o" } }
}
