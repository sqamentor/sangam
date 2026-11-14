d = r"D:\GITHUB  Project\Custom WorkFlow\sangam\sangam-server\src\test\java\org\sangam\server\acceptance\gov" + "\\"
VB = "SANGAM.md version-binding rule (deployment-scoped vs latest; product validator id D-12; no SANGAM.md decision id)"
reps = {
 "E16aPatternsIT.java": [
  ("the FC-23 tagged duration calendar reads", "the tagged duration calendar (SANGAM.md R31) reads"),
  ("/* Sangam D-12: binding must be declared */", "/* " + VB + ": binding must be declared */"),
  ("// Sangam replacement (FC-23 / D-11): instead", "// Sangam replacement (SANGAM.md R31): instead"),
  ('"Sangam FC-23 tagged duration:', '"Sangam R31 tagged duration:'),
  ('no large multi-instance", D-23): the', 'no large multi-instance", SANGAM.md X.0d pending amendment (4) GP-09 rule): the'),
  ('"Sangam MultiInstanceSizeGuard (D-23)"', '"Sangam MultiInstanceSizeGuard (SANGAM.md X.0d pending amendment (4) GP-09 rule)"'),
  ("// Sangam replacement (D-12): the spike's", "// Sangam replacement (" + VB + "): the spike's"),
 ],
 "E17aDomainsIT.java": [("/* Sangam D-12: binding must be declared */", "/* " + VB + ": binding must be declared */")],
}
for f, rs in reps.items():
    s = open(d + f, encoding="utf-8").read()
    for a, b in rs:
        assert a in s, (f, a)
        s = s.replace(a, b)
    open(d + f, "w", encoding="utf-8").write(s)
print("ok")
