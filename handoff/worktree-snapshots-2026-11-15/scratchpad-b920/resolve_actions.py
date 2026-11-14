"""Decision 134 lookup: resolve each workflow action ref to a commit SHA and the exact release tag it points at."""
import json
import re
import subprocess
import sys

REFS = {
    "actions/checkout": "v4", "actions/upload-artifact": "v4", "actions/setup-java": "v4",
    "actions/download-artifact": "v4", "actions/setup-python": "v5", "actions/setup-node": "v4",
    "docker/build-push-action": "v6", "github/codeql-action": "v3", "actions/setup-dotnet": "v4",
    "docker/setup-buildx-action": "v3", "azure/setup-helm": "v4", "actions/attest-build-provenance": "v2",
    "shivammathur/setup-php": "v2", "ossf/scorecard-action": "v2.5.0", "graalvm/setup-graalvm": "v1",
    "sigstore/cosign-installer": "v3", "gradle/actions": "v4", "dorny/paths-filter": "v3",
    "docker/login-action": "v3", "aws-actions/configure-aws-credentials": "v4",
    "aquasecurity/trivy-action": "v0.36.0", "actions/setup-go": "v5", "actions/cache": "v4",
    "docker/setup-qemu-action": "v3", "softprops/action-gh-release": "v2",
}

out = {}
for repo, ref in REFS.items():
    r = subprocess.run(["git", "ls-remote", "--tags", f"https://github.com/{repo}"], capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        out[repo] = {"ref": ref, "error": r.stderr.strip()}
        continue
    tags = {}
    for line in r.stdout.splitlines():
        sha, name = line.split("\t")
        name = name[len("refs/tags/"):]
        if name.endswith("^{}"):
            tags[name[:-3]] = sha  # peeled commit wins over the tag object
        else:
            tags.setdefault(name, sha)
    commit = tags.get(ref)
    exact = sorted((t for t, s in tags.items() if s == commit and re.fullmatch(r"v\d+\.\d+\.\d+", t)),
                   key=lambda t: tuple(int(x) for x in t[1:].split(".")))
    out[repo] = {"ref": ref, "commit": commit, "exact_tags": exact}
json.dump(out, sys.stdout, indent=1)
