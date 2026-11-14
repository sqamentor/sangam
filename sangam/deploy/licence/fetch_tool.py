# SPDX-License-Identifier: Apache-2.0
"""Verified install of a pinned CI tool (CR-11, decision 179): replaces `curl ... | sh`.

Every tool in ci-tool-pins.json carries a "signature" record:
  cosign-keyless  the vendor signs its checksum file with a keyless (Fulcio) certificate: checksums_url,
                  certificate_url, signature_url, identity, issuer
  cosign-bundle   the same, packaged as one Sigstore bundle (protobuf JSON): checksums_url, bundle_url, identity, issuer
  none            the vendor publishes no signature for that release: reason (checksum pin only, stated in the log)
For a signed tool the order is: fetch the checksum file and its signature material; `cosign verify-blob` with the exact
certificate identity and OIDC issuer (cosign comes from sigstore/cosign-installer, pinned in the workflow); the
verified checksum file must list the asset with the committed SHA-256; only then is the asset fetched, its SHA-256
compared with the pin, and the tool binary extracted into --bin. So a signature failure, a checksum file that
disagrees with the pin, a hash mismatch, an asset without that binary, or an archive member that would land outside
--bin fails (exit 1); no network, no cosign on PATH, or an unknown tool is BLOCKED (exit 2).

  python3 -I sangam/deploy/licence/fetch_tool.py syft --bin "$RUNNER_TEMP/bin"
Standard library only.
"""
import argparse
import hashlib
import json
import os
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

SIGNED_KINDS = ("cosign-keyless", "cosign-bundle")
REQUIRED = {
    "cosign-keyless": ("checksums_url", "certificate_url", "signature_url", "identity", "issuer"),
    "cosign-bundle": ("checksums_url", "bundle_url", "identity", "issuer"),
    "none": ("reason",),
}


class Blocked(Exception):
    """The check could not run (no cosign, no network): BLOCKED, never a pass."""


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def extract_binary(archive, binary, bin_dir):
    with tarfile.open(archive, "r:gz") as tar:
        members = [m for m in tar.getmembers() if m.isfile() and os.path.basename(m.name) == binary]
        if len(members) != 1:
            raise ValueError(f"{archive}: expected one file named {binary}, found {len(members)}")
        src = tar.extractfile(members[0])
        target = os.path.join(bin_dir, binary)
        with open(target, "wb") as out:
            out.write(src.read())
    os.chmod(target, os.stat(target).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return target


def signature_record(tool, t):
    sig = t.get("signature")
    if not isinstance(sig, dict) or sig.get("kind") not in REQUIRED:
        raise ValueError(f"{tool}: ci-tool-pins.json has no valid signature record (kinds: {', '.join(REQUIRED)})")
    missing = [k for k in REQUIRED[sig["kind"]] if not sig.get(k)]
    if missing:
        raise ValueError(f"{tool}: signature record {sig['kind']} lacks {', '.join(missing)}")
    return sig


def cosign_args(sig, checksums, material):
    args = ["cosign", "verify-blob", checksums]
    if sig["kind"] == "cosign-keyless":
        args += ["--certificate", material["certificate"], "--signature", material["signature"]]
    else:
        # cosign v2 reads a protobuf Sigstore bundle only with --new-bundle-format (cosign_verify-blob.md, v2.5.2)
        args += ["--bundle", material["bundle"], "--new-bundle-format"]
    return args + ["--certificate-identity", sig["identity"], "--certificate-oidc-issuer", sig["issuer"]]


def listed_sha256(checksums, asset):
    """SHA-256 the checksum file lists for the asset name (sha256sum format: '<hex>  <name>' or '<hex> *<name>')."""
    found = []
    with open(checksums, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2 and parts[1].lstrip("*") == asset:
                found.append(parts[0].lower())
    if len(found) != 1:
        raise ValueError(f"the verified checksum file lists {asset} {len(found)} times (expected once)")
    return found[0]


def verify_signed_checksums(tool, t, sig, work, fetch, run):
    checksums = os.path.join(work, "checksums")
    fetch(sig["checksums_url"], checksums)
    material = {}
    for key in ("certificate", "signature", "bundle"):
        url = sig.get(f"{key}_url")
        if url:
            material[key] = os.path.join(work, key)
            fetch(url, material[key])
    try:
        r = run(cosign_args(sig, checksums, material), capture_output=True, text=True)
    except FileNotFoundError as e:
        raise Blocked(f"{tool}: cosign is not on PATH (sigstore/cosign-installer must run first): {e}") from e
    if r.returncode != 0:
        detail = (r.stderr or r.stdout or "").strip().splitlines()
        raise ValueError(f"{tool} {t['version']}: cosign verify-blob of the vendor checksum file failed "
                         f"(identity {sig['identity']}): {detail[-1] if detail else 'exit ' + str(r.returncode)}")
    listed = listed_sha256(checksums, os.path.basename(t["url"]))
    if listed != t["sha256"]:
        raise ValueError(f"{tool} {t['version']}: the signed checksum file lists {listed}, the pin is {t['sha256']}")
    return f"vendor signature verified (cosign, {sig['identity']})"


def install(tool, pins, bin_dir, fetch=urllib.request.urlretrieve, run=subprocess.run):
    """Returns (binary path, signature status text)."""
    t = pins["tools"].get(tool)
    if t is None:
        raise KeyError(f"{tool} is not in ci-tool-pins.json")
    sig = signature_record(tool, t)
    os.makedirs(bin_dir, exist_ok=True)
    with tempfile.TemporaryDirectory() as d:
        if sig["kind"] in SIGNED_KINDS:
            status = verify_signed_checksums(tool, t, sig, d, fetch, run)
        else:
            status = f"NO vendor signature published ({sig['reason']}); SHA-256 pin only"
        archive = os.path.join(d, os.path.basename(t["url"]))
        fetch(t["url"], archive)
        got = sha256_of(archive)
        if got != t["sha256"]:
            raise ValueError(f"{tool} {t['version']}: SHA-256 {got} does not match the pinned {t['sha256']}")
        return extract_binary(archive, t["binary"], bin_dir), status


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tool")
    ap.add_argument("--bin", required=True)
    ap.add_argument("--pins", default=os.path.join(here, "ci-tool-pins.json"))
    a = ap.parse_args(argv)
    try:
        with open(a.pins, encoding="utf-8") as f:
            pins = json.load(f)
        path, status = install(a.tool, pins, a.bin)
    except (KeyError, OSError, Blocked) as e:
        print(f"::error title=fetch_tool BLOCKED::{e}")
        return 2
    except (ValueError, tarfile.TarError) as e:
        print(f"::error title=fetch_tool {a.tool}::{e}")
        return 1
    t = pins["tools"][a.tool]
    if t["signature"]["kind"] == "none":
        print(f"::notice title=fetch_tool {a.tool}::{status}")
    print(f"{a.tool} {t['version']} verified (SHA-256 {t['sha256']}; {status}) -> {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
