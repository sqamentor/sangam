import sys
root = sys.argv[1]
s = None


def rep(a, b):
    global s
    a = a.replace('\n', '\r\n'); b = b.replace('\n', '\r\n')
    assert s.count(a) == 1, a[:80]
    s = s.replace(a, b)


p = root + '/sangam-config/src/main/java/org/sangam/config/api/ConfigConnector.java'
s = open(p, 'rb').read().decode('utf-8')
rep(''' * (sangam-integration {@code PackConnectorBindings}). All operations are read-only except {@code series.next}, which is
 * idempotent per operationId (the number allocated for an operationId is remembered).
''', ''' * (sangam-integration {@code PackConnectorBindings}). All operations are read-only except {@code series.next}, which
 * reserves its number under a key: the caller's {@code ref} when given (the document identity, so a re-run of the
 * same document under a new operation gets the same number), else the operationId. With a durable series
 * ({@link SeriesReservations}, the JDBC store) the reservation is committed with the counter step, so a retry on any
 * node or after a restart returns the same number and burns none (SL-130 follow-up); an in-memory series remembers
 * its reservations in this node's memory only, as it does its counters.
''')
rep(''' *   <li>{@code series.next}: {@code series}, {@code unit}, {@code period}, {@code format} → {@code number};</li>
''', ''' *   <li>{@code series.next}: {@code series}, {@code unit}, {@code period}, {@code format}, optional {@code ref} →
 *       {@code number} (a repeat with the same key answers the stored number as a duplicate);</li>
''')
rep('''public final class ConfigConnector implements SangamConnector {

    private final ProfileResolver resolver;
    private final ClassificationRegistry registry;
    private final NumberSeries series;
    private final Map<String, Object> issued = new java.util.concurrent.ConcurrentHashMap<>();
''', '''public final class ConfigConnector implements SangamConnector {

    /**
     * A number series that reserves numbers durably by key (SL-130 follow-up; implemented by the JDBC config store).
     * The reservation and the counter step commit in one transaction.
     */
    public interface SeriesReservations {

        /**
         * A reserved number.
         *
         * @param number   the counter value
         * @param display  the formatted number, or null when the call had no format
         * @param replayed true when the key was already reserved (nothing was drawn)
         */
        record Reserved(long number, String display, boolean replayed) {
            /** What {@code series.next} answers: the formatted number, else the number. */
            public Object value() {
                return display != null ? display : number;
            }
        }

        /**
         * The number reserved for {@code key} in (scope, series); when there is none, the next number of
         * (scope, series, unit, period), formatted by {@code display} (may be null), reserved and counted together.
         */
        Reserved reserve(String scope, String series, String unit, String period, String key,
                         java.util.function.LongFunction<String> display);

        /** The reservation of {@code key}, without drawing. */
        java.util.Optional<Reserved> reserved(String scope, String series, String key);
    }

    private final ProfileResolver resolver;
    private final ClassificationRegistry registry;
    private final NumberSeries series;
    /** Durable reservations when the series supports them (the JDBC store), else null. */
    private final SeriesReservations reservations;
    /** Node-local reservations of a series without durable ones (in-memory series: dev and tests). */
    private final Map<String, Object> issued = new java.util.concurrent.ConcurrentHashMap<>();
''')
rep('''        this.series = series;
    }
''', '''        this.series = series;
        this.reservations = series instanceof SeriesReservations r ? r : null;
    }

    /** The reservation key of a {@code series.next} call: {@code ref:<ref>} when the caller names one, else {@code op:<operationId>}. */
    static String reservationKey(ConnectorRequest r) {
        String ref = r.str("ref");
        return ref != null && !ref.isBlank() ? "ref:" + ref : "op:" + r.operationId();
    }
''')
rep('''                case "series.next" -> {
                    Object prior = issued.get(r.operationId().toString());
                    if (prior != null) {
                        out.put("number", prior);
                        return new ConnectorResult.Success(out, String.valueOf(prior), true);
                    }
                    String period = r.str("period") == null ? asOf.toString() : r.str("period");
                    long n = series.next(r.tenantId(), r.str("series"), r.str("unit"), period);
                    Object number = r.str("format") == null ? n
                            : NumberSeries.format(r.str("format"), r.str("unit"), period, asOf, n);
                    issued.put(r.operationId().toString(), number);
                    out.put("number", number);
                }
''', '''                case "series.next" -> {
                    String key = reservationKey(r);
                    String period = r.str("period") == null ? asOf.toString() : r.str("period");
                    String unit = r.str("unit");
                    String format = r.str("format");
                    if (reservations != null) {
                        SeriesReservations.Reserved res = reservations.reserve(r.tenantId(), r.str("series"), unit, period, key,
                                format == null ? null : n -> NumberSeries.format(format, unit, period, asOf, n));
                        out.put("number", res.value());
                        return res.replayed() ? new ConnectorResult.Success(out, String.valueOf(res.value()), true)
                                : ConnectorResult.Success.of(out, null);
                    }
                    String localKey = localKey(r, key);
                    Object prior = issued.get(localKey);
                    if (prior != null) {
                        out.put("number", prior);
                        return new ConnectorResult.Success(out, String.valueOf(prior), true);
                    }
                    long n = series.next(r.tenantId(), r.str("series"), unit, period);
                    Object number = format == null ? n : NumberSeries.format(format, unit, period, asOf, n);
                    issued.put(localKey, number);
                    out.put("number", number);
                }
''')
rep('''    @Override
    public LookupResult lookup(ConnectorRequest r) {
        Object prior = issued.get(r.operationId().toString());
        return prior == null ? new LookupResult.NotFound()
                : new LookupResult.Found(true, Map.of("number", prior), String.valueOf(prior), null);
    }
''', '''    @Override
    public LookupResult lookup(ConnectorRequest r) {
        String key = reservationKey(r);
        Object prior;
        if (reservations != null) {
            if (r.str("series") == null) {
                return new LookupResult.NotFound();
            }
            prior = reservations.reserved(r.tenantId(), r.str("series"), key).map(SeriesReservations.Reserved::value)
                    .orElse(null);
        } else {
            prior = issued.get(localKey(r, key));
        }
        return prior == null ? new LookupResult.NotFound()
                : new LookupResult.Found(true, Map.of("number", prior), String.valueOf(prior), null);
    }

    /** The node-local key: tenant and series as the durable reservation's scope, plus the reservation key. */
    private static String localKey(ConnectorRequest r, String key) {
        return r.tenantId() + "\\u0000" + r.str("series") + "\\u0000" + key;
    }
''')
open(p, 'wb').write(s.encode('utf-8'))
print("ok")
