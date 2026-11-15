// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;

/**
 * Notification at a step (GP-33, R60, G-DLT / G-MAIL): SMS on a DLT-registered template, official e-mail, or in-app.
 * The connector ({@code notify.sms}, {@code notify.email}, {@code notify.inapp}) validates the template against the
 * tenant's DLT registry and the URL whitelist.
 *
 * <p>Channel lists (decision 312): a list such as {@code inapp,email} is split in {@link #send}, the one entry point
 * of every caller (this delegate, SLA warnings and escalations, FYI copies), and one notification is submitted per
 * channel, each to its own connector, with its own operationId and its own events. Blank entries are ignored; a
 * channel named twice in one list has the same operationId the second time and is suppressed as a duplicate; a list
 * that names no channel sends nothing and is recorded as {@code NOTIFICATION_FAILED} with reason {@value #NO_CHANNEL}.
 * The operationId is derived from the case, channel, template, recipient, step and case version.
 *
 * <p>Audit events (X.4.0.3 Notifications, decisions 299 and 313):
 * <ul>
 *   <li>{@code NOTIFICATION_QUEUED}: the gateway accepted the notification (not failed);</li>
 *   <li>{@code NOTIFICATION_SENT}: written once per notification, when the connector call completed successfully.
 *       A gateway that answers the submit itself with {@code COMPLETED} (the simulator, a connector with no binding)
 *       has no later result, so the event is written here. A gateway that queues the call answers {@code PENDING}:
 *       queueing alone writes nothing, and the event is written by the integration result path when the operation
 *       settles as {@code COMPLETED} (sangam-integration {@code NotificationSettlementAudit}, from
 *       {@link #sentLater}); a failed or cancelled operation writes none;</li>
 *   <li>{@code NOTIFICATION_SUPPRESSED_DUPLICATE}: the same operationId was already accepted in this case (the same
 *       step, channel, template and recipient at the same case version, for example a repeated SLA timer), so it is
 *       not submitted again; the case keeps the time of the first acceptance in variable
 *       {@code notified_<operationId>};</li>
 *   <li>{@code NOTIFICATION_FAILED}: the gateway refused it or threw; a failed notification never fails the business
 *       step (J-P2-E08) and is not recorded as sent, so a repeat goes to the gateway again.</li>
 * </ul>
 *
 * <p>Fields: {@code channel} (sms, email, inapp; comma list allowed), {@code template}, {@code recipientVar}
 * (variable holding a seat, person or mobile/e-mail reference; default {@code applicantRef}).
 */
public class NotifyDelegate implements JavaDelegate, ExecutionListener {

    /** Prefix of the case variable recording an accepted notification operation (decision 299). */
    public static final String NOTIFIED_PREFIX = "notified_";
    /** Prefix of the notification connectors: {@code notify.<channel>}. */
    public static final String CONNECTOR_PREFIX = "notify.";
    /** The connector operation of a notification. */
    public static final String OPERATION = "send";
    /** The event written once per notification whose connector call completed successfully (decision 313). */
    public static final String SENT = "NOTIFICATION_SENT";
    /** Reason of a notification whose channel list names no channel (decision 312). */
    public static final String NO_CHANNEL = "NO_CHANNEL";

    private Expression channel;
    private Expression template;
    private Expression recipientVar;

    @Override
    public void execute(DelegateExecution ex) {
        run(ex);
    }

    @Override
    public void notify(DelegateExecution ex) {
        run(ex);
    }

    private void run(DelegateExecution ex) {
        String tpl = Vars.field(template, ex, "generic");
        String rv = Vars.field(recipientVar, ex, "applicantRef");
        String recipient = Vars.str(ex, rv, Vars.str(ex, Vars.INITIATOR_PERSON));
        send(ex, Vars.field(channel, ex, "inapp"), tpl, recipient, PackRuntime.gateway());
    }

    /**
     * Sends one notification per channel of {@code channels} (one channel or a comma list, decision 312); never
     * throws for a delivery problem.
     */
    public static void send(DelegateExecution ex, String channels, String template, String recipient,
                            ConnectorGateway gw) {
        List<String> list = Vars.csv(channels);
        if (list.isEmpty()) {
            Vars.audit(ex, "NOTIFICATION_FAILED", Vars.map("channel", channels, "template", template,
                    "recipient", recipient, "reason", NO_CHANNEL));
            return;
        }
        for (String one : list) {
            sendOne(ex, one, template, recipient, gw);
        }
    }

    private static void sendOne(DelegateExecution ex, String channel, String template, String recipient,
                                ConnectorGateway gw) {
        String connector = CONNECTOR_PREFIX + channel;
        int version = Vars.intVal(ex, Vars.CASE_VERSION, 1);
        String opId = Vars.operationId(Vars.tenant(ex), ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId()
                : ex.getProcessBusinessKey(), "notify:" + channel + ":" + template + ":" + recipient + ":"
                + ex.getCurrentActivityId(), version);
        Object first = ex.getVariable(NOTIFIED_PREFIX + opId);
        if (first != null) {
            Vars.audit(ex, "NOTIFICATION_SUPPRESSED_DUPLICATE", Vars.map("channel", channel, "template", template,
                    "recipient", recipient, "operationId", opId, "firstAcceptedAt", first.toString()));
            return;
        }
        Map<String, Object> payload = Vars.map("template", template, "recipient", recipient,
                "language", Vars.str(ex, "preferredLanguage", "hi,en"), "caseState", Vars.str(ex, Vars.CASE_STATE),
                "reference", ex.getProcessBusinessKey());
        try {
            ConnectorResult r = gw.submit(new ConnectorRequest(Vars.tenant(ex), connector, OPERATION, opId,
                    ex.getProcessBusinessKey(), ex.getProcessInstanceId(), Vars.withDirective(ex, connector, payload),
                    Vars.hash(payload)));
            if (r.status() == OperationStatus.FAILED) {
                Vars.audit(ex, "NOTIFICATION_FAILED", Vars.map("channel", channel, "template", template,
                        "recipient", recipient, "reason", r.reasonCode()));
                return;
            }
            ex.setVariable(NOTIFIED_PREFIX + opId, Vars.now().toString());
            Vars.audit(ex, "NOTIFICATION_QUEUED", Vars.map("channel", channel, "template", template,
                    "recipient", recipient, "reason", r.reasonCode()));
            if (r.status() == OperationStatus.COMPLETED) {
                // the gateway answered the submit itself: there is no later result, so this is the one writer
                Vars.audit(ex, SENT, sentDetails(channel, template, recipient, opId, r.externalRef()));
            }
        } catch (RuntimeException e) {
            Vars.audit(ex, "NOTIFICATION_FAILED", Vars.map("channel", channel, "template", template,
                    "error", e.getClass().getSimpleName()));
        }
    }

    /** The details of {@value #SENT}, the same whichever path writes it. */
    static Map<String, Object> sentDetails(String channel, String template, String recipient, String operationId,
                                           String externalRef) {
        return Vars.map("channel", channel, "template", template, "recipient", recipient, "operationId", operationId,
                "externalRef", externalRef);
    }

    /** Whether {@code connector} and {@code operation} name a notification submitted by {@link #send}. */
    public static boolean isNotification(String connector, String operation) {
        return connector != null && connector.startsWith(CONNECTOR_PREFIX)
                && connector.length() > CONNECTOR_PREFIX.length() && OPERATION.equals(operation);
    }

    /**
     * Decision 313: the {@value #SENT} event of a notification that was queued ({@code PENDING}) and whose connector
     * call completed successfully later. Built by the integration result path from the stored operation; the caller
     * writes it exactly once, in the transaction that settles the operation.
     *
     * @param connector        the notification connector ({@code notify.<channel>})
     * @param submittedPayload the payload submitted by {@link #send} (template, recipient)
     * @param settledBy        who settled the operation (the integration system, a reconciler or an officer)
     */
    public static PackAuditEvent sentLater(String tenantId, String businessKey, String processInstanceId,
                                           String connector, Map<String, Object> submittedPayload, String operationId,
                                           String externalRef, String settledBy, Instant at) {
        if (!isNotification(connector, OPERATION)) {
            throw new IllegalArgumentException("not a notification connector: " + connector);
        }
        Map<String, Object> p = submittedPayload == null ? Map.of() : submittedPayload;
        Map<String, Object> details = sentDetails(connector.substring(CONNECTOR_PREFIX.length()),
                p.get("template") == null ? null : p.get("template").toString(),
                p.get("recipient") == null ? null : p.get("recipient").toString(), operationId, externalRef);
        details.put("settledBy", settledBy);
        return new PackAuditEvent(SENT, tenantId, businessKey, processInstanceId, "system", null, null, null, null,
                null, operationId, details, at);
    }
}
