$env:AWS_DEFAULT_REGION = "ap-south-1"
$A = "196337091345"
$D = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\aws-shared"
$keys = @{}
$specs = @(
  @("alias/shared-config", "shared", "Shared SSM config for Sangam, OneIFMS, VyaparTrust"),
  @("alias/sangam-config", "sangam", "Sangam-only SSM config")
)
foreach ($s in $specs) {
  $alias = $s[0]; $tag = $s[1]; $desc = $s[2]
  $k = aws kms create-key --description "$desc" --tags "TagKey=project,TagValue=$tag" --query KeyMetadata.KeyId --output text
  aws kms create-alias --alias-name $alias --target-key-id $k
  aws kms enable-key-rotation --key-id $k
  $keys[$alias] = $k
  "$alias -> $k"
}
$ks = $keys["alias/shared-config"]; $kg = $keys["alias/sangam-config"]
$specs2 = @(@("sangam-ssm-read", "sangam-ssm-read.json"), @("sangam-ci-ssm-read", "sangam-ci-read.json"))
foreach ($p in $specs2) {
  $name = $p[0]; $file = Join-Path $D $p[1]
  $j = Get-Content $file -Raw | ConvertFrom-Json
  ($j.Statement | Where-Object Sid -eq "DecryptOwnKeys").Resource = @("arn:aws:kms:ap-south-1:${A}:key/$ks", "arn:aws:kms:ap-south-1:${A}:key/$kg")
  $j | ConvertTo-Json -Depth 6 | Set-Content $file -Encoding ascii
  $arn = "arn:aws:iam::${A}:policy/$name"
  $v = aws iam create-policy-version --policy-arn $arn --policy-document "file://$file" --set-as-default --query PolicyVersion.VersionId --output text
  "$name default version now $v"
  $old = aws iam list-policy-versions --policy-arn $arn --query "Versions[?IsDefaultVersion==``false``].VersionId" --output text
  foreach ($o in ($old -split "\s+")) { if ($o) { aws iam delete-policy-version --policy-arn $arn --version-id $o; "deleted old $name $o" } }
}
