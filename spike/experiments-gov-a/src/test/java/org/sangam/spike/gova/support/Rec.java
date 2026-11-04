package org.sangam.spike.gova.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of one pattern / domain model on one engine.
 *
 * <ul>
 *   <li>{@code PASS}: the engine does it with its own features; vendor extension attributes that are needed are listed
 *       under {@code variants} (they are model variants, not workarounds).</li>
 *   <li>{@code PASS-WITH-WORKAROUND}: the outcome is reached, but only with product/test code or a model trick that
 *       stands in for a missing engine feature (listed under {@code workarounds}, with line counts).</li>
 *   <li>{@code FAIL}: the expected behaviour was not reached (reason in {@code error} / {@code failures}).</li>
 *   <li>{@code UNSUPPORTED}: the engine has no way to do it (reason in {@code error}).</li>
 * </ul>
 */
public final class Rec {

    public static final String PASS = "PASS";
    public static final String WORKAROUND = "PASS-WITH-WORKAROUND";
    public static final String FAIL = "FAIL";
    public static final String UNSUPPORTED = "UNSUPPORTED";

    private final Map<String, Object> m = new LinkedHashMap<>();
    private final List<String> features = new ArrayList<>();
    private final List<String> variants = new ArrayList<>();
    private final List<String> nativeApi = new ArrayList<>();
    private final List<Map<String, Object>> workarounds = new ArrayList<>();
    private final List<Map<String, Object>> productLayer = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final Map<String, Object> details = new LinkedHashMap<>();
    private final Map<String, Object> timings = new LinkedHashMap<>();
    private String result;

    public Rec(String id, String title) {
        m.put("id", id);
        m.put("title", title);
    }

    public String id() {
        return (String) m.get("id");
    }

    public Rec feature(String... f) {
        features.addAll(List.of(f));
        return this;
    }

    public Rec variant(String v) {
        variants.add(v);
        return this;
    }

    public Rec nativeApi(String api) {
        if (!nativeApi.contains(api)) {
            nativeApi.add(api);
        }
        return this;
    }

    /** A workaround: what it replaces and how many lines of product/test code or model it takes. */
    public Rec workaround(String what, int lines) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("what", what);
        w.put("lines", lines);
        workarounds.add(w);
        return this;
    }

    /**
     * Product-layer code that the design puts outside the engine on purpose (ledgers, seat register, file workers,
     * operationId rules): recorded for completeness, the same on both engines, and not counted as a workaround.
     */
    public Rec productLayer(String what, int lines) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("what", what);
        w.put("lines", lines);
        productLayer.add(w);
        return this;
    }

    public Rec note(String n) {
        notes.add(n);
        return this;
    }

    public Rec detail(String k, Object v) {
        details.put(k, v);
        return this;
    }

    public Rec timing(String k, long ms) {
        timings.put(k, ms);
        return this;
    }

    /** Records a check; a false check makes the result FAIL (the scenario continues so later checks are recorded too). */
    public boolean check(boolean ok, String what) {
        if (!ok) {
            failures.add(what);
        }
        return ok;
    }

    public Rec result(String r) {
        this.result = r;
        return this;
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    public String resultOrNull() {
        return result;
    }

    public Map<String, Object> finish(long totalMs, Throwable error) {
        String r = result;
        if (error != null) {
            if (error instanceof Unsupported) {
                r = UNSUPPORTED;
            } else {
                r = FAIL;
            }
            m.put("error", errorText(error));
        } else if (!failures.isEmpty()) {
            r = FAIL;
        } else if (r == null) {
            r = workarounds.isEmpty() ? PASS : WORKAROUND;
        }
        m.put("result", r);
        m.put("featuresUsed", features);
        m.put("variants", variants);
        m.put("nativeApi", nativeApi);
        m.put("workarounds", workarounds);
        m.put("workaroundLines", workarounds.stream().mapToInt(w -> (Integer) w.get("lines")).sum());
        if (!productLayer.isEmpty()) {
            m.put("productLayer", productLayer);
        }
        m.put("timingMs", totalMs);
        if (!timings.isEmpty()) {
            m.put("timings", timings);
        }
        if (!failures.isEmpty()) {
            m.put("failures", failures);
        }
        if (!notes.isEmpty()) {
            m.put("notes", notes);
        }
        if (!details.isEmpty()) {
            m.put("details", details);
        }
        return m;
    }

    public static String errorText(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 4) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            String msg = c.getMessage();
            if (msg != null && msg.length() > 500) {
                msg = msg.substring(0, 500) + "...";
            }
            sb.append(c.getClass().getName()).append(": ").append(msg);
            if (c.getCause() == c) {
                break;
            }
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    /** Thrown when the engine has no way to do something; the scenario becomes UNSUPPORTED. */
    public static final class Unsupported extends RuntimeException {
        public Unsupported(String message) {
            super(message);
        }
    }
}
