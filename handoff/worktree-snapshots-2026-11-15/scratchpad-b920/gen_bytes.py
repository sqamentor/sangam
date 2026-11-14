"""Byte check: each generator's output equals the working copy (run from the repo root)."""
import filecmp, glob, hashlib, os, subprocess, sys, tempfile

S = "sangam"
def h(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()

inplace = {
    "generate_changelogs": ([sys.executable, "-I", f"{S}/sangam-persistence/tools/generate_changelogs.py"],
                            glob.glob(f"{S}/sangam-persistence/src/main/resources/db/changelog/*/sangam-0001-baseline.sql")),
    "generate_scale_changelogs": ([sys.executable, "-I", f"{S}/sangam-control-plane/tools/generate_scale_changelogs.py"],
                                  glob.glob(f"{S}/sangam-control-plane/src/main/resources/db/changelog/controlplane/**/*.*", recursive=True)
                                  + glob.glob(f"{S}/sangam-rollup/src/main/resources/db/changelog/rollup/**/*.*", recursive=True)),
    "generate_realm": ([sys.executable, "-I", f"{S}/deploy/compose/keycloak/generate_realm.py"],
                       glob.glob(f"{S}/deploy/compose/keycloak/realm/*.json")
                       + [f"{S}/deploy/compose/observability/grafana/dashboards/sangam-overview.json"]),
}
rc = 0
for name, (cmd, files) in inplace.items():
    before = {f: h(f) for f in files}
    saved = {f: open(f, "rb").read() for f in files}
    r = subprocess.run(cmd, capture_output=True, text=True)
    changed = [f for f in files if h(f) != before[f]]
    for f in changed:  # never leave a regenerated file behind: other agents own these inputs
        open(f, "wb").write(saved[f])
    print(f"{name}: exit {r.returncode}, {len(files)} files, byte-changed {len(changed)} {changed[:5]}")
    rc |= r.returncode or bool(changed)
tmp = tempfile.mkdtemp()
out = os.path.join(tmp, "sangam-api.yaml")
r = subprocess.run([sys.executable, "-I", f"{S}/sangam-rest/api/gen_openapi.py", out], capture_output=True, text=True)
same = r.returncode == 0 and filecmp.cmp(out, f"{S}/sangam-rest/api/sangam-api.yaml", shallow=False)
print(f"gen_openapi: exit {r.returncode}, byte-identical {same}", r.stderr[-300:])
rc |= not same
for cmd in ([sys.executable, "-I", f"{S}/deploy/native/sdk-worker/generate_native_config.py", "--check"],
            [sys.executable, "-I", f"{S}/sangam-packs/tools/packgen/main.py", "--check"]):
    r = subprocess.run(cmd, capture_output=True, text=True)
    print(os.path.basename(cmd[2]), "--check exit", r.returncode, (r.stdout.strip().splitlines() or [""])[-1][:200])
    rc |= r.returncode
sys.exit(1 if rc else 0)
