// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.sim;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.ReconciliationRequest;
import org.sangam.packs.port.VerifiedPayeeLookup;
import org.sangam.packs.port.VerifiedPayees;

/**
 * Demo sandbox tenants ({@code sangam.demo.sandbox-tenants}, owner option (b) 2026-11-19). A narrow, per-tenant
 * exception that is allowed while the installation runs in production mode: for a listed tenant only,
 * <ul>
 *   <li>the verified-payee port answers from the simulator ({@link #PAYEES}: {@code SIM-PV-<key>}), whatever adapter
 *       is installed;</li>
 *   <li>the payment rails ({@link #PAYMENT_CONNECTORS}) are answered by an in-process simulator
 *       ({@link #PAYMENTS}), and so is the statement those payments are matched against
 *       ({@link #STATEMENT_CONNECTORS}); every other connector goes to the installed gateway unchanged;</li>
 *   <li>the outside checks of the bill journey ({@link #CHECK_CONNECTORS}, register E-26, owner 2026-11-21) are
 *       answered by an in-process simulator ({@link #CHECKS}) from shipped data ({@value #CHECK_ANSWERS}); no
 *       connector of that list moves money;</li>
 *   <li>every pack audit event of the tenant carries {@code sandbox=true}, and one that names a connector the sandbox
 *       answers for the tenant carries {@code simulated=true} as well ({@code Vars.audit}), so a simulated answer
 *       never reads as the outside system's.</li>
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
    /**
     * Register E-26 (owner 2026-11-21, "Add bill journey"): the outside checks the bill journey needs that nothing
     * answers on the demo host, answered by the sandbox for a sandbox tenant. The smallest list with which the bill
     * cases of the presentation reach their targets (SandboxBillJourneyTest): {@code ifms.duplicateBill}, the duplicate
     * or similar claim check of the bill; {@code docs.issue}, the number of an issued document (payment advice,
     * sanction order, rejection order). Neither moves money. An id leaves the list when a real system answers it.
     */
    public static final Set<String> CHECK_CONNECTORS = Set.of("ifms.duplicateBill", "docs.issue");
    /** The sandbox's answers of {@link #CHECK_CONNECTORS}: one line per id, {@code <connector>=<simulator directive>}. */
    public static final String CHECK_ANSWERS = "org/sangam/packs/sim/sandbox-answers.properties";
    /** The audit detail key of an event that names a connector the sandbox answers for the event's tenant. */
    public static final String SIMULATED_KEY = "simulated";
    /** Reason code of a sandbox check simulator call for a connector that is not in {@link #CHECK_CONNECTORS}. */
    public static final String NOT_LISTED = "SANDBOX_CONNECTOR_NOT_LISTED";

    private static volatile Set<String> tenants = Set.of();

    /** The sandbox verified-payee lookup: the simulator's answer, for sandbox tenants only. */
    public static final VerifiedPayeeLookup PAYEES = (tenant, employeeId, payeeId, asOf) -> {
        if (!isSandbox(tenant)) {
            throw new IllegalStateException(REFUSED + ": tenant '" + tenant + "' is not a demo sandbox tenant");
        }
        return VerifiedPayees.SIMULATOR.resolve(tenant, employeeId, payeeId, asOf);
    };

    /** The sandbox payment simulator, for sandbox tenants only. */
    public static final ConnectorGateway PAYMENTS = new SandboxPayments(new SimulatorConnectorGateway(), null);

    /**
     * The sandbox check simulator, for sandbox tenants and the ids of {@link #CHECK_CONNECTORS} only: any other tenant
     * is refused with {@link #REFUSED}, any other id with {@link #NOT_LISTED}. It answers from {@link #CHECK_ANSWERS};
     * a directive the case carries itself ({@code sim_<connector>}) wins, as for the payment simulator.
     */
    public static final ConnectorGateway CHECKS = new SandboxPayments(
            new DemoAnswersGateway(new SimulatorConnectorGateway(), checkAnswers()), CHECK_CONNECTORS);

    /** The shipped answers of the check simulator: exactly one line per id of {@link #CHECK_CONNECTORS}. */
    public static java.util.Map<String, String> checkAnswers() {
        java.util.Map<String, String> answers = DemoAnswersGateway.answers(CHECK_ANSWERS);
        if (!answers.keySet().equals(CHECK_CONNECTORS)) {
            throw new IllegalStateException(CHECK_ANSWERS + " must answer exactly " + new TreeSet<>(CHECK_CONNECTORS)
                    + ", it answers " + answers.keySet());
        }
        return answers;
    }

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

    /**
     * The gateway the packs use: for a sandbox tenant the payment rails and the statement go to {@link #PAYMENTS} and
     * the listed checks to {@link #CHECKS}; every other connector, and every connector of any other tenant, goes to
     * {@code real} unchanged.
     */
    public static ConnectorGateway routing(ConnectorGateway real) {
        return active() ? new Routing(real) : real;
    }

    /** Whether the sandbox, not the installed gateway, answers {@code connector} for {@code tenant}. */
    public static boolean simulates(String tenant, String connector) {
        return simulator(tenant, connector) != null;
    }

    /** The sandbox simulator that answers {@code connector} for {@code tenant}; null: the installed gateway does. */
    private static ConnectorGateway simulator(String tenant, String connector) {
        if (!isSandbox(tenant) || connector == null) {
            return null;
        }
        if (PAYMENT_CONNECTORS.contains(connector) || STATEMENT_CONNECTORS.contains(connector)) {
            return PAYMENTS;
        }
        return CHECK_CONNECTORS.contains(connector) ? CHECKS : null;
    }

    private record Routing(ConnectorGateway real) implements ConnectorGateway {
        private ConnectorGateway of(String tenant, String connector) {
            ConnectorGateway sim = simulator(tenant, connector);
            return sim != null ? sim : real;
        }

        @Override
        public ConnectorResult query(ConnectorRequest r) {
            return of(r.tenantId(), r.connector()).query(r);
        }

        @Override
        public ConnectorResult submit(ConnectorRequest r) {
            return of(r.tenantId(), r.connector()).submit(r);
        }

        @Override
        public ConnectorResult lookup(String tenantId, String connector, String operationId) {
            return of(tenantId, connector).lookup(tenantId, connector, operationId);
        }

        @Override
        public ConnectorResult cancel(String tenantId, String connector, String operationId, String reason) {
            return of(tenantId, connector).cancel(tenantId, connector, operationId, reason);
        }

        @Override
        public String openReconciliation(ReconciliationRequest r) {
            return of(r.tenantId(), r.connector()).openReconciliation(r);
        }

        @Override
        public CapabilityClass capabilityOf(String connector) {
            return real.capabilityOf(connector);
        }
    }

    /**
     * A simulator behind a tenant check on every call, and behind a connector check when {@code only} is given: a
     * tenant that is not a sandbox tenant and a connector outside {@code only} are refused, never answered.
     */
    private record SandboxPayments(ConnectorGateway sim, Set<String> only) implements ConnectorGateway {
        private static ConnectorResult refused(String tenant) {
            return ConnectorResult.failed(REFUSED, "tenant '" + tenant + "' is not a demo sandbox tenant");
        }

        private boolean listed(String connector) {
            return only == null || only.contains(connector);
        }

        private static ConnectorResult notListed(String connector) {
            return ConnectorResult.failed(NOT_LISTED, "connector '" + connector + "' is not a sandbox check");
        }

        private static ConnectorResult marked(ConnectorResult r) {
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>(r.data());
            data.put(AUDIT_KEY, true);
            return new ConnectorResult(r.status(), r.reasonCode(), r.message(), r.externalRef(), data);
        }

        @Override
        public ConnectorResult query(ConnectorRequest r) {
            if (!isSandbox(r.tenantId())) {
                return refused(r.tenantId());
            }
            return listed(r.connector()) ? marked(sim.query(r)) : notListed(r.connector());
        }

        @Override
        public ConnectorResult submit(ConnectorRequest r) {
            if (!isSandbox(r.tenantId())) {
                return refused(r.tenantId());
            }
            return listed(r.connector()) ? marked(sim.submit(r)) : notListed(r.connector());
        }

        @Override
        public ConnectorResult lookup(String tenantId, String connector, String operationId) {
            if (!isSandbox(tenantId)) {
                return refused(tenantId);
            }
            return listed(connector) ? marked(sim.lookup(tenantId, connector, operationId)) : notListed(connector);
        }

        @Override
        public ConnectorResult cancel(String tenantId, String connector, String operationId, String reason) {
            if (!isSandbox(tenantId)) {
                return refused(tenantId);
            }
            return listed(connector) ? marked(sim.cancel(tenantId, connector, operationId, reason))
                    : notListed(connector);
        }

        @Override
        public String openReconciliation(ReconciliationRequest r) {
            if (!isSandbox(r.tenantId())) {
                throw new IllegalStateException(REFUSED + ": tenant '" + r.tenantId() + "' is not a demo sandbox tenant");
            }
            if (!listed(r.connector())) {
                throw new IllegalStateException(NOT_LISTED + ": connector '" + r.connector() + "' is not a sandbox check");
            }
            return sim.openReconciliation(r);
        }

        @Override
        public CapabilityClass capabilityOf(String connector) {
            return sim.capabilityOf(connector);
        }
    }
}
