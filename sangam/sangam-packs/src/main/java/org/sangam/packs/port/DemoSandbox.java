// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.packs.sim.SimulatorConnectorGateway;

/**
 * Demo sandbox tenants ({@code sangam.demo.sandbox-tenants}, owner option (b) 2026-11-19). A narrow, per-tenant
 * exception that is allowed while the installation runs in production mode: for a listed tenant only,
 * <ul>
 *   <li>the verified-payee port answers from the simulator ({@link #PAYEES}: {@code SIM-PV-<key>}), whatever adapter
 *       is installed;</li>
 *   <li>the payment rails ({@link #PAYMENT_CONNECTORS}) are answered by an in-process simulator
 *       ({@link #PAYMENTS}), and so is the statement those payments are matched against
 *       ({@link #STATEMENT_CONNECTORS}); every other connector goes to the installed gateway unchanged;</li>
 *   <li>every pack audit event of the tenant carries {@code sandbox=true} ({@code Vars.audit}).</li>
 * </ul>
 * Tenants not listed get exactly the behaviour without this class. Both simulators check the tenant on every call and
 * refuse any tenant that is not listed, so calling them directly for another tenant pays nothing and verifies nobody
 * (fail closed). No production guard is relaxed: the production flag, the pack simulator fallback and every server
 * guard stay as they are. The empty set (the default) switches the whole mechanism off.
 */
public final class DemoSandbox {

    /** The audit detail key every sandbox tenant's pack event carries. */
    public static final String AUDIT_KEY = "sandbox";
    /** Reason code of a sandbox simulator call for a tenant that is not a sandbox tenant. */
    public static final String REFUSED = "SANDBOX_TENANT_REFUSED";
    /** The payment rails the sandbox simulator answers for a sandbox tenant. */
    public static final Set<String> PAYMENT_CONNECTORS = Set.of("pfms.payment", "ifms.payment", "pfms.dbt",
            "bank.transfer");
    /**
     * The statement the core execution matches a payment against (reconcileConnector default). A real statement never
     * shows a simulated payment, so for a sandbox tenant the simulator that made the payment answers it.
     */
    public static final Set<String> STATEMENT_CONNECTORS = Set.of("bank.statement");

    private static volatile Set<String> tenants = Set.of();

    /** The sandbox verified-payee lookup: the simulator's answer, for sandbox tenants only. */
    public static final VerifiedPayeeLookup PAYEES = (tenant, employeeId, payeeId, asOf) -> {
        if (!isSandbox(tenant)) {
            throw new IllegalStateException(REFUSED + ": tenant '" + tenant + "' is not a demo sandbox tenant");
        }
        return VerifiedPayees.SIMULATOR.resolve(tenant, employeeId, payeeId, asOf);
    };

    /** The sandbox payment simulator, for sandbox tenants only. */
    public static final ConnectorGateway PAYMENTS = new SandboxPayments(new SimulatorConnectorGateway());

    private DemoSandbox() {
    }

    /** Sets the sandbox tenants (null or empty: none). Blank ids are refused. */
    public static void install(Collection<String> ids) {
        Set<String> s = new TreeSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("sangam.demo.sandbox-tenants: a tenant id is blank");
                }
                s.add(id.trim());
            }
        }
        tenants = Set.copyOf(s);
    }

    /** The sandbox tenants, sorted; empty by default. */
    public static Set<String> tenants() {
        return new TreeSet<>(tenants);
    }

    public static boolean active() {
        return !tenants.isEmpty();
    }

    public static boolean isSandbox(String tenant) {
        return tenant != null && tenants.contains(tenant);
    }

    /** The gateway the packs use: payment rails of a sandbox tenant go to {@link #PAYMENTS}, all else to {@code real}. */
    public static ConnectorGateway routing(ConnectorGateway real) {
        return active() ? new Routing(real) : real;
    }

    private static boolean sandboxPayment(String tenant, String connector) {
        return isSandbox(tenant) && (PAYMENT_CONNECTORS.contains(connector) || STATEMENT_CONNECTORS.contains(connector));
    }

    private record Routing(ConnectorGateway real) implements ConnectorGateway {
        @Override
        public ConnectorResult query(ConnectorRequest r) {
            return sandboxPayment(r.tenantId(), r.connector()) ? PAYMENTS.query(r) : real.query(r);
        }

        @Override
        public ConnectorResult submit(ConnectorRequest r) {
            return sandboxPayment(r.tenantId(), r.connector()) ? PAYMENTS.submit(r) : real.submit(r);
        }

        @Override
        public ConnectorResult lookup(String tenantId, String connector, String operationId) {
            return sandboxPayment(tenantId, connector) ? PAYMENTS.lookup(tenantId, connector, operationId)
                    : real.lookup(tenantId, connector, operationId);
        }

        @Override
        public ConnectorResult cancel(String tenantId, String connector, String operationId, String reason) {
            return sandboxPayment(tenantId, connector) ? PAYMENTS.cancel(tenantId, connector, operationId, reason)
                    : real.cancel(tenantId, connector, operationId, reason);
        }

        @Override
        public String openReconciliation(ReconciliationRequest r) {
            return sandboxPayment(r.tenantId(), r.connector()) ? PAYMENTS.openReconciliation(r)
                    : real.openReconciliation(r);
        }

        @Override
        public CapabilityClass capabilityOf(String connector) {
            return real.capabilityOf(connector);
        }
    }

    /** The simulator behind a tenant check on every call. */
    private record SandboxPayments(SimulatorConnectorGateway sim) implements ConnectorGateway {
        private static ConnectorResult refused(String tenant) {
            return ConnectorResult.failed(REFUSED, "tenant '" + tenant + "' is not a demo sandbox tenant");
        }

        private static ConnectorResult marked(ConnectorResult r) {
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>(r.data());
            data.put(AUDIT_KEY, true);
            return new ConnectorResult(r.status(), r.reasonCode(), r.message(), r.externalRef(), data);
        }

        @Override
        public ConnectorResult query(ConnectorRequest r) {
            return isSandbox(r.tenantId()) ? marked(sim.query(r)) : refused(r.tenantId());
        }

        @Override
        public ConnectorResult submit(ConnectorRequest r) {
            return isSandbox(r.tenantId()) ? marked(sim.submit(r)) : refused(r.tenantId());
        }

        @Override
        public ConnectorResult lookup(String tenantId, String connector, String operationId) {
            return isSandbox(tenantId) ? marked(sim.lookup(tenantId, connector, operationId)) : refused(tenantId);
        }

        @Override
        public ConnectorResult cancel(String tenantId, String connector, String operationId, String reason) {
            return isSandbox(tenantId) ? marked(sim.cancel(tenantId, connector, operationId, reason))
                    : refused(tenantId);
        }

        @Override
        public String openReconciliation(ReconciliationRequest r) {
            if (!isSandbox(r.tenantId())) {
                throw new IllegalStateException(REFUSED + ": tenant '" + r.tenantId() + "' is not a demo sandbox tenant");
            }
            return sim.openReconciliation(r);
        }

        @Override
        public CapabilityClass capabilityOf(String connector) {
            return sim.capabilityOf(connector);
        }
    }
}
