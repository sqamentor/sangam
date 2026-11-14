set -euo pipefail
umask 077
hdr="$RUNNER_TEMP/central-auth.txt"
printf 'Authorization: Bearer %s\n' "$(printf '%s:%s' "$MAVEN_CENTRAL_TOKEN_USERNAME" "$MAVEN_CENTRAL_TOKEN_PASSWORD" | base64 -w0)" > "$hdr"
id=$(curl --fail-with-body -sS -X POST -H @"$hdr" \
  -F "bundle=@$RUNNER_TEMP/central-bundle.zip;type=application/octet-stream" \
  "https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED&name=sangam-$V")
echo "Central Portal deployment id: $id (validation runs in the Portal; publishing is the owner's act, decision 112)"
curl --fail-with-body -sS -X POST -H @"$hdr" "https://central.sonatype.com/api/v1/publisher/status?id=$id"
rm -f "$hdr"
