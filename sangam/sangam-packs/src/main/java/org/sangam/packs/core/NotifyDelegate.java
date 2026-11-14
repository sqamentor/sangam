// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.Map;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.PackRuntime;

/**
 * Notification at a step (GP-33, R60, G-DLT / G-MAIL): SMS on a DLT-registered template, official e-mail, or in-app.
 * The connector ({@code notify.sms}, {@code notify.email}, {@code notify.inapp}) validates the template against the
 * tenant's DLT registry and the URL whitelist. The operationId is derived from the case, template, recipient, step and
 * case version. Audit events (X.4.0.3 Notifications, decision 299):
 * <ul>
 *   <li>{@code NOTIFICATION_QUEUED}: the gateway accepted the notification (not failed);</li>
 *   <li>{@code NOTIFICATION_SENT}: in addition, the gateway confirmed it sent ({@code COMPLETED}); a notification
 *       accepted for later delivery ({@code PENDING}) stays queued here and its outcome comes back through the
 *       integration layer;</li>
 *   <li>{@code NOTIFICATION_SUPPRESSED_DUPLICATE}: the same operationId was already accepted in this case (the same
 *       step, template and recipient at the same case version, for example a repeated SLA timer), so it is not
 *       submitted again; the case keeps the time of the first acceptance in variable
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
        for (String ch : Vars.csv(Vars.field(channel, ex, "inapp"))) {
            send(ex, ch, tpl, recipient, PackRuntime.gateway());
        }
    }

    /** Sends one notification; never throws for a delivery problem. */
    public static void send(DelegateExecution ex, String channel, String template, String recipient,
                            ConnectorGateway gw) {
        String connector = "notify." + channel;
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
            ConnectorResult r = gw.submit(new ConnectorRequest(Vars.tenant(ex), connector, "send", opId,
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
                Vars.audit(ex, "NOTIFICATION_SENT", Vars.map("channel", channel, "template", template,
                        "recipient", recipient, "operationId", opId, "externalRef", r.externalRef()));
            }
        } catch (RuntimeException e) {
            Vars.audit(ex, "NOTIFICATION_FAILED", Vars.map("channel", channel, "template", template,
                    "error", e.getClass().getSimpleName()));
        }
    }
}
