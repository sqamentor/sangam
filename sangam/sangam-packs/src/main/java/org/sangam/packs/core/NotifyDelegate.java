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
 * tenant's DLT registry and the URL whitelist. The operationId is derived from the case, template and case version,
 * so a retried step never sends twice ({@code NOTIFICATION_SUPPRESSED_DUPLICATE} downstream). A failed notification
 * never fails the business step (J-P2-E08): it is recorded as {@code NOTIFICATION_FAILED}.
 *
 * <p>Fields: {@code channel} (sms, email, inapp; comma list allowed), {@code template}, {@code recipientVar}
 * (variable holding a seat, person or mobile/e-mail reference; default {@code applicantRef}).
 */
public class NotifyDelegate implements JavaDelegate, ExecutionListener {

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
        String opId = Vars.operationId(ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId()
                : ex.getProcessBusinessKey(), "notify:" + channel + ":" + template + ":" + recipient + ":"
                + ex.getCurrentActivityId(), version);
        Map<String, Object> payload = Vars.map("template", template, "recipient", recipient,
                "language", Vars.str(ex, "preferredLanguage", "hi,en"), "caseState", Vars.str(ex, Vars.CASE_STATE),
                "reference", ex.getProcessBusinessKey());
        try {
            ConnectorResult r = gw.submit(new ConnectorRequest(Vars.tenant(ex), connector, "send", opId,
                    ex.getProcessBusinessKey(), ex.getProcessInstanceId(), Vars.withDirective(ex, connector, payload),
                    Vars.hash(payload)));
            Vars.audit(ex, r.status() == OperationStatus.FAILED ? "NOTIFICATION_FAILED" : "NOTIFICATION_QUEUED",
                    Vars.map("channel", channel, "template", template, "recipient", recipient,
                            "reason", r.reasonCode()));
        } catch (RuntimeException e) {
            Vars.audit(ex, "NOTIFICATION_FAILED", Vars.map("channel", channel, "template", template,
                    "error", e.getClass().getSimpleName()));
        }
    }
}
