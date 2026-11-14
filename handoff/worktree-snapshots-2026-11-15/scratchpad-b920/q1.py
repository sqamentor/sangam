import os, re
p = os.path.join(_BK_SANGAM, "sangam-resources/src/main/java/org/sangam/resources/ResourcesContext.java")
ms = _bk_methods(p, None, "event")
_, m, _ = _bk_load(p)
for x in ms:
    a, b = x["body"]
    print(x["owner"], x["params"])
    for t in re.finditer(r"(?<![\w.])p\b", m[a:b]):
        pos = a + t.start()
        print(_bk_line(p, pos), repr(m[pos-30:pos+10]), _bk_enclosing_call(m, a, b, pos))
print(repr(m[pos-120:pos+5]))
