import sys
f=sys.argv[1]; c=open(f,encoding='utf-8').read()
R=[('''        assertEquals(1, own.size());
        assertEquals("other", own.get(0).tenantId());''','''        assertEquals(2, own.size(), "X: review and check, both open");
        assertTrue(own.stream().allMatch(t -> "other".equals(t.tenantId())));'''),
('''        // the server's history port (read-model bridge); bound as the tenant's system identity, see the class note
''','''        // the server's history port (read-model bridge); bound as the tenant's system identity, see the class note.
        // Its instance check reads the projected read model, so wait until A is projected.
        eventually(() -> acts("dev", admin, piA, null, "running"), s -> !s.isEmpty());
''')]
for a,b in R:
    if c.count(a)!=1: sys.exit('miss '+a[:50])
    c=c.replace(a,b)
open(f,'w',encoding='utf-8',newline='\n').write(c); print('ok')
