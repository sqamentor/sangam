// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.launcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.integration.sim.Fault;
import org.sangam.integration.sim.FaultInjector;

/**
 * The fault script of the simulator image ({@code SANGAM_SIM_FAULTS} at start, {@code POST /__sim/faults} at run time;
 * owner decision 184), a JSON form of {@link FaultInjector}:
 * <pre>{@code
 * {"clear": true,
 *  "faults": [
 *    {"mode": "once",   "kind": "DROP_RESPONSE", "route": "get-jit-service-data"},
 *    {"mode": "once",   "kind": "ERROR", "status": 503, "times": 2},
 *    {"mode": "always", "kind": "DELAY", "delayMillis": 1500}
 *  ]}
 * }</pre>
 * {@code clear} (default false) removes every fault first. {@code mode} is {@code once} (consumed by the next matching
 * request, {@code times} copies, default 1) or {@code always} (until cleared). {@code kind} is a {@link Fault.Kind};
 * {@code ERROR} needs {@code status} 400..599, {@code DELAY} needs {@code delayMillis} 1..600000. {@code route}
 * narrows the fault to route names containing it. Unknown members are refused, so a typing error is not a silent no-op.
 */
public record FaultScript(boolean clear, List<Entry> faults) {

    /** One fault line of the script. */
    public record Entry(boolean always, int times, Fault fault) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TOP = List.of("clear", "faults");
    private static final List<String> MEMBERS = List.of("mode", "times", "kind", "delayMillis", "status", "route");
    private static final int MAX_TIMES = 10_000;
    private static final long MAX_DELAY_MS = 600_000;

    public FaultScript {
        faults = List.copyOf(faults);
    }

    /** Parses a script; any shape or value error is an {@link IllegalArgumentException} naming the member. */
    public static FaultScript parse(byte[] json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("the fault script is not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("the fault script must be a JSON object");
        }
        root.fieldNames().forEachRemaining(n -> {
            if (!TOP.contains(n)) {
                throw new IllegalArgumentException("unknown member " + n + " (known: " + TOP + ")");
            }
        });
        JsonNode clear = root.path("clear");
        if (!clear.isMissingNode() && !clear.isBoolean()) {
            throw new IllegalArgumentException("clear must be true or false");
        }
        JsonNode list = root.path("faults");
        if (!list.isMissingNode() && !list.isArray()) {
            throw new IllegalArgumentException("faults must be an array");
        }
        List<Entry> entries = new ArrayList<>();
        int i = 0;
        for (JsonNode f : list) {
            entries.add(entry(f, i++));
        }
        return new FaultScript(clear.asBoolean(false), entries);
    }

    private static Entry entry(JsonNode f, int index) {
        String at = "faults[" + index + "]";
        if (!f.isObject()) {
            throw new IllegalArgumentException(at + " must be an object");
        }
        f.fieldNames().forEachRemaining(n -> {
            if (!MEMBERS.contains(n)) {
                throw new IllegalArgumentException(at + ": unknown member " + n + " (known: " + MEMBERS + ")");
            }
        });
        String mode = text(f, "mode", at, "once").toLowerCase(Locale.ROOT);
        if (!mode.equals("once") && !mode.equals("always")) {
            throw new IllegalArgumentException(at + ".mode must be once or always");
        }
        Fault.Kind kind;
        try {
            kind = Fault.Kind.valueOf(text(f, "kind", at, null));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(at + ".kind must be one of " + List.of(Fault.Kind.values()));
        }
        long times = number(f, "times", at, 1);
        if (times < 1 || times > MAX_TIMES) {
            throw new IllegalArgumentException(at + ".times must be 1.." + MAX_TIMES);
        }
        if (mode.equals("always") && f.has("times")) {
            throw new IllegalArgumentException(at + ".times applies to mode once only");
        }
        Fault fault = switch (kind) {
            case DELAY -> {
                long ms = number(f, "delayMillis", at, -1);
                if (ms < 1 || ms > MAX_DELAY_MS) {
                    throw new IllegalArgumentException(at + ".delayMillis must be 1.." + MAX_DELAY_MS);
                }
                yield new Fault(Fault.Kind.DELAY, ms, 0, null);
            }
            case ERROR -> {
                long status = number(f, "status", at, -1);
                if (status < 400 || status > 599) {
                    throw new IllegalArgumentException(at + ".status must be 400..599");
                }
                yield Fault.error((int) status);
            }
            case DROP_RESPONSE -> Fault.dropResponse();
            case DUPLICATE -> Fault.duplicate();
        };
        if ((kind != Fault.Kind.DELAY && f.has("delayMillis")) || (kind != Fault.Kind.ERROR && f.has("status"))) {
            throw new IllegalArgumentException(at + ": delayMillis is for DELAY and status for ERROR only");
        }
        String route = text(f, "route", at, null);
        if (route != null) {
            if (route.isBlank()) {
                throw new IllegalArgumentException(at + ".route must not be blank");
            }
            fault = fault.onRoute(route);
        }
        return new Entry(mode.equals("always"), (int) times, fault);
    }

    /** Applies the script to {@code injector}: clear first when asked, then add every fault in order. */
    public void applyTo(FaultInjector injector) {
        if (clear) {
            injector.clear();
        }
        for (Entry e : faults) {
            if (e.always()) {
                injector.always(e.fault());
            } else {
                injector.times(e.times(), e.fault());
            }
        }
    }

    private static String text(JsonNode f, String name, String at, String fallback) {
        JsonNode n = f.get(name);
        if (n == null) {
            return fallback;
        }
        if (!n.isTextual()) {
            throw new IllegalArgumentException(at + "." + name + " must be text");
        }
        return n.asText();
    }

    private static long number(JsonNode f, String name, String at, long fallback) {
        JsonNode n = f.get(name);
        if (n == null) {
            return fallback;
        }
        if (!n.isIntegralNumber() || !n.canConvertToLong()) {
            throw new IllegalArgumentException(at + "." + name + " must be a whole number");
        }
        return n.asLong();
    }
}
