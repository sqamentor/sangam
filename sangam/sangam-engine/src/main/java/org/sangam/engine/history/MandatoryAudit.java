// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.operaton.bpm.engine.impl.history.event.HistoryEventType;
import org.operaton.bpm.engine.impl.history.event.HistoryEventTypes;

/**
 * M-012 (X.2.3, T62): the per-thread hand-over between {@link MandatoryAuditHistoryLevel} and the code that receives
 * the event it let through. Operaton asks the history level <b>immediately</b> before it creates an event, with the
 * runtime entity ({@code if (level.isHistoryEventProduced(type, entity)) HistoryEventProcessor.processHistoryEvents(..)}
 * at every call site of the class-B event types; checked with javap on Operaton 2.2.0). For a class-B type the level
 * records, per type name, whether the event exists <b>only</b> because the audit is mandatory (the configured level
 * said no). {@link MandatoryAuditHistoryEventProducer} peeks at the record while it creates the event, and
 * {@link MandatoryAuditHistoryEventHandler} consumes it when the event arrives, so such an event reaches the audit
 * chain and nothing else.
 *
 * <p>An event that arrives without a record (not asked through the level) is handled exactly as before.
 */
public final class MandatoryAudit {

    private static final ThreadLocal<Map<String, Boolean>> PENDING = ThreadLocal.withInitial(HashMap::new);

    private MandatoryAudit() {
    }

    /** Operaton {@code HistoryEventTypes} name of a type ({@code PROCESS_INSTANCE_START}, ...). */
    public static String name(HistoryEventType type) {
        if (type instanceof HistoryEventTypes t) {
            return t.name();
        }
        return (type.getEntityType() + "_" + type.getEventName()).replace('-', '_').toUpperCase(Locale.ROOT);
    }

    static void record(String typeName, boolean auditOnly) {
        PENDING.get().put(typeName, auditOnly);
    }

    /** Whether the event of this type now being created exists only for the mandatory audit; the record stays. */
    static boolean peek(String typeName) {
        return Boolean.TRUE.equals(PENDING.get().get(typeName));
    }

    /** Whether the event of this type that just arrived exists only for the mandatory audit; removes the record. */
    static boolean consume(String typeName) {
        Map<String, Boolean> m = PENDING.get();
        Boolean v = m.remove(typeName);
        if (m.isEmpty()) {
            PENDING.remove();
        }
        return Boolean.TRUE.equals(v);
    }

    /** Test support: forgets every record of this thread. */
    static void clear() {
        PENDING.remove();
    }
}
