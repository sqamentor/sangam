// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.util.ArrayList;
import java.util.List;

import org.sangam.server.acceptance.engine.EngineScenarioSupport.Ctx;

/**
 * The spike's gov-a {@code Rec} vocabulary over {@link Ctx}: {@code check} is a real assertion (collected by the
 * scenario harness); features, variants, native API, workarounds, product-layer items, notes and timings are recorded
 * as details only, exactly as the spike recorded them in its JSON.
 */
public final class GovARec {

    public static final String PASS = "PASS";
    public static final String WORKAROUND = "PASS-WITH-WORKAROUND";

    private final Ctx ctx;
    private final List<String> notes = new ArrayList<>();
    private final List<String> nativeApi = new ArrayList<>();
    private final List<String> workarounds = new ArrayList<>();

    GovARec(Ctx ctx, String title) {
        this.ctx = ctx;
        ctx.detail("title", title);
    }

    public GovARec feature(String... f) {
        ctx.detail("features", List.of(f));
        return this;
    }

    public GovARec variant(String v) {
        ctx.detail("variant." + v.hashCode(), v);
        return this;
    }

    public GovARec nativeApi(String api) {
        if (!nativeApi.contains(api)) {
            nativeApi.add(api);
            ctx.detail("nativeApi", nativeApi);
        }
        return this;
    }

    public GovARec workaround(String what, int lines) {
        workarounds.add(what + " (" + lines + " lines)");
        ctx.workaround(what);
        return this;
    }

    public GovARec productLayer(String what, int lines) {
        ctx.detail("productLayer." + what.hashCode(), what + " (" + lines + " lines)");
        return this;
    }

    public GovARec note(String n) {
        notes.add(n);
        ctx.detail("notes", notes);
        return this;
    }

    public GovARec detail(String k, Object v) {
        ctx.detail(k, v);
        return this;
    }

    public GovARec timing(String k, long ms) {
        ctx.detail("timing." + k, ms);
        return this;
    }

    public boolean check(boolean ok, String what) {
        return ctx.check(what, ok);
    }

    public GovARec result(String r) {
        ctx.detail("spikeResultClass", r);
        return this;
    }

    public boolean hasFailures() {
        return !ctx.failedChecks().isEmpty();
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
}
