import sys
root = sys.argv[1]
s = None


def rep(a, b):
    global s
    a = a.replace('\n', '\r\n'); b = b.replace('\n', '\r\n')
    assert s.count(a) == 1, a[:80]
    s = s.replace(a, b)


p = root + '/sangam-config/src/main/java/org/sangam/config/store/JdbcConfigStore.java'
s = open(p, 'rb').read().decode('utf-8')
rep('''import org.sangam.config.admin.ConfigAdminService;
''', '''import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.api.ConfigConnector;
''')
rep('''import java.util.function.Function;
''', '''import java.util.function.Function;
import java.util.function.LongFunction;
''')
rep('''public final class JdbcConfigStore implements ProfileRepository, MasterStore, NumberSeries, ConfigAdminService.ChangeLog {''',
    '''public final class JdbcConfigStore implements ProfileRepository, MasterStore, NumberSeries, ConfigAdminService.ChangeLog,
        ConfigConnector.SeriesReservations {''')
rep('''    @Override
    public long next(String scope, String series, String unit, String period) {
        return inTx(c -> {
            Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CFG_SERIES (scope_id, series_id, unit_id, period, last_no) "
                    + "VALUES (?,?,?,?,0)", scope, series, unit, period);
            long cur = Jdbc.one(c, dialect.lockingSelect("last_no", "CFG_SERIES",
                            "scope_id = ? AND series_id = ? AND unit_id = ? AND period = ?"), rs -> rs.getLong(1),
                    scope, series, unit, period).orElseThrow();
            Jdbc.update(c, "UPDATE CFG_SERIES SET last_no = ? WHERE scope_id = ? AND series_id = ? AND unit_id = ? AND period = ?",
                    cur + 1, scope, series, unit, period);
            return cur + 1;
        });
    }
''', '''    @Override
    public long next(String scope, String series, String unit, String period) {
        return inTx(c -> allocate(c, scope, series, unit, period));
    }

    /** The next number of the series, under the counter's row lock, in the caller's transaction. */
    private long allocate(Connection c, String scope, String series, String unit, String period) {
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CFG_SERIES (scope_id, series_id, unit_id, period, last_no) "
                + "VALUES (?,?,?,?,0)", scope, series, unit, period);
        long cur = Jdbc.one(c, dialect.lockingSelect("last_no", "CFG_SERIES",
                        "scope_id = ? AND series_id = ? AND unit_id = ? AND period = ?"), rs -> rs.getLong(1),
                scope, series, unit, period).orElseThrow();
        Jdbc.update(c, "UPDATE CFG_SERIES SET last_no = ? WHERE scope_id = ? AND series_id = ? AND unit_id = ? AND period = ?",
                cur + 1, scope, series, unit, period);
        return cur + 1;
    }

    /** A concurrent reservation of the same key committed first: roll back this draw and return the stored one. */
    private static final class LostReservationRace extends RuntimeException {
        LostReservationRace() {
            super(null, null, false, false);
        }
    }

    /**
     * SL-130 follow-up: the durable {@code series.next} reservation. One transaction reads the reservation of
     * {@code key}; when there is none it advances the counter (row lock), formats the number and inserts the
     * reservation, so the counter and the reservation commit together. A retry with the same key (any node, after a
     * restart) returns the stored number and draws none. Two first calls with one key race on the reservation's
     * primary key; the loser's transaction rolls back (its counter step too) and it returns the winner's number.
     */
    @Override
    public ConfigConnector.SeriesReservations.Reserved reserve(String scope, String series, String unit, String period,
                                                               String key, LongFunction<String> display) {
        try {
            return inTx(c -> {
                Optional<ConfigConnector.SeriesReservations.Reserved> prior = findReservation(c, scope, series, key);
                if (prior.isPresent()) {
                    return prior.get();
                }
                long n = allocate(c, scope, series, unit, period);
                String shown = display == null ? null : display.apply(n);
                boolean inserted;
                try {
                    inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO CFG_SERIES_RESERVATION (scope_id, series_id, res_key, "
                                    + "unit_id, period, number_no, display, created_at) VALUES (?,?,?,?,?,?,?," + dialect.now() + ")",
                            scope, series, key, unit, period, n, shown);
                } catch (Jdbc.LayerSqlException e) {
                    if (dialect.isUniqueViolation(e.sql())) {
                        throw new LostReservationRace();
                    }
                    throw e;
                }
                if (!inserted) {
                    throw new LostReservationRace();
                }
                return new ConfigConnector.SeriesReservations.Reserved(n, shown, false);
            });
        } catch (LostReservationRace e) {
            return reserved(scope, series, key).orElseThrow(() -> new IllegalStateException(
                    "series reservation " + key + " lost a race but is not stored"));
        }
    }

    @Override
    public Optional<ConfigConnector.SeriesReservations.Reserved> reserved(String scope, String series, String key) {
        return inTx(c -> findReservation(c, scope, series, key));
    }

    private static Optional<ConfigConnector.SeriesReservations.Reserved> findReservation(Connection c, String scope,
                                                                                          String series, String key) {
        return Jdbc.one(c, "SELECT number_no, display FROM CFG_SERIES_RESERVATION WHERE scope_id = ? AND series_id = ? "
                        + "AND res_key = ?",
                rs -> new ConfigConnector.SeriesReservations.Reserved(rs.getLong(1), rs.getString(2), true), scope, series, key);
    }
''')
open(p, 'wb').write(s.encode('utf-8'))

p = root + '/sangam-config/src/main/java/org/sangam/config/store/ConfigSchema.java'
s = open(p, 'rb').read().decode('utf-8')
rep(''' *   <li>{@code CFG_SERIES}: number-series counters by (scope, series, unit, period), allocated under a row lock;</li>
''', ''' *   <li>{@code CFG_SERIES}: number-series counters by (scope, series, unit, period), allocated under a row lock;</li>
 *   <li>{@code CFG_SERIES_RESERVATION}: the number reserved for a caller key (its {@code ref}, else the operationId)
 *       per (scope, series), written with the counter step so a retry returns it (SL-130 follow-up);</li>
''')
rep('''              PRIMARY KEY (scope_id, series_id, unit_id, period)
            )""",
''', '''              PRIMARY KEY (scope_id, series_id, unit_id, period)
            )""",
            """
            CREATE TABLE CFG_SERIES_RESERVATION (
              scope_id       {str64} NOT NULL,
              series_id      {str64} NOT NULL,
              res_key        {str} NOT NULL,
              unit_id        {str64},
              period         {str64},
              number_no      {long} NOT NULL,
              display        {str},
              created_at     {ts} NOT NULL,
              PRIMARY KEY (scope_id, series_id, res_key)
            )""",
''')
open(p, 'wb').write(s.encode('utf-8'))
print("ok")
