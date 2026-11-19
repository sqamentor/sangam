#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Key custodian tool (owner decisions 387 (4) and 390 (4)): signs the attestation that a Sangam environment's keys
# belong to that environment, in the format the server checks (org.sangam.server.config.KeyAttestation,
# "sangam-key-attestation/1"). Needs only POSIX sh, awk and OpenSSL 3 (Ed25519 with -rawin); no JDK, no build.
#
#   sangam-key-attestation.sh generate-custodian-key <private-key.pem>
#       Creates the custodian's Ed25519 private key (mode 0600, refused when the file exists) and prints the PUBLIC
#       key, base64 X.509, the value of SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY. The private key is never printed.
#   sangam-key-attestation.sh public-key <private-key.pem>
#       Prints the public key of an existing custodian key (base64 X.509).
#   sangam-key-attestation.sh sign <private-key.pem> <environment> <fingerprint-export> [<attestation.json>]
#       Reads the server's fingerprint export (sangam.keys.fingerprint-export: lines
#       "<environment> <fingerprint> <setting> <purpose>") and writes the signed attestation JSON (to the file, or to
#       standard output). Every line must be of <environment> and carry its purpose; anything else is refused.
#   sangam-key-attestation.sh verify <custodian-public-key-base64> <attestation.json>
#       Checks the signature as the server does and prints the attested environment and key count.
#
# Nothing here prints key material: only the public key, fingerprints never leave the files they are in.
set -eu
umask 077

FORMAT='sangam-key-attestation/1'

die() {
    echo "sangam-key-attestation: $*" >&2
    exit 1
}

need_openssl() {
    command -v openssl >/dev/null 2>&1 || die "openssl 3 is required"
    v=$(openssl version 2>/dev/null | awk '{print $2}')
    case "$v" in
        3.*) ;;
        *) die "openssl 3 is required (found ${v:-none}); Ed25519 signing needs -rawin" ;;
    esac
}

work=''
cleanup() {
    if [ -n "$work" ] && [ -d "$work" ]; then
        rm -rf "$work"
    fi
}
trap cleanup EXIT HUP INT TERM

mkwork() {
    work=$(mktemp -d "${TMPDIR:-/tmp}/sangam-attest.XXXXXX") || die "cannot create a temporary directory"
}

public_key() {
    openssl pkey -in "$1" -pubout -outform DER 2>/dev/null | openssl base64 -A \
        || die "$1 is not a readable Ed25519 private key"
    echo
}

cmd_generate() {
    [ $# -eq 1 ] || die "usage: generate-custodian-key <private-key.pem>"
    [ ! -e "$1" ] || die "$1 exists; a custodian key is never overwritten"
    openssl genpkey -algorithm ed25519 -out "$1" 2>/dev/null || die "cannot create $1"
    chmod 600 "$1"
    public_key "$1"
}

cmd_public() {
    [ $# -eq 1 ] || die "usage: public-key <private-key.pem>"
    [ -r "$1" ] || die "$1 cannot be read"
    public_key "$1"
}

cmd_sign() {
    [ $# -eq 3 ] || [ $# -eq 4 ] || die "usage: sign <private-key.pem> <environment> <fingerprint-export> [<out>]"
    key=$1 env=$2 export=$3 out=${4:-}
    [ -r "$key" ] || die "$key cannot be read"
    [ -r "$export" ] || die "$export cannot be read"
    echo "$env" | grep -Eq '^[a-z0-9][a-z0-9._-]{0,47}$' || die "environment must match [a-z0-9][a-z0-9._-]{0,47}"
    mkwork
    # The statement: {"environment":..,"issued_at":..,"keys":[{"fingerprint":..,"purpose":..},..]}, one key per
    # distinct (fingerprint, purpose), sorted. Purposes are the server's plain names; anything else is refused, so no
    # JSON escaping is ever needed.
    awk -v env="$env" '
        { sub(/\r$/, "") }
        /^[[:space:]]*(#|$)/ { next }
        {
            if ($1 != env) { printf "line %d is of environment %s, not %s\n", NR, $1, env > "/dev/stderr"; bad = 1; exit }
            if ($2 !~ /^[0-9a-f]+$/ || length($2) != 64) { printf "line %d: not a 64 lower-case hex fingerprint\n", NR > "/dev/stderr"; bad = 1; exit }
            if (NF < 4) { printf "line %d has no purpose (an export of an older server); start the server again to rewrite it\n", NR > "/dev/stderr"; bad = 1; exit }
            p = $4; for (i = 5; i <= NF; i++) p = p " " $i
            if (p !~ /^[a-z0-9][a-z0-9 ._()-]*$/) { printf "line %d: purpose is not a plain name\n", NR > "/dev/stderr"; bad = 1; exit }
            print $2 "\t" p
        }
        END { if (bad) exit 1 }' "$export" > "$work/lines" \
        || die "$export is not a Sangam fingerprint export"
    LC_ALL=C sort -u "$work/lines" > "$work/keys"
    [ -s "$work/keys" ] || die "$export names no key"
    issued=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    {
        printf '{"environment":"%s","issued_at":"%s","keys":[' "$env" "$issued"
        awk -F '\t' '{ printf "%s{\"fingerprint\":\"%s\",\"purpose\":\"%s\"}", (NR > 1 ? "," : ""), $1, $2 }' "$work/keys"
        printf ']}'
    } > "$work/statement"
    openssl pkeyutl -sign -inkey "$key" -rawin -in "$work/statement" -out "$work/signature" 2>/dev/null \
        || die "signing failed (is $key an Ed25519 private key?)"
    stmt=$(openssl base64 -A -in "$work/statement")
    sig=$(openssl base64 -A -in "$work/signature")
    doc=$(printf '{"format":"%s","statement":"%s","signature":"%s"}' "$FORMAT" "$stmt" "$sig")
    if [ -n "$out" ]; then
        printf '%s\n' "$doc" > "$out.tmp.$$" && chmod 644 "$out.tmp.$$" && mv -f "$out.tmp.$$" "$out" \
            || die "cannot write $out"
        echo "attestation for environment $env, $(wc -l < "$work/keys" | tr -d ' ') key(s): $out" >&2
    else
        printf '%s\n' "$doc"
    fi
}

field() {
    # the value of a top-level string field of the attestation JSON (base64 or the format name: no escapes)
    tr -d '\r\n' < "$2" | sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}

cmd_verify() {
    [ $# -eq 2 ] || die "usage: verify <custodian-public-key-base64> <attestation.json>"
    [ -r "$2" ] || die "$2 cannot be read"
    mkwork
    [ "$(field format "$2")" = "$FORMAT" ] || die "$2 is not a $FORMAT document"
    printf '%s' "$1" | openssl base64 -d -A > "$work/pub.der" 2>/dev/null || die "the public key is not base64"
    field statement "$2" | openssl base64 -d -A > "$work/statement" 2>/dev/null || die "the statement is not base64"
    field signature "$2" | openssl base64 -d -A > "$work/signature" 2>/dev/null || die "the signature is not base64"
    openssl pkeyutl -verify -pubin -keyform DER -inkey "$work/pub.der" -rawin -in "$work/statement" \
        -sigfile "$work/signature" >/dev/null 2>&1 || die "the signature does not verify against this public key"
    env=$(sed -n 's/.*"environment":"\([^"]*\)".*/\1/p' "$work/statement")
    n=$(grep -o '"fingerprint"' "$work/statement" | wc -l | tr -d ' ')
    echo "verified: environment $env, $n key(s)"
}

[ $# -ge 1 ] || die "usage: generate-custodian-key | public-key | sign | verify (see the header of this script)"
need_openssl
sub=$1
shift
case "$sub" in
    generate-custodian-key) cmd_generate "$@" ;;
    public-key) cmd_public "$@" ;;
    sign) cmd_sign "$@" ;;
    verify) cmd_verify "$@" ;;
    *) die "unknown command $sub" ;;
esac
