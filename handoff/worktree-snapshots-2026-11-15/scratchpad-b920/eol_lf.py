"""Rewrites CRLF->LF in working-copy files that git marks eol=lf, only when content-neutral (same git hash-object)."""
import subprocess, sys

out = subprocess.run(["git", "ls-files", "--eol"], capture_output=True, text=True, check=True).stdout
paths = [l.split("\t", 1)[1] for l in out.splitlines() if "w/crlf" in l.split("\t")[0] and "eol=lf" in l.split("\t")[0]]
done, refused = 0, []
for p in paths:
    h = lambda: subprocess.run(["git", "hash-object", "--", p], capture_output=True, text=True, check=True).stdout.strip()
    before = h()
    with open(p, "rb") as f:
        data = f.read()
    new = data.replace(b"\r\n", b"\n")
    if b"\r" in new:
        refused.append((p, "lone CR"))
        continue
    with open(p, "wb") as f:
        f.write(new)
    after = h()
    if after != before:
        with open(p, "wb") as f:
            f.write(data)
        refused.append((p, f"hash changed {before} -> {after}; restored"))
        continue
    done += 1
print(f"converted {done} of {len(paths)}")
for r in refused:
    print("REFUSED", *r)
sys.exit(1 if refused else 0)
