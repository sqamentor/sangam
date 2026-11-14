import difflib, sys, pathlib
src = pathlib.Path(sys.argv[1]); wt = pathlib.Path(sys.argv[2]); out = pathlib.Path(sys.argv[3])
files = sys.argv[4:]
buf = []
for f in files:
    a = src / f; b = wt / f
    al = a.read_text(encoding='utf-8').replace('\r\n', '\n').splitlines(keepends=True) if a.exists() else []
    bl = b.read_text(encoding='utf-8').replace('\r\n', '\n').splitlines(keepends=True) if b.exists() else []
    d = list(difflib.unified_diff(al, bl, fromfile='a/sangam/' + f.replace('\\', '/'), tofile='b/sangam/' + f.replace('\\', '/')))
    buf.extend(d)
    added = sum(1 for l in d if l.startswith('+') and not l.startswith('+++'))
    removed = sum(1 for l in d if l.startswith('-') and not l.startswith('---'))
    print(f"{f}: +{added} -{removed}{' (new)' if not a.exists() else ''}")
out.write_text(''.join(buf), encoding='utf-8')
