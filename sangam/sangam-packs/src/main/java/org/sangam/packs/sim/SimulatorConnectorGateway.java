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
 *       (for example {@code available=310000} for J-P1-E11, {@code duplicate=true} for J-P1-E03); a value
 *       {@code [k:v,k:v][k:v,k:v]} is a list of records ({@link #records}, decision 223: the frozen wage register of
 *       {@code hris.inputs});</li>
 *   <li>any directive followed by {@value #SELECT} and business-key suffixes ({@code |}-separated) applies only to
 *       the requests of those cases, for example {@code FAILED:ACCOUNT_CLOSED @ :1:ITEM-00002|:1:ITEM-00004} for two
 *       batch items in round 1 (GP-49); batch children receive the parent's directives (BatchDelegate FAN_OUT);
 *       a selector {@code field=value} matches a payload field instead, and alternatives are separated by
 *       {@value #OR}, for example {@code score=60 @ scoreRound=1 || score=80 @ scoreRound=2} (GP-47).</li>
 * </ul>
 * Effects are idempotent per operationId: a repeat with the same payload hash returns the first result, a different
 * hash is refused with {@code IDEMPOTENCY_PAYLOAD_MISMATCH} (R41).
 */
public class SimulatorConnectorGateway implements ConnectorGateway {

    /** Payload key carrying the failure-injection directive. */
    public static final String DIRECTIVE = "_sim";
    /** Separates a directive from the business-key suffixes it is limited to (per batch item, GP-49). */
    public static final String SELECT = " @ ";
    /** Separates alternative directives, each with its own selectors (GP-47: one score per score round). */
    public static final String OR = "||";

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
    private final Map<String, String> installed = new ConcurrentHashMap<>();
    private final Map<String, ReconciliationRequest> reconciliations = new ConcurrentHashMap<>();
    private final Map<String, String> seenBills = new ConcurrentHashMap<>();
    private final Map<String, String> seenBeneficiaries = new ConcurrentHashMap<>();
    private final List<ConnectorRequest> journal = java.util.Collections.synchronizedList(new ArrayList<>());

    private record Stored(String payloadHash, ConnectorResult first, String directive, String connector) {
    }

    /** The configuration-profile connector (sangam-config ConfigConnector); its directive pairs are profile values. */
    static final String PROFILE = "config.profile";

    /**
     * Decision 225: a simulated {@code config.profile} answers in the connector's own shape. The directive's
     * {@code path=value} pairs are the simulated profile's values; {@code profile.get} returns {@code values} with every
     * requested path ({@code paths}, comma list), a path the simulated profile does not hold with a null value, as the
     * real connector does. Other operations get no data from those pairs (they are profile values, not answer fields).
     */
    static Map<String, Object> profileAnswer(ConnectorRequest r, Map<String, Object> profileValues) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (!"profile.get".equals(r.operation())) {
            return out;
        }
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        Object paths = r.payload().get("paths");
        for (String p : paths == null ? new String[0] : paths.toString().split(",")) {
            if (!p.isBlank()) {
                values.put(p.trim(), profileValues.get(p.trim()));
            }
        }
        out.put("values", values);
        out.put("binding", "SIMULATOR");
        return out;
    }

    @Override
    public ConnectorResult query(ConnectorRequest r) {
        journal.add(r);
        String directive = directive(r);
        Map<String, Object> data = new HashMap<>(defaultsFor(r));
        if (directive != null && directive.contains("=")) {
            Map<String, Object> pairs = new HashMap<>();
            for (String pair : directive.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    pairs.put(pair.substring(0, eq).trim(), coerce(pair.substring(eq + 1).trim()));
                }
            }
            if (PROFILE.equals(r.connector())) {
                return ConnectorResult.completed("SIM-Q-" + shortId(), profileAnswer(r, pairs));
            }
            data.putAll(pairs);
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
                    : ConnectorResult.completed(reference(operationId), Map.of("lookedUp", true));
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
        if ("mdm.install".equals(r.connector()) && r.payload().get("applicantRef") != null) {
            installed.put(r.tenantId() + "|" + r.payload().get("applicantRef"), String.valueOf(r.businessKey()));
        }
        if (directive != null && directive.contains("=")) {
            Map<String, Object> data = new HashMap<>(effectData(r));
            for (String pair : directive.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    data.put(pair.substring(0, eq).trim(), coerce(pair.substring(eq + 1).trim()));
                }
            }
            return ConnectorResult.completed(reference(r.operationId()), data);
        }
        if (directive == null || directive.isBlank() || directive.equals("COMPLETED")) {
            return ConnectorResult.completed(reference(r.operationId()), effectData(r));
        }
        if (directive.startsWith("FAILED")) {
            return ConnectorResult.failed(reason(directive), "simulated failure");
        }
        if (directive.startsWith("UNKNOWN")) {
            return ConnectorResult.unknown("simulated lost response");
        }
        if (directive.startsWith("PENDING")) {
            return ConnectorResult.pending(reference(r.operationId()));
        }
        return ConnectorResult.completed(reference(r.operationId()), effectData(r));
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
            case "ifms.budget" -> d.put("available", 1_000_000_000L); // 1 crore, in paise (pack money is minor units)
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
            case "mdm.consumersDue" -> {
                // the MDM knows the connection case of a newly energised consumer, so its first bill can release the credit
                List<Map<String, Object>> items = new ArrayList<>();
                installed.forEach((k, bk) -> {
                    if (k.startsWith(r.tenantId() + "|")) {
                        Map<String, Object> it = new HashMap<>();
                        it.put("key", k.substring(k.indexOf('|') + 1));
                        it.put("connectionCaseKey", bk);
                        items.add(it);
                    }
                });
                for (int i = 1; i <= 3; i++) {
                    items.add(Map.of("key", "CONS-" + i));
                }
                d.put("items", items);
            }
            case "digilocker.pull" -> {
                d.put("fetched", true);
                d.put("uri", "in.gov.sim.issuer-" + shortId());
            }
            case "eproc.emdRegister" -> {
                // the e-procurement portal's register of the bids received with their EMD captures (item 11): one
                // record per responsive bidder of eproc.evaluate; each capture reference is unique to this answer
                String round = shortId();
                List<Map<String, Object>> reg = new ArrayList<>();
                for (int i = 1; i <= 3; i++) {
                    reg.add(Map.of("bidderId", "BIDDER-" + i, "emdAmount", 2_000_000L, // 20,000 rupees, in paise
                            "emdCaptureRef", "SIM-EMD-" + round + "-" + i));
                }
                d.put("register", reg);
            }
            case "eproc.evaluate" -> {
                d.put("l1BidderId", "BIDDER-1");
                d.put("l1Amount", 95_000_000L); // 9.5 lakh, in paise
                d.put("responsiveBids", 3);
            }
            case "ai.extract" -> {
                d.put("confidence", 0.82d);
                d.put("extracted", true);
            }
            // SL-149: the pack's business date (scenario clock, tenant zone), not the JVM's date and zone
            case "court.limbs" -> d.put("nextHearing",
                    org.sangam.packs.core.Vars.today(r.tenantId()).plusDays(30).toString());
            // sangam-config's contract (ConfigConnector), simulated: a result only from the contract's inputs, so a
            // process that does not send them is not validated by the simulator either
            case "config.documentType" -> {
                switch (String.valueOf(r.operation())) {
                    case "documentType.get" -> {
                        Object form = p.get("typeId") != null ? p.get("typeId")
                                : p.get("typeRef") != null ? p.get("typeRef") : p.get("formNumber");
                        if (form != null) {
                            d.put("id", "SIM_" + form);
                            d.put("formNumber", form);
                            d.put("approvalChain", "SIM_CHAIN");
                        }
                    }
                    case "documentType.check" -> d.put("valid", p.get("typeId") != null);
                    case "chain.signatures" -> {
                        if (p.get("chainId") != null && p.get("amount") != null) {
                            d.put("signatures", 1);
                        }
                    }
                    default -> d.put("ok", true);
                }
            }
            case "config.classification" -> d.put("valid", p.get("code") != null);
            default -> d.put("ok", true);
        }
        return d;
    }

    private static String directive(ConnectorRequest r) {
        Object d = r.payload().get(DIRECTIVE);
        return d == null || d.toString().isBlank() ? null
                : selected(d.toString().trim(), r.businessKey(), r.payload());
    }

    /** {@link #selected(String, String, Map)} without payload fields (business-key selectors only). */
    public static String selected(String directive, String businessKey) {
        return selected(directive, businessKey, Map.of());
    }

    /**
     * Which directive applies to a request. A directive is one or more alternatives separated by {@value #OR}, each
     * {@code <directive>} or {@code <directive>} {@value #SELECT} {@code <selector>|<selector>...}; the first
     * alternative with no selector or with a matching selector applies, otherwise the default outcome (null).
     * A selector is either {@code <field>=<value>}, matching the request payload field as text (GP-47:
     * {@code scoreRound=2}), or a business-key suffix (GP-49: a batch item is {@code <parent>:<round>:<item>}, so
     * {@code :1:ITEM-00002} is that item in round 1 only). A malformed directive (an empty alternative, {@value
     * #SELECT} without selectors) is refused, never guessed.
     */
    public static String selected(String directive, String businessKey, Map<String, Object> payload) {
        // the whole directive is checked before any alternative is chosen: a malformed alternative after the one
        // that applies is refused too, whatever the request
        String[] alternatives = directive.split(java.util.regex.Pattern.quote(OR), -1);
        List<String> bases = new ArrayList<>();
        List<List<String>> selectors = new ArrayList<>();
        for (String alternative : alternatives) {
            String alt = alternative.trim();
            if (alt.isEmpty()) {
                throw new IllegalArgumentException("simulator directive with an empty alternative: " + directive);
            }
            String padded = " " + alt + " "; // "@ x" (no directive) and "X @" (no selector) are still found
            int at = padded.indexOf(SELECT);
            if (at < 0) {
                bases.add(alt);
                selectors.add(List.of());
                continue;
            }
            String sel = padded.substring(at + SELECT.length()).trim();
            if (sel.isEmpty()) {
                throw new IllegalArgumentException("simulator directive selector is empty: " + directive);
            }
            List<String> tokens = new ArrayList<>();
            for (String token : sel.split("\\|", -1)) {
                String s = token.trim();
                if (s.isEmpty()) {
                    throw new IllegalArgumentException("simulator directive with an empty selector: " + directive);
                }
                tokens.add(s);
            }
            bases.add(padded.substring(0, at).trim());
            selectors.add(tokens);
        }
        for (int i = 0; i < bases.size(); i++) {
            List<String> tokens = selectors.get(i);
            if (tokens.isEmpty()) {
                return bases.get(i); // no selector: applies to every request
            }
            for (String s : tokens) {
                if (matches(s, businessKey, payload)) {
                    return bases.get(i).isEmpty() ? null : bases.get(i);
                }
            }
        }
        return null;
    }

    /** The simulated external reference of an operation: {@code SIM-} and the first characters of its id. */
    private static String reference(String operationId) {
        return "SIM-" + operationId.substring(0, Math.min(8, operationId.length()));
    }

    private static boolean matches(String selector, String businessKey, Map<String, Object> payload) {
        int eq = selector.indexOf('=');
        if (eq > 0 && selector.substring(0, eq).matches("[A-Za-z_][A-Za-z0-9_]*")) {
            Object v = payload == null ? null : payload.get(selector.substring(0, eq));
            return v != null && String.valueOf(v).equals(selector.substring(eq + 1).trim());
        }
        return businessKey != null && businessKey.endsWith(selector);
    }

    private static String reason(String directive) {
        int c = directive.indexOf(':');
        return c < 0 ? "SIMULATED_FAILURE" : directive.substring(c + 1);
    }

    private static final java.util.regex.Pattern RECORDS = java.util.regex.Pattern.compile("(\\[[^\\[\\]]*\\])+");
    private static final java.util.regex.Pattern RECORD = java.util.regex.Pattern.compile("\\[([^\\[\\]]*)\\]");
    private static final java.util.regex.Pattern FIELD = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final java.util.regex.Pattern WHOLE = java.util.regex.Pattern.compile("-?(0|[1-9][0-9]*)");

    /**
     * Decision 223: the list-of-records value of a directive pair, {@code name=[k:v,k:v][k:v,k:v]} (one bracketed
     * record per item, {@code []} for an empty list), for example
     * {@code register=[employeeId:E001,pfWages:1500000][employeeId:E002,pfWages:3000000]}. Parsed exactly: a field is
     * {@code <name>:<value>} split at the first {@code :}, the name an identifier, the value non-empty and taken as is
     * (no trimming); {@code true} / {@code false} is a boolean, a whole number without a leading zero is a
     * {@link Long} (amounts are integer minor units), anything else is text (so an identifier such as {@code 007}
     * stays text). A number with a fraction, a repeated field, an empty record or field and any text outside the
     * brackets are refused. A value cannot contain {@code ; , [ ]} (the directive's own separators). Backward
     * compatible: before this format no directive value began with {@code [} and ended with {@code ]}.
     */
    public static List<Map<String, Object>> records(String v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v.equals("[]")) {
            return out;
        }
        if (!RECORDS.matcher(v).matches()) {
            throw new IllegalArgumentException("simulator list value is [k:v,...][k:v,...], not " + v);
        }
        java.util.regex.Matcher m = RECORD.matcher(v);
        while (m.find()) {
            String body = m.group(1);
            if (body.isEmpty()) {
                throw new IllegalArgumentException("simulator list value with an empty record: " + v);
            }
            Map<String, Object> rec = new java.util.LinkedHashMap<>();
            for (String field : body.split(",", -1)) {
                int c = field.indexOf(':');
                String name = c < 0 ? field : field.substring(0, c);
                String value = c < 0 ? "" : field.substring(c + 1);
                if (!FIELD.matcher(name).matches() || value.isEmpty()) {
                    throw new IllegalArgumentException("simulator list field must be name:value, not '" + field + "'");
                }
                if (rec.containsKey(name)) {
                    throw new IllegalArgumentException("simulator list record repeats field " + name + ": " + v);
                }
                rec.put(name, item(value));
            }
            out.add(rec);
        }
        return out;
    }

    private static Object item(String value) {
        if (value.equals("true") || value.equals("false")) {
            return Boolean.parseBoolean(value);
        }
        if (WHOLE.matcher(value).matches()) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("simulator list number beyond a long: " + value);
            }
        }
        if (value.matches("-?[0-9]*\\.[0-9]+")) {
            throw new IllegalArgumentException("simulator list amount " + value + " is not whole minor units");
        }
        return value;
    }

    private static Object coerce(String v) {
        if (v.startsWith("[") && v.endsWith("]")) {
            return records(v);
        }
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
