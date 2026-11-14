"""Re-sync wt-quota from the main tree while keeping my SL-43/SL-39 files (stash -> mirror -> restore)."""
import hashlib, os, shutil, subprocess
S = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(S, "quota_sl43_files.py"), encoding="utf-8").read().split("def h(")[0])
GEN = [f"sangam-persistence/src/main/resources/db/changelog/{d}/sangam-0001-baseline.sql" for d in ("postgresql","oracle","mssql","mysql","h2")]
MINE = MODIFIED + CREATED + GEN + ["TESTING-GAP-REGISTER.md"]
def h(p): return hashlib.sha1(open(p,"rb").read()).hexdigest() if os.path.exists(p) else None
stash = os.path.join(S, "wt-quota-stash")
shutil.rmtree(stash, ignore_errors=True)
for rel in MINE:
    src = os.path.join(NEW, rel)
    if os.path.exists(src):
        os.makedirs(os.path.dirname(os.path.join(stash, rel)), exist_ok=True); shutil.copy2(src, os.path.join(stash, rel))
subprocess.run(["robocopy", MAIN, NEW, "/E", "/XD", "target", "target-quota", "node_modules", ".git", "__pycache__", "/NFL", "/NDL", "/NJH", "/NJS", "/NP", "/MT:16"])
for rel in MINE:
    st = os.path.join(stash, rel)
    if not os.path.exists(st): continue
    m, b = h(os.path.join(MAIN, rel)), h(os.path.join(BASE, rel))
    if rel not in CREATED and rel not in GEN and m != b:
        print("MAIN CHANGED since baseline (merge needed):", rel)
    shutil.copy2(st, os.path.join(NEW, rel))
print("resynced")
