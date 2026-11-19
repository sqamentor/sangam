#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Host-side key generation for Sangam on demo.ifms.dev (EV-M-0890). Writes, under $SANGAM_SECRETS_DIR:
#   sangam-keys.env                    env-only keys (second --env-file of docker compose):
#                                        SANGAM_SECURITY_KEK_KEYS_K1        base64, 32 random bytes
#                                        SANGAM_REGISTRY_SIGNER_PRIVATEKEY  Ed25519, PKCS#8 DER, base64
#                                        SANGAM_REGISTRY_SIGNER_PUBLICKEY   Ed25519, X.509 DER, base64
#                                        SANGAM_REGISTRY_VAULT_HMACPEPPER   base64, 32 random bytes
#   files/compliance/principal-key     base64, 32 random bytes            (secret://compliance/principal-key)
#   files/audit/checkpoint-private     Ed25519 PKCS#8 DER, base64          (secret://audit/checkpoint-private)
#   files/audit/checkpoint-public      Ed25519 X.509 DER, base64           (secret://audit/checkpoint-public)
# The secret files hold the exact bytes, no trailing newline (SL-65), as ProductionStartupFailFastTest writes them.
# Idempotent: an existing file is never overwritten (a re-run keeps every key; rotate by hand only). Prints only file
# names and "kept"/"created", never key material. Needs: sh, openssl (1.1.1 or later), mkdir, chmod, mv, rm, cat.
# The container reads files/ as the image's non-root user: chown the tree to that uid if 0600 is not readable by it.
set -eu
umask 077
for tool in openssl cat mv rm chmod mkdir; do
  command -v "$tool" >/dev/null 2>&1 || { echo "STOP: $tool not found on PATH" >&2; exit 1; }
done
D="${SANGAM_SECRETS_DIR:?set SANGAM_SECRETS_DIR to the host directory for Sangam's demo keys}"
mkdir -p "$D/files/compliance" "$D/files/audit"
chmod 700 "$D" "$D/files" "$D/files/compliance" "$D/files/audit"

# openssl's own base64 (one line, -A): no coreutils base64/tr needed
b64() { openssl base64 -A; }
rand32() { openssl rand 32 | b64; }

# writes stdin to $1 (no trailing newline) unless $1 exists
put() {
  if [ -e "$1" ]; then cat >/dev/null; echo "kept    $1"; else
    tmp="$1.tmp.$$"; cat >"$tmp"; chmod 600 "$tmp"
    # a failed generator in a pipe leaves an empty file: never keep one (fail closed)
    [ -s "$tmp" ] || { rm -f "$tmp"; echo "STOP: generating $1 produced nothing" >&2; exit 1; }
    mv "$tmp" "$1"; echo "created $1"; fi
}

# an Ed25519 pair: $1 private file, $2 public file (both written together, or both kept)
ed_pair() {
  if [ -e "$1" ] && [ -e "$2" ]; then echo "kept    $1"; echo "kept    $2"; return; fi
  [ ! -e "$1" ] && [ ! -e "$2" ] || { echo "STOP: only one of $1 / $2 exists; restore or remove it by hand" >&2; exit 1; }
  der="$D/.ed.der.$$"
  openssl genpkey -algorithm ed25519 -outform DER -out "$der"
  b64 <"$der" | put "$1"
  openssl pkey -inform DER -in "$der" -pubout -outform DER | b64 | put "$2"
  rm -f "$der"
}

rand32 | put "$D/files/compliance/principal-key"
ed_pair "$D/files/audit/checkpoint-private" "$D/files/audit/checkpoint-public"

ENV="$D/sangam-keys.env"
if [ -e "$ENV" ]; then echo "kept    $ENV"; else
  k="$D/.k.$$"; mkdir "$k"
  ed_pair_tmp() { openssl genpkey -algorithm ed25519 -outform DER -out "$k/s.der"; }
  ed_pair_tmp
  {
    printf 'SANGAM_SECURITY_KEK_KEYS_K1=%s\n' "$(rand32)"
    printf 'SANGAM_REGISTRY_SIGNER_PRIVATEKEY=%s\n' "$(b64 <"$k/s.der")"
    printf 'SANGAM_REGISTRY_SIGNER_PUBLICKEY=%s\n' "$(openssl pkey -inform DER -in "$k/s.der" -pubout -outform DER | b64)"
    printf 'SANGAM_REGISTRY_VAULT_HMACPEPPER=%s\n' "$(rand32)"
  } | put "$ENV"
  rm -rf "$k"
fi
echo "done: keys under $D (not printed). Keep $D out of backups that leave the host, or encrypt them."
