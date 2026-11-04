package org.sangam.spike.govb.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import org.sangam.spike.api.EngineAdapter.ExternalWork;

/** Shared lifecycle (one engine, one fresh database per class) and small helpers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
public abstract class GovTestBase {

    /** Monday 2 Nov 2026, 10:00 IST: the engine clock at the start of every pattern. */
    protected static final Instant T0 = Instant.parse("2026-11-02T04:30:00Z");
    protected static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    protected Gov g;

    protected abstract Report report();

    protected abstract String dbName();

    @BeforeAll
    void openEngine() {
        g = Gov.open(dbName());
    }

    @AfterAll
    void closeEngine() {
        if (g != null) {
            try {
                report().write(g);
            } finally {
                g.close();
            }
        }
    }

    @BeforeEach
    void resetClock() {
        g.clock(T0);
    }

    protected static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    /** ISO-8601 with seconds and the +05:30 offset, as a government system would send it. */
    protected static String iso(Instant t) {
        return ISO_SECONDS.format(t.atOffset(IST));
    }

    protected static Instant days(long d) {
        return T0.plus(Duration.ofDays(d));
    }

    protected static Instant hours(long h) {
        return T0.plus(Duration.ofHours(h));
    }

    protected static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static HttpResponse<String> post(URI uri, String contentType, String body) {
        try {
            return HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Fetches every item of a topic (one worker, batches of {@code batch}); returns them. */
    protected List<ExternalWork> fetchAll(String worker, String topic, int batch) {
        List<ExternalWork> all = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            List<ExternalWork> w = g.adapter().fetchAndLock(worker, topic, batch, Duration.ofMinutes(5));
            if (w.isEmpty()) {
                break;
            }
            all.addAll(w);
        }
        return all;
    }

    /** One work item of a topic for one instance (fails if none). */
    protected ExternalWork fetchOne(String worker, String topic) {
        List<ExternalWork> w = g.adapter().fetchAndLock(worker, topic, 1, Duration.ofMinutes(5));
        if (w.size() != 1) {
            throw new AssertionError("expected 1 work item on topic " + topic + ", got " + w.size());
        }
        return w.get(0);
    }

    /**
     * Normalises a DMN COLLECT result variable: Operaton (collectEntries) gives a {@code List<String>}; Flowable gives a
     * JSON array of {@code {"<output>": value}} objects (a Jackson node of the engine's own Jackson line).
     */
    protected static List<String> collected(Object v, String output) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        try {
            JsonNode n = JSON.readTree(v.toString());
            List<String> out = new ArrayList<>();
            for (JsonNode e : n) {
                out.add(e.isObject() ? e.get(output).asText() : e.asText());
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read COLLECT result " + v, e);
        }
    }

    protected static Map<String, Object> percentiles(List<Long> nanos) {
        long[] a = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("n", a.length);
        out.put("p50Ms", round(a[(int) Math.floor(0.50 * (a.length - 1))] / 1e6));
        out.put("p95Ms", round(a[(int) Math.ceil(0.95 * (a.length - 1))] / 1e6));
        out.put("maxMs", round(a[a.length - 1] / 1e6));
        out.put("meanMs", round(Arrays.stream(a).average().orElse(0) / 1e6));
        return out;
    }

    protected static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    protected static String root(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        return r.getClass().getSimpleName() + ": " + String.valueOf(r.getMessage()).lines().findFirst().orElse("");
    }

    protected static String first(Throwable t) {
        return t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).lines().findFirst().orElse("");
    }
}
