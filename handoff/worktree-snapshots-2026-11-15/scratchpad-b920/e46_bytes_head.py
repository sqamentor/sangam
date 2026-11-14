"""Write the frozen E-46 fixtures (exact git blob bytes) into a target tree and verify their SHA-256.

Usage: python e46_bytes.py <REPO_ROOT> <TARGET_SANGAM_DIR> [--write]
Reads each blob from HEAD of REPO_ROOT (read-only, git show HEAD:<path>), compares its SHA-256 with the frozen kit hash that
E46ConformanceIT asserts, and reports the hash of the file currently in TARGET. With --write, writes the blob bytes.
"""
import hashlib
import os
import subprocess
import sys

REPO, TARGET = sys.argv[1], sys.argv[2]
WRITE = '--write' in sys.argv
DIR = 'sangam-server/src/test/resources/acceptance/e46'
SHA256 = {
    'e46-wf1.bpmn': '167df15c261cac014df6692213422d34800ee391fd00d401d5b4d0533c730395',
    'e46-wf2.bpmn': 'fd838300cc2fd07b699be4840b28e6885af401abdbc21bbea53ca5ae1637639b',
    'e46-wf3.bpmn': 'f0faf33fc5d943f62272640030732bf7801970bbd9b15cec5b2d8f76fcedcd3c',
    'e46-wf4.bpmn': '30abead9e70dd73845b51a6d017a789235390405a2c51c8620807413560b8ab7',
    'e46-wf5.bpmn': 'b4572c2df517f78ba4a5f8c97400ec0027e4ab4ec2f82801e78546ecc5e59589',
    'e46-wf6.bpmn': 'ff7135baf9297dcb7347965696fd3364efbf29123302cc7ae6d570a6ed542f1d',
    'e46-wf7.bpmn': '46d45aea9d1f280de36e6aaa09a17396da2bde7a7ad6b23a1be9f58706b8aa48',
    'e46-wf8-child.bpmn': '3926b185e771e5e004327fc691e091d0d3ada0a0ade35d3c0afa0c4fa64cf6e7',
    'e46-wf8.bpmn': '729e0bb1cf77fb841ce47bfe8039940dd3a3a87f4394deeba453b6a03a05db83',
    'e46-wf9.bpmn': '088e9144394c42312b57b56dc4d4370c8e618e5df5d98278ccef12ef8330d24f',
}
bad = 0
for name, want in SHA256.items():
    rel = f'{DIR}/{name}.model'
    blob = subprocess.run(['git', '-C', REPO, 'show', f'HEAD:sangam/{rel}'], capture_output=True, check=True).stdout
    got = hashlib.sha256(blob).hexdigest()
    path = os.path.join(TARGET, *rel.split('/'))
    with open(path, 'rb') as fh:
        cur = hashlib.sha256(fh.read()).hexdigest()
    ok = got == want
    bad += 0 if ok else 1
    print(f'{name}: index-blob {"OK" if ok else "MISMATCH"}; working file {"OK" if cur == want else "differs"}')
    if WRITE and ok and cur != want:
        with open(path, 'wb') as fh:
            fh.write(blob)
        print(f'  wrote {len(blob)} bytes')
sys.exit(1 if bad else 0)
