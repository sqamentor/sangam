package org.sangam.spike.it.support;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/** Records pass/fail plus the scenario's measurements into its {@link ExperimentBase}. */
public final class ScenarioRecorder implements TestWatcher {

    @Override
    public void testSuccessful(ExtensionContext ctx) {
        record(ctx, "PASS", null);
    }

    @Override
    public void testFailed(ExtensionContext ctx, Throwable cause) {
        record(ctx, "FAIL", cause);
    }

    @Override
    public void testAborted(ExtensionContext ctx, Throwable cause) {
        record(ctx, "ABORTED", cause);
    }

    @Override
    public void testDisabled(ExtensionContext ctx, Optional<String> reason) {
        // not recorded
    }

    private static void record(ExtensionContext ctx, String outcome, Throwable cause) {
        Object instance = ctx.getTestInstance().orElse(null);
        if (!(instance instanceof ExperimentBase base)) {
            return;
        }
        Scenario s = ctx.getRequiredTestMethod().getAnnotation(Scenario.class);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", s == null ? ctx.getRequiredTestMethod().getName() : s.id());
        r.put("name", s == null ? ctx.getDisplayName() : s.name());
        r.put("tests", s == null ? List.of() : List.of(s.tests()));
        r.put("counted", s == null || s.counted());
        r.put("outcome", outcome);
        r.put("pass", "PASS".equals(outcome));
        if (cause != null) {
            r.put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            Throwable root = cause;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            if (root != cause) {
                r.put("rootCause", root.getClass().getName() + ": " + root.getMessage());
            }
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            String trace = sw.toString();
            r.put("trace", trace.length() > 2500 ? trace.substring(0, 2500) : trace);
        }
        r.put("measurements", new LinkedHashMap<>(base.m));
        base.scenarios.add(r);
    }
}
