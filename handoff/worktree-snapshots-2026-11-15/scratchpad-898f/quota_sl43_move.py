"""Move my SL-43/SL-39 edits from the shared 'wt' copy into my own 'wt-quota' and restore 'wt' for its owner."""
import hashlib, os, shutil, sys, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
S = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(S, "quota_sl43_files.py"), encoding="utf-8").read().split("def h(")[0])
def h(p):
    return hashlib.sha1(open(p, "rb").read()).hexdigest() if os.path.exists(p) else None
GEN = [f"sangam-persistence/src/main/resources/db/changelog/{d}/sangam-0001-baseline.sql" for d in ("postgresql","oracle","mssql","mysql","h2")]
for rel in MODIFIED + CREATED + GEN:
    w, b, n = os.path.join(WT, rel), os.path.join(BASE, rel), os.path.join(NEW, rel)
    if h(w) is None or h(w) == h(b):
        continue
    os.makedirs(os.path.dirname(n), exist_ok=True)
    shutil.copy2(w, n)                     # my version into my copy (main == base for every file)
    if os.path.exists(b):
        shutil.copy2(b, w)                 # restore the owner's file
    else:
        os.remove(w)                       # a file I created
    print("moved", rel)
for d in glob.glob(os.path.join(WT, "*", "target-quota")):
    shutil.rmtree(d, ignore_errors=True)
    print("removed build output", d)
