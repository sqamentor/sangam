import os, sys
root, lf_list, crlf_list = sys.argv[1:4]
def paths(p):
    return [l.strip() for l in open(p, encoding="utf-8-sig") if l.strip()]
n = 0
for rel in paths(lf_list):
    p = os.path.join(root, rel)
    b = open(p, "rb").read()
    nb = b.replace(b"\r\n", b"\n")
    assert b"\r" not in nb, f"lone CR left in {rel}"
    if nb != b:
        open(p, "wb").write(nb); n += 1
m = 0
for rel in paths(crlf_list):
    p = os.path.join(root, rel)
    b = open(p, "rb").read()
    nb = b.replace(b"\r\n", b"\n")
    assert b"\r" not in nb, f"lone CR in {rel}"
    nb = nb.replace(b"\n", b"\r\n")
    if nb != b:
        open(p, "wb").write(nb); m += 1
print("to LF", n, "to CRLF", m)
