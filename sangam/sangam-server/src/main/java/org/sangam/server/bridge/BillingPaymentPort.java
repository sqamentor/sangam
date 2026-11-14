// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.sangam.billing.BillingService;
import org.sangam.billing.PaymentConfirmationPolicy;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.gov.PaymentAcknowledgement;
import org.springframework.core.env.Environment;

/**
 * {@code BillingService.PaymentPort} over the integration connectors (payment gateway, bank, counter, aggregator),
 * with the connector ledger's exactly-once identity. Configuration binding only (RM-02): the confirmation rule is
 * sangam-billing's {@link PaymentConfirmationPolicy}, and trusted channels are decided by {@code BillingService} from
 * the server configuration (decision 136), never here.
 * <ul>
 *   <li>confirm: the channel's connector ({@code sangam.billing.channels.<channel>.connector}) runs its verify operation
 *       ({@code .verify-operation}, default {@code verify}) keyed by the payment reference, so a repeated confirmation
 *       is one call. A completed call confirms only with a success status (the platform's one payment-status
 *       vocabulary, {@link PaymentAcknowledgement.Status#from}) and a parseable {@code amount}
 *       ({@link PaymentConfirmationPolicy#confirmedByRail}); any other result confirms nothing.</li>
 *   <li>refund: the channel's refund operation ({@code .refund-operation}, default {@code refund}) keyed by the refund
 *       operation id; returns the provider reference.</li>
 * </ul>
 * An unconfigured channel confirms nothing (null), so the collection is not recorded.
 */
public final class BillingPaymentPort implements BillingService.PaymentPort {

    private final ConnectorInvoker invoker;
    private final Environment env;

    public BillingPaymentPort(ConnectorInvoker invoker, Environment env) {
        this.invoker = invoker;
        this.env = env;
    }

    @Override
    public org.sangam.core.money.Money confirm(String tenant, String channel, String paymentRef, org.sangam.core.money.Money amount) {
        String connector = env.getProperty("sangam.billing.channels." + channel + ".connector");
        if (connector == null || invoker == null) {
            return null;
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("paymentRef", paymentRef);
        in.put("amount", amount.toPlainString());
        ConnectorInvoker.Outcome o = invoker.invoke(invocation(tenant, "billing:pay:" + paymentRef, "confirm", connector,
                env.getProperty("sangam.billing.channels." + channel + ".verify-operation", "verify"), in));
        if (!(o instanceof ConnectorInvoker.Completed c) || c.output() == null) {
            return null;
        }
        Object status = c.output().get("status");
        return PaymentConfirmationPolicy.confirmedByRail(
                PaymentAcknowledgement.Status.from(status == null ? null : status.toString())
                        == PaymentAcknowledgement.Status.SUCCESS,
                c.output().get("amount"), amount);
    }

    @Override
    public String refund(String tenant, String channel, String paymentRef, org.sangam.core.money.Money amount, String operationId) {
        String connector = env.getProperty("sangam.billing.channels." + channel + ".connector");
        if (connector == null || invoker == null) {
            return null;
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("paymentRef", paymentRef);
        in.put("amount", amount.toPlainString());
        in.put("refundRef", operationId);
        ConnectorInvoker.Outcome o = invoker.invoke(invocation(tenant, "billing:refund:" + operationId, "refund", connector,
                env.getProperty("sangam.billing.channels." + channel + ".refund-operation", "refund"), in));
        return switch (o) {
            case ConnectorInvoker.Completed c -> c.providerRef() != null ? c.providerRef() : c.operationId().toString();
            case ConnectorInvoker.Unknown u -> throw new IllegalStateException("refund outcome unknown; reconciliation item "
                    + u.reconciliationItemId());
            case ConnectorInvoker.Failed f -> throw new IllegalStateException("refund refused: " + f.code() + " " + f.message());
            case ConnectorInvoker.Retry r -> throw new IllegalStateException("refund to retry: " + r.message());
            case ConnectorInvoker.Stale s -> throw new IllegalStateException("refund stale: " + s.message());
        };
    }

    private static ConnectorInvoker.Invocation invocation(String tenant, String root, String activity, String connector,
                                                          String operation, Map<String, Object> input) {
        return new ConnectorInvoker.Invocation(tenant, ComplianceConnectors.id64("bill:", root),
                ComplianceConnectors.id64("bill:", activity), 0, null, null, connector, operation, input,
                UUID.randomUUID().toString(), 0L, Instant.now().plus(Duration.ofMinutes(2)),
                org.sangam.core.layer.OperationLedger.operationId(tenant, ComplianceConnectors.id64("bill:", root),
                        ComplianceConnectors.id64("bill:", activity), 0));
    }
}
