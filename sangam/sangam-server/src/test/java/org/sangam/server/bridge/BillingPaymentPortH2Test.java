// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.PendingAudit;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.money.Money;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.schema.IntegrationSchema;
import org.springframework.mock.env.MockEnvironment;

/**
 * RM-02: the server payment port is configuration binding over a real {@link ConnectorInvoker} (H2), and a rail
 * result confirms only with a success status and a parseable amount. An empty output, a status alone, an amount alone,
 * a failure status and an unconfigured channel all confirm nothing; trusted channels are not this class's concern
 * (sangam.billing.trusted-channels is never read here).
 */
class BillingPaymentPortH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t";

    /** A rail whose verify output is chosen by the payment reference. */
    static final class Rail implements SangamConnector {
        final Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("rail", "test", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, List.of("verify"),
                    ConnectorProfile.LIVE);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            Map<String, Object> out = outputs.getOrDefault(String.valueOf(request.input().get("paymentRef")), Map.of());
            return ConnectorResult.Success.of(out, "prov-" + request.operationId());
        }
    }

    static BillingPaymentPort port(Rail rail, MockEnvironment env) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            IntegrationSchema.create(c, D);
            PendingAudit.create(c, D);
        }
        AuditChain chain = new AuditChain(D);
        ConnectorRegistry registry = new ConnectorRegistry();
        registry.register(TENANT, rail);
        ConnectorInvoker invoker = new ConnectorInvoker(Transactions.of(ds),
                new OperationLedger(D, new ReconciliationQueue(D, chain)), new ConnectorCallJournal(D), registry, chain);
        return new BillingPaymentPort(invoker, env);
    }

    static Money inr(String s) {
        return Money.parse(s, "INR");
    }

    @Test
    void onlyASuccessStatusWithAnAmountConfirms() throws Exception {
        Rail rail = new Rail();
        rail.outputs.put("EMPTY", Map.of());
        rail.outputs.put("STATUS-ONLY", Map.of("status", "SUCCESS"));
        rail.outputs.put("AMOUNT-ONLY", Map.of("amount", "100.00"));
        rail.outputs.put("FAILED", Map.of("status", "FAILED", "amount", "100.00"));
        rail.outputs.put("PENDING", Map.of("status", "CREATED", "amount", "100.00"));
        rail.outputs.put("BAD-AMOUNT", Map.of("status", "PAID", "amount", "a hundred"));
        rail.outputs.put("PAID", Map.of("status", "PAID", "amount", "100.00"));
        rail.outputs.put("PARTIAL", Map.of("status", "SUCCESS", "amount", "60"));
        MockEnvironment env = new MockEnvironment().withProperty("sangam.billing.channels.gateway.connector", "rail")
                .withProperty("sangam.billing.trusted-channels", "gateway");   // ignored here: trust is BillingService's
        BillingPaymentPort p = port(rail, env);

        assertNull(p.confirm(TENANT, "gateway", "EMPTY", inr("100")), "empty connector output: not confirmed");
        assertNull(p.confirm(TENANT, "gateway", "STATUS-ONLY", inr("100")), "status without amount: not confirmed");
        assertNull(p.confirm(TENANT, "gateway", "AMOUNT-ONLY", inr("100")), "amount without status: not confirmed");
        assertNull(p.confirm(TENANT, "gateway", "FAILED", inr("100")));
        assertNull(p.confirm(TENANT, "gateway", "PENDING", inr("100")));
        assertNull(p.confirm(TENANT, "gateway", "BAD-AMOUNT", inr("100")));
        assertEquals(inr("100.00"), p.confirm(TENANT, "gateway", "PAID", inr("100")));
        assertEquals(inr("60"), p.confirm(TENANT, "gateway", "PARTIAL", inr("100")), "the rail's figure");
    }

    @Test
    void anUnconfiguredChannelConfirmsNothingEvenWhenListedAsTrusted() throws Exception {
        Rail rail = new Rail();
        rail.outputs.put("PAY-1", Map.of("status", "PAID", "amount", "10"));
        BillingPaymentPort p = port(rail, new MockEnvironment().withProperty("sangam.billing.trusted-channels", "cash"));
        assertNull(p.confirm(TENANT, "cash", "PAY-1", inr("10")), "the port never confirms as reported");
    }
}
