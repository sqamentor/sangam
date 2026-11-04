package org.sangam.spike.load;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Command retry with exponential back-off and full jitter (SANGAM.md VI.1.1 rule 7: up to 5 retries, base 20 ms,
 * cap 1 s) and the per-dialect error classification of VI.1.6. Every exception seen (including the ones that were
 * retried successfully) is counted by kind, so the raw OLE / deadlock numbers can be compared between engines.
 */
public final class Retry {

    public static final int MAX_RETRIES = 5;
    private static final long BASE_MS = 20;
    private static final long CAP_MS = 1000;

    /** Error kinds. Only {@link #OTHER} is not retryable. */
    public enum Kind { OLE, DEADLOCK, SERIALIZATION, LOCK_TIMEOUT, OTHER }

    /** Thrown when a command still fails after its retries, or fails with a non-retryable error. */
    public static final class Unrecovered extends RuntimeException {
        public final Kind lastKind;

        Unrecovered(Throwable cause, Kind lastKind) {
            super(cause);
            this.lastKind = lastKind;
        }
    }

    private final String dialect;
    final AtomicLong ops = new AtomicLong();
    final AtomicLong succeeded = new AtomicLong();
    final AtomicLong attempts = new AtomicLong();
    final AtomicLong opsRetried = new AtomicLong();
    final AtomicLong unrecovered = new AtomicLong();
    final AtomicLongArray byKind = new AtomicLongArray(Kind.values().length);
    /** attempts histogram: index = attempts used (1..MAX_RETRIES+1) */
    final AtomicLongArray attemptsHistogram = new AtomicLongArray(MAX_RETRIES + 2);
    final Map<String, AtomicLong> exceptionClasses = new ConcurrentHashMap<>();
    final Map<String, String> samples = new ConcurrentHashMap<>();
    final Map<String, AtomicLong> sqlCodes = new ConcurrentHashMap<>();

    public Retry(String dialect) {
        this.dialect = dialect;
    }

    public <T> T call(Callable<T> command) {
        ops.incrementAndGet();
        Kind last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            attempts.incrementAndGet();
            try {
                T result = command.call();
                succeeded.incrementAndGet();
                attemptsHistogram.incrementAndGet(attempt + 1);
                if (attempt > 0) {
                    opsRetried.incrementAndGet();
                }
                return result;
            } catch (Throwable t) {
                last = classify(t, dialect);
                byKind.incrementAndGet(last.ordinal());
                String cls = t.getClass().getName();
                exceptionClasses.computeIfAbsent(last + " " + cls, k -> new AtomicLong()).incrementAndGet();
                String code = sqlCode(t);
                if (code != null) {
                    sqlCodes.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
                }
                samples.putIfAbsent(last + " " + cls, abbreviate(String.valueOf(rootMessage(t)), 300));
                if (last == Kind.OTHER || attempt == MAX_RETRIES) {
                    unrecovered.incrementAndGet();
                    if (attempt > 0) {
                        opsRetried.incrementAndGet();
                    }
                    throw new Unrecovered(t, last);
                }
                long cap = Math.min(CAP_MS, BASE_MS << attempt);
                sleep(ThreadLocalRandom.current().nextLong(cap + 1));
            }
        }
        throw new AssertionError("unreachable");
    }

    public void run(ThrowingRunnable command) {
        call(() -> {
            command.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ classification (VI.1.6)

    /**
     * Classifies by walking the cause chain (and SQLException next-exception chains). A database deadlock /
     * serialization / lock-timeout code wins over an engine OptimisticLockingException wrapper, so a deadlock that an
     * engine re-throws as an OLE is still counted as a deadlock.
     */
    public static Kind classify(Throwable t, String dialect) {
        boolean ole = false;
        int guard = 0;
        for (Throwable x = t; x != null && guard < 50; x = x.getCause(), guard++) {
            if (x instanceof SQLException s) {
                for (SQLException n = s; n != null; n = n.getNextException()) {
                    Kind k = sqlKind(n, dialect);
                    if (k != null) {
                        return k;
                    }
                    if (n.getNextException() == n) {
                        break;
                    }
                }
            }
            String name = x.getClass().getSimpleName();
            if (name.contains("OptimisticLocking")) {
                ole = true;
            }
            if (x.getCause() == x) {
                break;
            }
        }
        return ole ? Kind.OLE : Kind.OTHER;
    }

    static Kind sqlKind(SQLException e, String dialect) {
        String state = e.getSQLState();
        int code = e.getErrorCode();
        switch (dialect) {
            case "oracle" -> {
                if (code == 60) {
                    return Kind.DEADLOCK;          // ORA-00060
                }
                if (code == 8177) {
                    return Kind.SERIALIZATION;     // ORA-08177
                }
                if (code == 30006 || code == 54) {
                    return Kind.LOCK_TIMEOUT;      // ORA-30006 / ORA-00054
                }
            }
            case "sqlserver" -> {
                if (code == 1205) {
                    return Kind.DEADLOCK;          // deadlock victim (SQLState 40001 as well)
                }
                if (code == 1222) {
                    return Kind.LOCK_TIMEOUT;
                }
                if (code == 3960) {
                    return Kind.SERIALIZATION;     // snapshot update conflict
                }
            }
            default -> {
                if ("40P01".equals(state)) {
                    return Kind.DEADLOCK;
                }
                if ("40001".equals(state)) {
                    return Kind.SERIALIZATION;
                }
                if ("55P03".equals(state)) {
                    return Kind.LOCK_TIMEOUT;
                }
            }
        }
        return null;
    }

    static String sqlCode(Throwable t) {
        int guard = 0;
        for (Throwable x = t; x != null && guard < 50; x = x.getCause(), guard++) {
            if (x instanceof SQLException s) {
                return s.getSQLState() + "/" + s.getErrorCode();
            }
            if (x.getCause() == x) {
                break;
            }
        }
        return null;
    }

    static String rootMessage(Throwable t) {
        Throwable r = t;
        int guard = 0;
        while (r.getCause() != null && r.getCause() != r && guard++ < 50) {
            r = r.getCause();
        }
        return r.getClass().getSimpleName() + ": " + r.getMessage() + " | top: " + t.getClass().getSimpleName() + ": "
                + t.getMessage();
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ reporting

    public long ops() {
        return ops.get();
    }

    public long unrecovered() {
        return unrecovered.get();
    }

    public long kind(Kind k) {
        return byKind.get(k.ordinal());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("operations", ops.get());
        m.put("successes", succeeded.get());
        m.put("unrecoveredFailures", unrecovered.get());
        m.put("attempts", attempts.get());
        m.put("retries", attempts.get() - ops.get());
        m.put("operationsThatNeededRetry", opsRetried.get());
        Map<String, Object> kinds = new java.util.LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            kinds.put(k.name(), byKind.get(k.ordinal()));
        }
        m.put("exceptionsByKind", kinds);
        Map<String, Object> hist = new java.util.LinkedHashMap<>();
        for (int i = 1; i < attemptsHistogram.length(); i++) {
            if (attemptsHistogram.get(i) > 0) {
                hist.put(String.valueOf(i), attemptsHistogram.get(i));
            }
        }
        m.put("attemptsPerSuccessfulOperation", hist);
        Map<String, Object> cls = new java.util.TreeMap<>();
        exceptionClasses.forEach((k, v) -> cls.put(k, v.get()));
        m.put("exceptionClasses", cls);
        Map<String, Object> codes = new java.util.TreeMap<>();
        sqlCodes.forEach((k, v) -> codes.put(k, v.get()));
        m.put("sqlStateErrorCodes", codes);
        m.put("exceptionSamples", new java.util.TreeMap<>(samples));
        return m;
    }
}
