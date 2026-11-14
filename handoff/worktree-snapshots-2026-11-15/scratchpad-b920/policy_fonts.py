import io, json
p = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\deploy\licence\licence-policy.json"
s = io.open(p, encoding="utf-8", newline="").read()
old = '"components": ["pkg:generic/noto-sans-devanagari", "pkg:github/notofonts/devanagari"],'
if old in s:
    s = s.replace(old, '"components": ["pkg:generic/noto-sans-devanagari", "pkg:github/notofonts/devanagari", "pkg:generic/noto-sans", "pkg:github/notofonts/latin-greek-cyrillic"],')
    s = s.replace('Any other OFL font needs a new owner decision."', 'Widened by owner decision 95 to Noto Sans for Latin text; files and hashes in deploy/fonts/README.md. Any other OFL font needs a new owner decision."', 1)
    json.loads(s)
    io.open(p, "w", encoding="utf-8", newline="").write(s)
    print("policy updated")
else:
    print("already updated" if "latin-greek-cyrillic" in s else "ANCHOR MISSING")
