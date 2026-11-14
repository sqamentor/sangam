import sys
root = sys.argv[1]


def patch(path, pairs):
    s = open(path, encoding='utf-8').read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:80], s.count(old))
        s = s.replace(old, new)
    open(path, 'w', encoding='utf-8', newline='\n').write(s)


patch(root + '/sangam-resources/src/main/java/org/sangam/resources/catalogue/ResourceCatalogue.java', [
    ('''    public Resource update(Connection c, SecurityIdentity actor, String resourceId, long expectedVersion, Resource.Patch p) {''',
     '''    /**
     * Create-or-update by resource id (the bulk entry point, M-130), all on {@code c}: a new id is created; an existing
     * one is moved when the parent differs and patched when a given field differs; an identical draft changes nothing
     * and writes no event. Type and code cannot change. Every step goes through the per-object guard as {@code actor}.
     */
    public Resource upsert(Connection c, SecurityIdentity actor, Resource.Draft d) {
        if (d.resourceId() == null || d.resourceId().isBlank()) {
            throw ResourcesException.invalid("RESOURCE_ID", "an upsert needs the resource id");
        }
        Optional<Resource> found = find(c, actor.tenantId(), d.resourceId());
        if (found.isEmpty()) {
            return create(c, actor, d);
        }
        Resource r = found.get();
        if (d.typeId() != null && !d.typeId().equals(r.typeId())) {
            throw ResourcesException.invalid("TYPE_CHANGED", "the type of resource " + r.resourceId() + " cannot change");
        }
        if (d.code() != null && !d.code().isBlank() && !d.code().equals(r.code())) {
            throw ResourcesException.invalid("CODE_CHANGED", "the code of resource " + r.resourceId() + " cannot change");
        }
        if (!java.util.Objects.equals(d.parentId(), r.parentId())) {
            r = move(c, actor, r.resourceId(), d.parentId());
        }
        Resource.Patch p = new Resource.Patch(d.name(), d.orgUnitId(), d.locationId(), d.geoLat(), d.geoLon(), d.capacity(),
                d.unitLabel(), d.calendarId(), d.attributes());
        return unchanged(r, p) ? r : update(c, actor, r.resourceId(), r.version(), p);
    }

    private static boolean unchanged(Resource r, Resource.Patch p) {
        boolean attrs = p.attributes() == null || p.attributes().entrySet().stream()
                .allMatch(e -> java.util.Objects.equals(e.getValue(), r.attributes().get(e.getKey())));
        return same(p.name(), r.name()) && same(p.orgUnitId(), r.orgUnitId()) && same(p.locationId(), r.locationId())
                && same(p.geoLat(), r.geoLat()) && same(p.geoLon(), r.geoLon())
                && (p.capacity() == null || p.capacity() == r.capacity()) && same(p.unitLabel(), r.unitLabel())
                && same(p.calendarId(), r.calendarId()) && attrs;
    }

    private static boolean same(String given, String current) {
        return given == null || given.equals(current);
    }

    public Resource update(Connection c, SecurityIdentity actor, String resourceId, long expectedVersion, Resource.Patch p) {'''),
])

patch(root + '/sangam-config/src/main/java/org/sangam/config/store/JdbcConfigStore.java', [
    ('''    @Override
    public void put(MasterCode m) {
        String from = m.validFrom() == null ? "0000-01-01" : m.validFrom().toString();
        String parent = AbsentKey.encode(m.parentCode());
        inTx(c -> {
            Jdbc.update(c, "DELETE FROM CFG_MASTER_CODE WHERE scope_id = ? AND scheme_id = ? AND segment_id = ? AND "
                    + "parent_code = ? AND code = ? AND valid_from = ?", m.scope(), m.scheme(), m.segment(), parent, m.code(), from);
            Jdbc.update(c, "INSERT INTO CFG_MASTER_CODE (scope_id, scheme_id, segment_id, parent_code, code, valid_from, "
                            + "valid_to, name_json, flags_json, source_ref) VALUES (?,?,?,?,?,?,?,?,?,?)", m.scope(), m.scheme(),
                    m.segment(), parent, m.code(), from, str(m.validTo()), CanonicalJson.write(m.name()),
                    CanonicalJson.write(m.flags()), m.sourceRef());
            return null;
        });
    }
''', '''    @Override
    public void put(MasterCode m) {
        inTx(c -> {
            put(c, m);
            return null;
        });
    }

    /**
     * Upserts one master code on the caller's connection and transaction (M-130 bulk sink): replaces the row with the
     * same key and {@code valid_from}, so a repeat is idempotent and commits or rolls back with the caller's work.
     */
    public void put(Connection c, MasterCode m) {
        String from = m.validFrom() == null ? "0000-01-01" : m.validFrom().toString();
        String parent = AbsentKey.encode(m.parentCode());
        Jdbc.update(c, "DELETE FROM CFG_MASTER_CODE WHERE scope_id = ? AND scheme_id = ? AND segment_id = ? AND "
                + "parent_code = ? AND code = ? AND valid_from = ?", m.scope(), m.scheme(), m.segment(), parent, m.code(), from);
        Jdbc.update(c, "INSERT INTO CFG_MASTER_CODE (scope_id, scheme_id, segment_id, parent_code, code, valid_from, "
                        + "valid_to, name_json, flags_json, source_ref) VALUES (?,?,?,?,?,?,?,?,?,?)", m.scope(), m.scheme(),
                m.segment(), parent, m.code(), from, str(m.validTo()), CanonicalJson.write(m.name()),
                CanonicalJson.write(m.flags()), m.sourceRef());
    }
'''),
])
print('ok')
