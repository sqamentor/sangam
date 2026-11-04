// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.history;

import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.history.event.HistoricCaseInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricTaskInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.sangam.core.layer.Outbox;
import org.sangam.ports.links.InstanceLinkService;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Json;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ports' history stage, registered in {@code customHistoryEventHandlers} next to Operaton's DB handler and next
 * to WS-ENGINE's {@code org.sangam.engine.history.PortsHistoryEventHandler} (audit chain R42, typed events to
 * {@code EngineEventSink}, CMMN outcome projection D-25). This handler adds only what the engine module does not:
 * <ol>
 *   <li><b>Read-model feed (FC-25 projection, FC-20).</b> Optional (null outbox = off). Events of one command are buffered and, when the engine
 *       transaction commits ({@code COMMITTING}), appended to the {@link Outbox} as one row per ordering root with
 *       destination {@code history-projector} (plus any extra destinations, e.g. analytics). The relay delivers them
 *       to {@link HistoryProjector}, which builds the CQRS read model. Because the row is in the engine transaction,
 *       the stream never contains an event whose command rolled back.</li>
 *   <li><b>Typed event bus (FC-43).</b> Every event is dispatched to {@link EngineEventBus} synchronously.</li>
 *   <li><b>Entity links (FC-32).</b> Process/case start events with a super instance and task creation write
 *       {@code child} links (with root) into {@code INSTANCE_LINK} in the same transaction.</li>
 * </ol>
 */
public class PortsHistoryEventHandler implements HistoryEventHandler {

    public static final String DESTINATION = "history-projector";
    public static final String EVENT_TYPE = "sangam.history.batch";

    private final Outbox outbox;
    private final List<Outbox.Destination> destinations;
    private final EngineEventBus bus;
    private final InstanceLinkService links;
    private final boolean stream;

    private static final ThreadLocal<Buffer> BUFFER = new ThreadLocal<>();

    private static final class Buffer {
        final CommandContext cc;
        final Map<String, List<Map<String, Object>>> byRoot = new LinkedHashMap<>();
        final Map<String, String> tenantByRoot = new LinkedHashMap<>();

        Buffer(CommandContext cc) {
            this.cc = cc;
        }
    }

    /**
     * @param outboxOrNull     null disables the async stream
     * @param extraDestinations further ordered destinations besides {@link #DESTINATION}
     */
    public PortsHistoryEventHandler(Outbox outboxOrNull, List<String> extraDestinations, EngineEventBus bus,
                                     InstanceLinkService linksOrNull) {
        this.outbox = outboxOrNull;
        this.stream = outboxOrNull != null;
        List<Outbox.Destination> d = new ArrayList<>();
        d.add(Outbox.Destination.ordered(DESTINATION));
        if (extraDestinations != null) {
            extraDestinations.forEach(x -> d.add(Outbox.Destination.ordered(x)));
        }
        this.destinations = List.copyOf(d);
        this.bus = bus;
        this.links = linksOrNull;
    }

    @Override
    public void handleEvent(HistoryEvent e) {
        Map<String, Object> m = HistoryEventMapper.toMap(e);
        if (bus != null && bus.hasListeners()) {
            String kind = (String) m.get("kind");
            bus.dispatch(new EngineEventBus.EngineEvent(kind + "." + e.getEventType(), (String) m.get("tenantId"),
                    e.getProcessInstanceId(), e.getCaseInstanceId(), (String) m.getOrDefault("activityId", m.get("caseActivityId")),
                    (String) m.get("taskId"), m));
        }
        if (links != null && EngineTx.inCommand()) {
            recordLinks(e);
        }
        if (stream && EngineTx.inCommand()) {
            buffer(e, m);
        }
    }

    @Override
    public void handleEvents(List<HistoryEvent> events) {
        for (HistoryEvent e : events) {
            handleEvent(e);
        }
    }

    private void buffer(HistoryEvent e, Map<String, Object> m) {
        CommandContext cc = Context.getCommandContext();
        Buffer b = BUFFER.get();
        if (b == null || b.cc != cc) {
            Buffer nb = new Buffer(cc);
            BUFFER.set(nb);
            cc.getTransactionContext().addTransactionListener(TransactionState.COMMITTING, ctx -> flush(nb));
            cc.getTransactionContext().addTransactionListener(TransactionState.ROLLED_BACK, ctx -> clear(nb));
            b = nb;
        }
        String root = HistoryEventMapper.root(e);
        b.byRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(m);
        b.tenantByRoot.putIfAbsent(root, (String) m.get("tenantId"));
    }

    private void flush(Buffer b) {
        try {
            Connection c = b.cc.getDbSqlSession().getSqlSession().getConnection();
            for (Map.Entry<String, List<Map<String, Object>>> en : b.byRoot.entrySet()) {
                String tenant = b.tenantByRoot.get(en.getKey());
                outbox.append(c, tenant == null ? "" : tenant, en.getKey(), EVENT_TYPE, Json.write(en.getValue()), destinations);
            }
        } finally {
            clear(b);
        }
    }

    private static void clear(Buffer b) {
        if (BUFFER.get() == b) {
            BUFFER.remove();
        }
    }

    private void recordLinks(HistoryEvent e) {
        Connection c = EngineTx.connection();
        if (e instanceof HistoricProcessInstanceEventEntity p && "start".equals(e.getEventType())) {
            String root = p.getRootProcessInstanceId();
            if (p.getSuperProcessInstanceId() != null) {
                links.link(c, p.getTenantId(), "child", hierarchy(root, p.getSuperProcessInstanceId()), "process",
                        p.getSuperProcessInstanceId(), "process", p.getProcessInstanceId(), "process", root, p.getStartUserId());
            }
            if (p.getSuperCaseInstanceId() != null) {
                links.link(c, p.getTenantId(), "child", "parent", "case", p.getSuperCaseInstanceId(), "process",
                        p.getProcessInstanceId(), "case", p.getSuperCaseInstanceId(), p.getStartUserId());
            }
        } else if (e instanceof HistoricCaseInstanceEventEntity ci && "create".equals(e.getEventType())) {
            if (ci.getSuperCaseInstanceId() != null) {
                links.link(c, ci.getTenantId(), "child", "parent", "case", ci.getSuperCaseInstanceId(), "case",
                        ci.getCaseInstanceId(), "case", ci.getSuperCaseInstanceId(), ci.getCreateUserId());
            }
            if (ci.getSuperProcessInstanceId() != null) {
                links.link(c, ci.getTenantId(), "child", "parent", "process", ci.getSuperProcessInstanceId(), "case",
                        ci.getCaseInstanceId(), "process", ci.getSuperProcessInstanceId(), ci.getCreateUserId());
            }
        } else if (e instanceof HistoricTaskInstanceEventEntity t && "create".equals(e.getEventType())) {
            if (t.getProcessInstanceId() != null) {
                links.link(c, t.getTenantId(), "child", "task", "process", t.getProcessInstanceId(), "task", t.getTaskId(),
                        "process", t.getRootProcessInstanceId(), null);
            } else if (t.getCaseInstanceId() != null) {
                links.link(c, t.getTenantId(), "child", "task", "case", t.getCaseInstanceId(), "task", t.getTaskId(),
                        "case", t.getCaseInstanceId(), null);
            }
            if (t.getParentTaskId() != null) {
                links.link(c, t.getTenantId(), "child", "sub-task", "task", t.getParentTaskId(), "task", t.getTaskId(),
                        null, null, null);
            }
        }
    }

    private static String hierarchy(String root, String parent) {
        return root != null && root.equals(parent) ? "root" : "parent";
    }
}
