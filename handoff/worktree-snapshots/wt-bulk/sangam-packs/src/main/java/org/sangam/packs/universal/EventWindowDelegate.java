// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.universal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.core.Vars;

/**
 * Windowed many-to-one event correlation with bounded state (UP-03, UP-21, R83). Events arrive as messages correlated
 * on the business key (device, card, shipment); each delivery carries {@code eventId}, {@code eventType},
 * {@code eventAt} (ISO instant) and {@code eventValue}. Field {@code op}:
 * <ul>
 *   <li>{@code ADD}: de-duplicates by {@code eventId}, drops events older than the window start (late-event policy
 *       {@code lateEventPolicy = DROP | ACCEPT}), keeps at most {@code maxEvents} (bounded state, R40);</li>
 *   <li>{@code EVALUATE}: {@code thresholdReached} when the in-window count reaches {@code threshold}
 *       or the summed value reaches {@code valueThreshold}.</li>
 * </ul>
 * State: {@code windowEvents} (JSON), {@code eventCount}, {@code duplicateCount}, {@code lateCount}.
 */
public class EventWindowDelegate implements JavaDelegate {

    private Expression op;
    private Expression maxEvents;

    @Override
    public void execute(DelegateExecution ex) {
        List<Map<String, Object>> events = Vars.jsonList(ex, "windowEvents");
        if ("EVALUATE".equals(Vars.field(op, ex, "ADD"))) {
            int threshold = Vars.intVal(ex, "threshold", 3);
            double sum = 0;
            for (Map<String, Object> e : events) {
                Object v = e.get("value");
                if (v != null) {
                    sum += Double.parseDouble(v.toString());
                }
            }
            Object vt = ex.getVariable("valueThreshold");
            boolean reached = events.size() >= threshold
                    || (vt != null && sum >= Double.parseDouble(vt.toString()));
            ex.setVariable("thresholdReached", reached);
            ex.setVariable("windowValueSum", sum);
            return;
        }
        if (ex.getVariable("windowStartedAt") == null) {
            ex.setVariable("windowStartedAt", java.util.Date.from(Vars.now()));
        }
        String id = Vars.str(ex, "eventId", Vars.now().toString());
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> e : events) {
            seen.add(String.valueOf(e.get("id")));
        }
        if (seen.contains(id)) {
            ex.setVariable("duplicateCount", Vars.intVal(ex, "duplicateCount", 0) + 1);
            return;
        }
        Instant at = Vars.str(ex, "eventAt") == null ? Vars.now() : Instant.parse(Vars.str(ex, "eventAt"));
        Instant windowStart = Vars.instant(ex, "windowStartedAt");
        if (windowStart != null && at.isBefore(windowStart)
                && !"ACCEPT".equals(Vars.str(ex, "lateEventPolicy", "DROP"))) {
            ex.setVariable("lateCount", Vars.intVal(ex, "lateCount", 0) + 1);
            return;
        }
        List<Map<String, Object>> next = new ArrayList<>(events);
        next.add(Vars.map("id", id, "type", Vars.str(ex, "eventType"), "at", at.toString(),
                "value", Vars.str(ex, "eventValue")));
        next.sort((a, b) -> String.valueOf(a.get("at")).compareTo(String.valueOf(b.get("at")))); // out-of-order
        int max = Vars.fieldInt(maxEvents, ex, 500);
        while (next.size() > max) {
            next.remove(0);
        }
        ex.setVariable("windowEvents", Vars.toJson(next));
        ex.setVariable("eventCount", next.size());
    }
}
