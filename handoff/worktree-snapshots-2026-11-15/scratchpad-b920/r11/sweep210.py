"""Decision 210 groupId sweep: org.sangam -> io.github.sqamentor (Maven coordinates only; Java packages stay).

Mode 'apply' edits files in place (keeps each file's line endings) and prints a change list; mode 'scan' prints every
remaining Maven-coordinate use of org.sangam. Excludes handoff/ (snapshots) and spike/ (groupId org.sangam.spike,
reported separately)."""
import os, re, subprocess, sys

root, mode = sys.argv[1], sys.argv[2]
NEW = "io.github.sqamentor"
files = subprocess.run(["git", "ls-files", "-co", "--exclude-standard"], cwd=root, capture_output=True,
                       text=True, encoding="utf-8").stdout.splitlines()
files = [f for f in files if not f.startswith(("handoff/", "spike/")) and "/node_modules/" not in f]

GROUP_TAG = re.compile(r"<groupId>(\s*)org\.sangam(\s*)</groupId>")
COORD = re.compile(r"\borg\.sangam:(?=[a-z])")
PURL = re.compile(r"pkg:maven/org\.sangam/")

# explicit literal edits outside the regex families: (file, old, new)
LITERAL = [
    ("sangam/sangam-server/src/main/java/org/sangam/server/config/PluginWiring.java",
     'PlatformInfo.detect(cl, "org.sangam", ', f'PlatformInfo.detect(cl, "{NEW}", '),
    ("sangam/sangam-assembly/src/test/java/org/sangam/assembly/AssemblyIsPlainJavaTest.java",
     'assertTrue(!IN_POM.matcher("<groupId>org.sangam</groupId>").find());',
     f'assertTrue(!IN_POM.matcher("<groupId>{NEW}</groupId>").find());'),
    ("sangam/deploy/licence/test_release_assets.py",
     'ME = {"type": "library", "group": "org.sangam",', f'ME = {{"type": "library", "group": "{NEW}",'),
    ("sangam/deploy/licence/test_release_assets.py",
     'self.assertNotIn("org.sangam", third)', f'self.assertNotIn("{NEW}", third)'),
    ("sangam/deploy/licence/notice_bundle.py",
     '# Decision 210: the Maven groupId becomes io.github.sqamentor (integrator sweep); org.sangam stays until it lands.\n'
     'FIRST_PARTY = ("pkg:maven/org.sangam/", "pkg:maven/io.github.sqamentor/", "pkg:npm/%40sangam/")',
     '# Decision 210: the Maven groupId is io.github.sqamentor (sweep done); a component still under org.sangam is\n'
     '# third-party to this check, so a missed rename shows up in THIRD-PARTY.txt instead of being hidden.\n'
     'FIRST_PARTY = ("pkg:maven/io.github.sqamentor/", "pkg:npm/%40sangam/")'),
    ("sangam/deploy/licence/register_check.py",
     '# Decision 210: the Maven groupId becomes io.github.sqamentor (integrator sweep); org.sangam stays until it lands.\n'
     'FIRST_PARTY = ("pkg:maven/org.sangam/", "pkg:maven/io.github.sqamentor/", "pkg:npm/%40sangam/")',
     '# Decision 210: the Maven groupId is io.github.sqamentor (sweep done); an org.sangam component is not first-party.\n'
     'FIRST_PARTY = ("pkg:maven/io.github.sqamentor/", "pkg:npm/%40sangam/")'),
    ("sangam/deploy/native/sdk-worker/generate_native_config.py",
     '"native-image", "org.sangam",', f'"native-image", "{NEW}",'),
    ("sangam/deploy/native/sdk-worker/generate_native_config.py",
     "META-INF/native-image/org.sangam/", f"META-INF/native-image/{NEW}/"),
]
# files where org.sangam coordinates are deliberate negative cases (decision 210 refuses them)
KEEP = {"sangam/deploy/licence/test_central_bundle.py"}

changed = []
for f in files:
    p = os.path.join(root, f)
    if not os.path.isfile(p):
        continue
    try:
        raw = open(p, "rb").read()
        t = raw.decode("utf-8")
    except (OSError, UnicodeDecodeError):
        continue
    if mode == "scan":
        if f in KEEP:
            continue
        for n, line in enumerate(t.splitlines(), 1):
            if GROUP_TAG.search(line) or COORD.search(line) or PURL.search(line):
                print(f"LEFT {f}:{n}: {line.strip()[:160]}")
        continue
    new = t
    for lf, old, rep in LITERAL:  # literal edits first, before the regex families touch the same lines
        if lf == f:
            o = old.replace("\n", "\r\n") if "\r\n" in new else old
            r = rep.replace("\n", "\r\n") if "\r\n" in new else rep
            assert new.count(o) == 1, (f, old[:60], new.count(o))
            new = new.replace(o, r)
    if f not in KEEP:
        new = GROUP_TAG.sub(lambda m: f"<groupId>{m.group(1)}{NEW}{m.group(2)}</groupId>", new)
        new = COORD.sub(f"{NEW}:", new)
        new = PURL.sub(f"pkg:maven/{NEW}/", new)
    if new != t:
        open(p, "wb").write(new.encode("utf-8"))
        changed.append(f)
for f in changed:
    print("CHANGED", f)
print("changed files:", len(changed))
