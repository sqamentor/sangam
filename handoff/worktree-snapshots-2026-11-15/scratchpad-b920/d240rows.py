import os
import sys

os.chdir(sys.argv[1])


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for o, n in pairs:
        assert s.count(o) == 1, (p, o, s.count(o))
        s = s.replace(o, n)
    open(p, 'w', encoding='utf-8', newline='').write(s)


edit('sangam-ops/src/main/java/org/sangam/ops/utc/UtcRows.java', [
    (''' * pattern checked there) and are used unquoted, as the generated DDL creates them; values are always bound.
 */''', ''' * pattern checked there) and are used unquoted, as the generated DDL creates them; values are always bound.
 * <p>
 * Owner decision 240: with a {@link Session}, every statement runs with session zone {@link SessionZones#UTC} (stored
 * instants), and each read also reads the session-zoned columns with the declared historical zone (the wall clock as
 * written); the connection's own session zone is restored after every call.
 */'''),
    ('''    /** One row: its key and the read time columns (null values kept as null). */
    record Row(Key key, Map<String, Instant> values) {
    }
''', '''    /**
     * One row: its key and the read time columns (null values kept as null).
     *
     * @param values the values the conversion reads: the wall clock under the declared session zone for a session-zoned
     *               column, otherwise the stored value
     * @param stored the stored values (equal to {@code values} without a session)
     */
    record Row(Key key, Map<String, Instant> values, Map<String, Instant> stored) {
    }

    /**
     * Decision 240: the declared historical session zone and the session-zoned columns among the time columns.
     *
     * @param zones   the dialect's session zone access
     * @param zone    the declared zone as session zone text
     * @param columns the session-zoned columns of this table that are read
     */
    record Session(SessionZones zones, String zone, Set<String> columns) {
        Session {
            columns = Set.copyOf(columns);
        }
    }
'''),
    ('''    final String tenantColumn;

    private UtcRows(String table, List<String> primaryKey, List<String> timeColumns, String tenantColumn) {
        this.table = table;
        this.primaryKey = List.copyOf(primaryKey);
        this.timeColumns = List.copyOf(timeColumns);
        this.tenantColumn = tenantColumn;
    }

    /** Reads the table's primary key and checks that every named column exists. */
    static UtcRows of(Connection c, String table, List<String> timeColumns, boolean tenantFiltered) {''',
     '''    final String tenantColumn;
    final Session session;

    private UtcRows(String table, List<String> primaryKey, List<String> timeColumns, String tenantColumn,
                    Session session) {
        this.table = table;
        this.primaryKey = List.copyOf(primaryKey);
        this.timeColumns = List.copyOf(timeColumns);
        this.tenantColumn = tenantColumn;
        this.session = session;
    }

    /** Reads the table's primary key and checks that every named column exists. */
    static UtcRows of(Connection c, String table, List<String> timeColumns, boolean tenantFiltered) {
        return of(c, table, timeColumns, tenantFiltered, null);
    }

    /** As {@link #of(Connection, String, List, boolean)}, reading the session-zoned columns of {@code session}. */
    static UtcRows of(Connection c, String table, List<String> timeColumns, boolean tenantFiltered, Session session) {
        if (session != null && (session.columns().isEmpty() || !timeColumns.containsAll(session.columns()))) {
            throw new IllegalArgumentException("session-zoned columns " + session.columns() + " are not read columns");
        }'''),
    ('''            return new UtcRows(table, new ArrayList<>(pk.values()), timeColumns, tenantFiltered ? "TENANT_ID" : null);''',
     '''            return new UtcRows(table, new ArrayList<>(pk.values()), timeColumns, tenantFiltered ? "TENANT_ID" : null,
                    session);'''),
    ('''    boolean replace(Connection c, Key key, String column, Instant from, Instant to) {
        Object[] k = key.bindValues();
        Object[] params = new Object[k.length + 2];
        params[0] = to;
        System.arraycopy(k, 0, params, 1, k.length);
        params[params.length - 1] = from;
        return Jdbc.update(c, "UPDATE " + table + " SET " + column + " = ? WHERE " + keyPredicate() + " AND " + column
                + " = ?", params) == 1;
    }
''', '''    boolean replace(Connection c, Key key, String column, Instant from, Instant to) {
        Object[] k = key.bindValues();
        Object[] params = new Object[k.length + 2];
        params[0] = to;
        System.arraycopy(k, 0, params, 1, k.length);
        params[params.length - 1] = from;
        return inZone(c, SessionZones.UTC, () -> Jdbc.update(c, "UPDATE " + table + " SET " + column + " = ? WHERE "
                + keyPredicate() + " AND " + column + " = ?", params) == 1);
    }

    /**
     * Decision 240 rollback of a session-zoned cell: while it still stores {@code storedNow} (checked under a row lock
     * in {@link SessionZones#UTC}), writes {@code wallClock} under the declared session zone, so the server stores the
     * original instant again, and reads it back. The apply never converts a wall clock in a DST overlap of the session
     * zone, so the write is unambiguous.
     *
     * @return false when the cell no longer stores {@code storedNow}
     */
    boolean restoreWallClock(Connection c, Key key, String column, Instant storedNow, Instant wallClock) {
        if (session == null || !session.columns().contains(column)) {
            throw new IllegalStateException(table + "." + column + " is not a session-zoned column of this run");
        }
        Object[] k = key.bindValues();
        String where = " WHERE " + keyPredicate();
        String saved = session.zones().current(c);
        try {
            session.zones().set(c, SessionZones.UTC);
            Optional<Instant> now = Jdbc.one(c, "SELECT " + column + " FROM " + table + where + " FOR UPDATE",
                    rs -> Jdbc.instant(rs, column), k);
            if (now.isEmpty() || !storedNow.equals(now.get())) {
                return false;
            }
            session.zones().set(c, session.zone());
            Object[] params = new Object[k.length + 1];
            params[0] = wallClock;
            System.arraycopy(k, 0, params, 1, k.length);
            if (Jdbc.update(c, "UPDATE " + table + " SET " + column + " = ?" + where, params) != 1) {
                return false;
            }
            Instant back = Jdbc.one(c, "SELECT " + column + " FROM " + table + where, rs -> Jdbc.instant(rs, column), k)
                    .orElseThrow();
            if (!wallClock.equals(back)) {
                throw new IllegalStateException(table + "." + column + " " + key.json() + " reads back " + back
                        + " under session zone " + session.zone() + ", not " + wallClock + ": rollback stopped");
            }
            return true;
        } finally {
            session.zones().set(c, saved);
        }
    }

    /** Runs {@code body} with the session zone {@code zone} when this table has a session, then restores it. */
    private <T> T inZone(Connection c, String zone, java.util.function.Supplier<T> body) {
        if (session == null) {
            return body.get();
        }
        String saved = session.zones().current(c);
        try {
            session.zones().set(c, zone);
            return body.get();
        } finally {
            session.zones().set(c, saved);
        }
    }
'''),
    ('''    private List<Row> read(Connection c, String sql, int limit, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {''',
     '''    private List<Row> read(Connection c, String sql, int limit, Object... params) {
        if (session == null) {
            return readOnce(c, sql, limit, params);
        }
        String saved = session.zones().current(c);
        try {
            session.zones().set(c, SessionZones.UTC);
            List<Row> stored = readOnce(c, sql, limit, params);
            session.zones().set(c, session.zone());
            List<Row> wall = readOnce(c, sql, limit, params);
            if (wall.size() != stored.size()) {
                throw new IllegalStateException(table + " changed between its two reads: stop its writers");
            }
            List<Row> out = new ArrayList<>();
            for (int i = 0; i < stored.size(); i++) {
                Row s = stored.get(i);
                Row w = wall.get(i);
                if (!s.key().equals(w.key())) {
                    throw new IllegalStateException(table + " changed between its two reads: stop its writers");
                }
                Map<String, Instant> values = new LinkedHashMap<>(s.stored());
                for (String col : session.columns()) {
                    values.put(col, w.stored().get(col));
                }
                out.add(new Row(s.key(), java.util.Collections.unmodifiableMap(values), s.stored()));
            }
            return out;
        } finally {
            session.zones().set(c, saved);
        }
    }

    private List<Row> readOnce(Connection c, String sql, int limit, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {'''),
    ('''                    out.add(new Row(new Key(parts), values));''',
     '''                    out.add(new Row(new Key(parts), values, values));'''),
])
print('ok')
