#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Development and pilot ONLY (owner decision 153; W4-D 19:49:29Z): makes the demo secrets of the PDF validator and the
# OCR sidecar at start, inside named volumes. Nothing here is committed and no secret is printed. Runs once per
# `docker compose up` in the one-shot service sidecar-secrets-init (profiles pdf and ocr, docker-compose.sidecars.yml).
#
# For each sidecar (pdf-validator, ocr), when its volume does not hold all three files yet:
#   token              one random 64-hex token (32 random bytes): the sidecar accepts it, the server sends it
#   keystore-password  one random 64-hex password
#   keystore.p12       PKCS#12 with an EC P-256 key and a self-signed certificate, SAN dns:<service name>
# Existing files are kept, so tokens and certificates survive a restart; delete the volume to make new ones (also when
# the certificate expires, SANGAM_SIDECAR_CERT_DAYS, default 397 days).
# The client volume (mounted only into the server) gets a copy of each token (<name>.token) and truststore.p12: the
# JRE's public CA list plus the two sidecar certificates, rebuilt on every run (store password "changeit": a trust
# store holds only public certificates). Files are owned by 10001:0 and readable by group 0 only (mode 0440), which
# fits the server (10001:0 or distroless 65532:0) and the sidecars (10001:0).
# Production: do not use this script. Give each sidecar its own secrets from your secret store (deploy/README.md).
set -eu
umask 077

OWNER="${SANGAM_SIDECAR_SECRETS_OWNER:-10001:0}"
DAYS="${SANGAM_SIDECAR_CERT_DAYS:-397}"
CLIENT=/secrets/client
CACERTS="${JAVA_HOME:?JAVA_HOME is not set}/lib/security/cacerts"

case "$DAYS" in
  ''|*[!0-9]*) echo "SANGAM_SIDECAR_CERT_DAYS must be a whole number of days" >&2; exit 1 ;;
esac
[ -d "$CLIENT" ] || { echo "the client secrets volume is not mounted at $CLIENT" >&2; exit 1; }

random_hex() {
  head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
  echo
}

make_sidecar() {
  name=$1
  dir=/secrets/$name
  [ -d "$dir" ] || { echo "the $name secrets volume is not mounted at $dir" >&2; exit 1; }
  if [ -s "$dir/token" ] && [ -s "$dir/keystore-password" ] && [ -s "$dir/keystore.p12" ]; then
    echo "$name: existing demo secrets kept"
  else
    rm -f "$dir/token" "$dir/keystore-password" "$dir/keystore.p12" "$dir/cert.pem"
    random_hex > "$dir/token"
    random_hex > "$dir/keystore-password"
    keytool -genkeypair -alias "$name" -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
      -dname "CN=$name, OU=Sangam development sidecar" -ext "SAN=dns:$name" -ext "EKU=serverAuth" \
      -validity "$DAYS" -storetype PKCS12 -keystore "$dir/keystore.p12" -storepass:file "$dir/keystore-password" \
      >/dev/null 2>&1 || { echo "$name: keytool could not create the key store" >&2; exit 1; }
    echo "$name: new demo token, key store and certificate created"
  fi
  keytool -exportcert -rfc -alias "$name" -keystore "$dir/keystore.p12" -storetype PKCS12 \
    -storepass:file "$dir/keystore-password" -file "$dir/cert.pem" >/dev/null 2>&1 \
    || { echo "$name: the certificate cannot be read from the key store" >&2; exit 1; }
  cp "$dir/token" "$CLIENT/$name.token"
  chown -R "$OWNER" "$dir"
  chmod 0750 "$dir"
  chmod 0440 "$dir/token" "$dir/keystore-password" "$dir/keystore.p12" "$dir/cert.pem"
}

make_sidecar pdf-validator
make_sidecar ocr

trust="$CLIENT/truststore.p12.new"
rm -f "$trust"
cp "$CACERTS" "$trust"
chmod 0600 "$trust"
for name in pdf-validator ocr; do
  keytool -importcert -noprompt -alias "sangam-$name" -file "/secrets/$name/cert.pem" -keystore "$trust" \
    -storepass changeit >/dev/null 2>&1 || { echo "$name: the certificate cannot be added to the trust store" >&2; exit 1; }
done
mv -f "$trust" "$CLIENT/truststore.p12"
chown -R "$OWNER" "$CLIENT"
chmod 0750 "$CLIENT"
chmod 0440 "$CLIENT/truststore.p12" "$CLIENT/pdf-validator.token" "$CLIENT/ocr.token"
echo "sidecar demo secrets ready"
