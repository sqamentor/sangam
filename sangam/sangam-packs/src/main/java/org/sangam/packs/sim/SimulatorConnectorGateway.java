// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.sim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.ReconciliationRequest;

/**
 * Deterministic simulator for every connector the packs use (ADR-D-13: build against public detail, simulate the
 * rest). WS-INTEGRATION replaces it with the real adapters; the simulator stays available as the {@code sim} profile.
 *
 * <h2>Failure injection</h2>
 * A delegate copies the process variable {@code sim_<connector with dots as underscores>} (for example
 * {@code sim_pfms_payment}) into the payload key {@value #DIRECTIVE}. Directives:
 * <ul>
 *   <li>{@code COMPLETED} (default), {@code FAILED:<REASON>}, {@code UNKNOWN}, {@code PENDING};</li>
 *   <li>{@code UNKNOWN_THEN_COMPLETED}, {@code UNKNOWN_THEN_FAILED:<REASON>}: the lookup / reconciliation finds the
 *       second outcome (J-P1-E04 payment succeeded but the response was lost);</li>
 *   <li>{@code PENDING_THEN_COMPLETED}, {@code PENDING_THEN_FAILED:<REASON>}: the status lookup after the
 *       acknowledgement window finds the second outcome;</li>
 *   <li>for queries, any {@code key=value} pairs separated by {@code ;} are returned as data
 *       (for example {@code available=310000} for J-P1-E11, {@code duplicate=true} for J-P1-E03).</li>
 * </ul>
 * Effects are idempotent per operationId: a repeat with the same payload hash returns the first result, a different
 * hash is refused with {@code IDEMPOTENCY_PAYLOAD_MISMATCH} (R41).
 */
public class SimulatorConnectorGateway implements ConnectorGateway {

    /** Payload key carrying the failure-injection directive. */
    public static final String DIRECTIVE = "_sim";

    private static final Map<String, CapabilityClass> CAPABILITY = new HashMap<>();

    static {
        // (c) irreversible money movement and dispatch with no reliable lookup: UNKNOWN_OUTCOME, no auto repeat.
        for (String c : List.of("pfms.payment", "ifms.payment", "pfms.dbt", "treasury.challan", "gem.order",
                "bank.transfer", "dak.dispatch", "rpa.bot", "saga.charge")) {
            CAPABILITY.put(c, CapabilityClass.C_NEITHER);
        }
        // (b) outcome lookup only: payment gateways with status query, SMS/e-mail delivery reports.
        for (String c : List.of("paygov.fee", "ntrp.fee", "grass.challan", "notify.sms", "notify.email",
                "esign.sign", "bank.statement", "court.limbs", "cppp.publish", "eproc.publish", "registry.update")) {
            CAPABILITY.put(c, CapabilityClass.B_LOOKUP_ONLY);
        }
        // (a) idempotent with lookup: document rendering, DigiLocker push by URI, archive transfer.
        for (String c : List.of("docs.issue", "docs.render", "digilocker.push", "archive.nai", "dsc.sign",
                "ai.extract", "provision.account", "inventory.reserve", "shipping.create")) {
            CAPABILITY.put(c, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP);
        }
    }

    private final Map<String, Stored> operations = new ConcurrentHashMap<>();
    private final Map<String, ReconciliationRequest> reconciliations = new ConcurrentHashMap<>();
    private final Map<String, String> seenBills = new ConcurrentHashMap<>();
    private final Map<String, String> seenBeneficiaries = new ConcurrentHashMap<>();
    private final List<ConnectorRequest> journal = java.util.Collections.synchronizedList(new ArrayList<>());

    private record Stored(String payloadHash, ConnectorResult first, String directive, String connector) {
    }

    @Override
    public ConnectorResult query(ConnectorRequest r) {
        journal.add(r);
        String directive = directive(r);
        Map<String, Object> data = new HashMap<>(defaultsFor(r));
        if (directive != null && directive.contains("=")) {
            for (String pair : directive.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    data.put(pair.substring(0, eq).trim(), coerce(pair.substring(eq + 1).trim()));
                }
            }
            return ConnectorResult.completed("SIM-Q-" + shortId(), data);
        }
        if (directive != null && directive.startsWith("FAILED")) {
            return ConnectorResult.failed(reason(directive), "simulated query failure");
        }
        if ("UNKNOWN".equals(directive)) {
            return ConnectorResult.unknown("simulated query timeout");
        }
        return ConnectorResult.completed("SIM-Q-" + shortId(), data);
    }

    @Override
    public ConnectorResult submit(ConnectorRequest r) {
        journal.add(r);
        if (r.operationId() == null || r.operationId().isBlank()) {
            return ConnectorResult.failed("OPERATION_ID_REQUIRED", "effects need an operationId (VI.3 rule 4)");
        }
        Stored existing = operations.get(r.operationId());
        if (existing != null) {
            if (existing.payloadHash() != null && r.payloadHash() != null
                    && !existing.payloadHash().equals(r.payloadHash())) {
                return ConnectorResult.failed("IDEMPOTENCY_PAYLOAD_MISMATCH",
                        "same operationId reused with a different payload (R41)");
            }
            return existing.first();
        }
        String directive = directive(r);
        ConnectorResult first = firstOutcome(r, directive);
        operations.put(r.operationId(), new Stored(r.payloadHash(), first, directive, r.connector()));
        return first;
    }

    @Override
    public ConnectorResult lookup(String tenantId, String connector, String operationId) {
        Stored s = operations.get(operationId);
        if (s == null) {
            return ConnectorResult.failed("NOT_FOUND", "no operation with this id reached the external system");
        }
        if (capabilityOf(connector) == CapabilityClass.C_NEITHER && s.first().status() == OperationStatus.UNKNOWN
                && (s.directive() == null || !s.directive().contains("_THEN_"))) {
            return ConnectorResult.unknown("class (c): no outcome lookup");
        }
        String d = s.directive();
        if (d != null && d.contains("_THEN_")) {
            String second = d.substring(d.indexOf("_THEN_") + 6);
            ConnectorResult resolved = second.startsWith("FAILED")
                    ? ConnectorResult.failed(reason(second), "found failed on lookup")
                    : ConnectorResult.completed("SIM-" + operationId.substring(0, 8), Map.of("lookedUp", true));
            operations.put(operationId, new Stored(s.payloadHash(), resolved, null, s.connector()));
            return resolved;
        }
        return s.first();
    }

    @Override
    public ConnectorResult cancel(String tenantId, String connector, String operationId, String reason) {
        Stored s = operations.get(operationId);
        if (s == null) {
            return new ConnectorResult(OperationStatus.CANCELLED, null, "nothing reached the external system", null,
                    Map.of());
        }
        if (s.first().status() == OperationStatus.COMPLETED) {
            return s.first(); // too late: the effect already happened (LATE_RESULT / refund path)
        }
        ConnectorResult cancelled = new ConnectorResult(OperationStatus.CANCELLED, null, reason, null, Map.of());
        operations.put(operationId, new Stored(s.payloadHash(), cancelled, null, s.connector()));
        return cancelled;
    }

    @Override
    public String openReconciliation(ReconciliationRequest request) {
        String id = "RECON-" + shortId();
        reconciliations.put(id, request);
        return id;
    }

    @Override
    public CapabilityClass capabilityOf(String connector) {
        CapabilityClass c = CAPABILITY.get(connector);
        if (c != null) {
            return c;
        }
        if (connector.startsWith("notify.") || connector.startsWith("query.")) {
            return CapabilityClass.B_LOOKUP_ONLY;
        }
        if (connector.startsWith("docs.")) {
            return CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP;
        }
        return CapabilityClass.C_NEITHER; // A-8 default
    }

    /** Reconciliation items opened so far (operator console simulation, tests). */
    public Map<String, ReconciliationRequest> reconciliations() {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(reconciliations));
    }

    /** Every request received, in order (tests and the demo console). */
    public List<ConnectorRequest> journal() {
        synchronized (journal) {
            return List.copyOf(journal);
        }
    }

    private ConnectorResult firstOutcome(ConnectorRequest r, String directive) {
        if (directive == null || directive.isBlank() || directive.equals("COMPLETED")) {
            return ConnectorResult.completed("SIM-" + r.operationId().substring(0, 8), effectData(r));
        }
        if (directive.startsWith("FAILED")) {
            return ConnectorResult.failed(reason(directive), "simulated failure");
        }
        if (directive.startsWith("UNKNOWN")) {
            return ConnectorResult.unknown("simulated lost response");
        }
        if (directive.startsWith("PENDING")) {
            return ConnectorResult.pending("SIM-" + r.operationId().substring(0, 8));
        }
        return ConnectorResult.completed("SIM-" + r.operationId().substring(0, 8), effectData(r));
    }

    private Map<String, Object> effectData(ConnectorRequest r) {
        Map<String, Object> d = new HashMap<>();
        switch (r.connector()) {
            case "pfms.payment", "ifms.payment", "bank.transfer", "pfms.dbt" -> d.put("utr", "UTR" + shortId());
            case "esign.sign", "dsc.sign" -> {
                d.put("certSerial", "SIM-CA-" + shortId());
                d.put("signedHash", r.payloadHash());
            }
            case "digilocker.push" -> d.put("uri", "in.gov.sim-" + r.tenantId() + "-" + shortId());
            case "docs.issue", "docs.render" -> d.put("documentNo", "DOC/" + shortId());
            case "paygov.fee", "ntrp.fee", "grass.challan" -> d.put("gatewayTxnId", "PG" + shortId());
            default -> {
            }
        }
        return d;
    }

    private Map<String, Object> defaultsFor(ConnectorRequest r) {
        Map<String, Object> p = r.payload();
        Map<String, Object> d = new HashMap<>();
        switch (r.connector()) {
            case "ifms.budget" -> d.put("available", new BigDecimal("10000000"));
            case "ifms.duplicateBill" -> {
                String key = r.tenantId() + "|" + p.get("payee") + "|" + p.get("invoiceNo");
                String owner = seenBills.putIfAbsent(key, String.valueOf(r.businessKey()));
                d.put("duplicate", owner != null && !owner.equals(String.valueOf(r.businessKey())));
            }
            case "registry.beneficiary" -> {
                String key = r.tenantId() + "|" + p.get("beneficiaryId") + "|" + p.get("schemeId");
                String owner = seenBeneficiaries.putIfAbsent(key, String.valueOf(r.businessKey()));
                d.put("duplicate", owner != null && !owner.equals(String.valueOf(r.businessKey())));
                d.put("exists", true);
            }
            case "bank.statement", "reconcile.match" -> {
                Stored s = operations.get(String.valueOf(p.get("operationId")));
                boolean happened = s != null && (s.first().status() == OperationStatus.COMPLETED
                        || (s.directive() != null && s.directive().endsWith("_THEN_COMPLETED")));
                d.put("match", happened ? "MATCHED" : "PENDING"); // the statement shows what really happened
            }
            case "digilocker.pull" -> {
                d.put("fetched", true);
                d.put("uri", "in.gov.sim.issuer-" + shortId());
            }
            case "eproc.evaluate" -> {
                d.put("l1BidderId", "BIDDER-1");
                d.put("l1Amount", new BigDecimal("950000"));
                d.put("responsiveBids", 3);
            }
            case "ai.extract" -> {
                d.put("confidence", 0.82d);
                d.put("extracted", true);
            }
            case "court.limbs" -> d.put("nextHearing", java.time.LocalDate.now().plusDays(30).toString());
            default -> d.put("ok", true);
        }
        return d;
    }

    private static String directive(ConnectorRequest r) {
        Object d = r.payload().get(DIRECTIVE);
        return d == null || d.toString().isBlank() ? null : d.toString().trim();
    }

    private static String reason(String directive) {
        int c = directive.indexOf(':');
        return c < 0 ? "SIMULATED_FAILURE" : directive.substring(c + 1);
    }

    private static Object coerce(String v) {
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return Boolean.parseBoolean(v);
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
