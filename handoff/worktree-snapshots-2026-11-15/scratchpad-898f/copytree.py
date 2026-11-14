import os, shutil, sys
src, dst = sys.argv[1], sys.argv[2]
def skip(rel, name, parent):
    if name.startswith('target-'): return True
    if name == 'target' and os.path.exists(os.path.join(parent,'pom.xml')): return True
    if name in ('node_modules','.git','dist'): return True
    return False
for root, dirs, files in os.walk(src):
    dirs[:] = [d for d in dirs if not skip(None,d,root)]
    rel = os.path.relpath(root, src)
    os.makedirs(os.path.join(dst, rel), exist_ok=True)
    for f in files:
        shutil.copy2(os.path.join(root,f), os.path.join(dst,rel,f))
