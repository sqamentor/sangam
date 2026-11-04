package org.sangam.spike.govb.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The record of one pattern (E16) or domain model (E17) on one engine: result, features used, model variants, native
 * API calls (anything the {@code EngineAdapter} interface does not expose), workarounds with line counts, timing,
 * scale numbers and notes. The current record is thread-bound so native helpers can log their own use.
 */
public final class Rec {

    public enum Result { PASS, PASS_WITH_WORKAROUND, FAIL, UNSUPPORTED }

    private static final ThreadLocal<Rec> CURRENT = new ThreadLocal<>();

    final String id;
    final String name;
    Result result;
    String reason;
    final Set<String> features = new LinkedHashSet<>();
    final Set<String> variants = new LinkedHashSet<>();
    final Set<String> nativeApi = new LinkedHashSet<>();
    final List<Map<String, Object>> workarounds = new ArrayList<>();
    final Map<String, Object> timingsMs = new LinkedHashMap<>();
    final Map<String, Object> scale = new LinkedHashMap<>();
    final List<String> notes = new ArrayList<>();
    final Map<String, String> subChecks = new LinkedHashMap<>();
    long startedNanos;
    long totalMs;

    Rec(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public static Rec current() {
        return CURRENT.get();
    }

    static void bind(Rec r) {
        CURRENT.set(r);
    }

    static void unbind() {
        CURRENT.remove();
    }

    /** Records a native API use on the current record (no-op outside a pattern run). */
    public static void nat(String api) {
        Rec r = CURRENT.get();
        if (r != null) {
            r.nativeApi.add(api);
        }
    }

    public Rec feature(String... f) {
        features.addAll(List.of(f));
        return this;
    }

    public Rec variant(String v) {
        variants.add(v);
        return this;
    }

    public Rec workaround(String what, int lines) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("what", what);
        w.put("lines", lines);
        workarounds.add(w);
        return this;
    }

    public Rec time(String what, long ms) {
        timingsMs.put(what, ms);
        return this;
    }

    public Rec timings(Map<String, Object> t) {
        timingsMs.putAll(t);
        return this;
    }

    public Rec scale(String what, Object value) {
        scale.put(what, value);
        return this;
    }

    public Rec note(String n) {
        notes.add(n);
        return this;
    }

    /** A named sub-check with its own outcome (e.g. "BPMN adHocSubProcess" = UNSUPPORTED while CMMN passes). */
    public Rec sub(String check, String outcome) {
        subChecks.put(check, outcome);
        return this;
    }

    /** Sets the result explicitly. Without a call the runner decides: PASS, or PASS_WITH_WORKAROUND if any workaround. */
    public Rec result(Result r, String why) {
        this.result = r;
        this.reason = why;
        return this;
    }

    public int workaroundLines() {
        return workarounds.stream().mapToInt(w -> (Integer) w.get("lines")).sum();
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("result", result == null ? null : result.name().replace('_', '-'));
        if (reason != null) {
            m.put("reason", reason);
        }
        m.put("features", List.copyOf(features));
        m.put("variants", List.copyOf(variants));
        m.put("nativeApi", List.copyOf(nativeApi));
        m.put("workarounds", workarounds);
        m.put("workaroundLines", workaroundLines());
        m.put("totalMs", totalMs);
        m.put("timingsMs", timingsMs);
        if (!scale.isEmpty()) {
            m.put("scale", scale);
        }
        if (!subChecks.isEmpty()) {
            m.put("subChecks", subChecks);
        }
        m.put("notes", notes);
        return m;
    }
}
