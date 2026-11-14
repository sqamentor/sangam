"""SHA-256 of every file under a directory, one line each, sorted (for no-diff proofs of a generator run)."""
import hashlib
import os
import sys

root = sys.argv[1]
for d, _, fs in sorted(os.walk(root)):
    for f in sorted(fs):
        p = os.path.join(d, f)
        print(hashlib.sha256(open(p, 'rb').read()).hexdigest(), os.path.relpath(p, root).replace('\\', '/'))
