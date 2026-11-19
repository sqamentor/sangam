// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.VariableScope;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;

/**
 * Variable conventions shared by every pack (documented in the module README, "Process variable contract").
 * Decimals are carried as strings in hashes (R33); JSON values are stored as strings.
 */
public final class Vars {

    /** Namespace for operationIds: UUIDv5(NS, businessKey | step | attempt) (T35). */
    public static final UUID OPERATION_NS = UUID.fromString("6f1c2a52-3b8e-5d41-9e7a-5a6e2c0d7b11");

    public static final String CASE_STATE = "caseState";
    public static final String PACK_VERSION = "packVersion";
    public static final String MATERIAL_FIELDS = "materialFields";
    public static final String APPROVED_HASH = "approvedPayloadHash";
    public static final String INITIATOR_PERSON = "initiatorPerson";
    public static final String INITIATOR_SEAT = "initiatorSeat";
    public static final String ACTOR_LOG = "actorLog";
    public static final String CASE_VERSION = "caseVersion";
    public static final String CASE_DUE_AT = "caseDueAt";
    public static final String CASE_PAUSED_AT = "casePausedAt";
    public static final String OPERATION_ID = "operationId";
    public static final String OP_STATUS = "opStatus";

    /** Decision 138: JSON fractions read as BigDecimal (never Double); written in BigDecimal's canonical form. */
    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private Vars() {
    }

    /** The case variable that used to stand in for the tenant: now only an untrusted claim (decision 145). */
    public static final String TENANT_VARIABLE = "tenantId";

    /**
     * Decision 145: the tenant of pack work is the engine tenant of the execution, set by authenticated, authorised
     * process creation, and nothing else. Pack work is tenant-owned ({@code ExecutionTenantPolicy.tenantOwned}): an
     * execution without an engine tenant fails closed ({@code TENANT_CONTEXT_MISSING}); a {@value #TENANT_VARIABLE}
     * case variable is untrusted business data that never substitutes for or overrides it, and one that differs is
     * refused ({@code TENANT_CONTEXT_CONFLICT}) before the caller's connector call, seat, ledger, decision, calendar or
     * audit use. Every caller (connector gateway, credentials, data access, audit routing) gets the same answer.
     *
     * @throws org.sangam.core.tenant.TenantResolutionException when the engine tenant is missing or contradicted
     */
    public static String tenant(DelegateExecution ex) {
        return org.sangam.core.tenant.ExecutionTenantPolicy.tenantOwned(ex.getTenantId(),
                claim(ex.getVariable(TENANT_VARIABLE)), "pack execution").tenantId();
    }

    /**
     * SL-90: the tenant key of a pack ledger or batch row (PACK_LEDGER_ACCOUNT, PACK_LEDGER_POSTING, PACK_BATCH_ITEM:
     * tenant_id is a NOT NULL key). Since decision 145 this is always the execution's engine tenant ({@link #tenant}
     * fails closed without one), never the reserved shared key.
     */
    public static String tenantKey(DelegateExecution ex) {
        return org.sangam.core.layer.TenantKey.of(tenant(ex));
    }

    /** {@link #tenant(DelegateExecution)} for a user task: the task's engine tenant only (decision 145). */
    public static String tenant(DelegateTask task) {
        return org.sangam.core.tenant.ExecutionTenantPolicy.tenantOwned(task.getTenantId(),
                claim(task.getVariable(TENANT_VARIABLE)), "pack task").tenantId();
    }

    /** A tenant claimed by a case variable, as text (a non-text value is still a claim, never ignored). */
    private static String claim(Object v) {
        return v == null ? null : v.toString();
    }

    public static String field(Expression e, VariableScope scope) {
        return field(e, scope, null);
    }

    public static String field(Expression e, VariableScope scope, String dflt) {
        if (e == null) {
            return dflt;
        }
        Object v = e.getValue(scope);
        return v == null || v.toString().isBlank() ? dflt : v.toString();
    }

    public static boolean fieldBool(Expression e, VariableScope scope, boolean dflt) {
        String s = field(e, scope, null);
        return s == null ? dflt : Boolean.parseBoolean(s.trim());
    }

    public static int fieldInt(Expression e, VariableScope scope, int dflt) {
        String s = field(e, scope, null);
        return s == null ? dflt : Integer.parseInt(s.trim());
    }

    public static String str(VariableScope s, String name) {
        Object v = s.getVariable(name);
        return v == null ? null : v.toString();
    }

    public static String str(VariableScope s, String name, String dflt) {
        String v = str(s, name);
        return v == null || v.isBlank() ? dflt : v;
    }

    public static boolean bool(VariableScope s, String name) {
        Object v = s.getVariable(name);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }

    public static int intVal(VariableScope s, String name, int dflt) {
        Object v = s.getVariable(name);
        if (v instanceof Number) {
            return decimal(v).intValueExact(); // decision 138: a fraction or an overflow is refused, never truncated
        }
        return v == null || v.toString().isBlank() ? dflt : Integer.parseInt(v.toString().trim());
    }

    public static BigDecimal decimal(VariableScope s, String name) {
        return decimal(s.getVariable(name));
    }

    /**
     * Decision 138: the exact decimal of a value, by the rules of the engine decimal type
     * ({@link org.sangam.packs.engine.DecimalText#exact}): {@link BigDecimal} as is, integer types exactly, text as
     * strict decimal text after removing grouping commas and surrounding blanks. A binary floating-point value
     * ({@link Double}, {@link Float}) is refused with code {@value #DECIMAL_FROM_FLOAT}: its decimal text is not the
     * value the user or the rule meant, and nothing on the pack path may produce one. Invalid text, a value over the
     * length limit and unsupported types are refused with their codes; blank text is {@code null}.
     *
     * @throws org.sangam.packs.engine.DecimalText.DecimalFormatException with the refusal code
     */
    public static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof CharSequence) {
            String t = v.toString().replace(",", "").trim();
            return t.isEmpty() ? null : org.sangam.packs.engine.DecimalText.parse(t);
        }
        return org.sangam.packs.engine.DecimalText.exact(v);
    }

    /** Refusal code of a binary floating-point value (decision 138), as in the engine decimal type. */
    public static final String DECIMAL_FROM_FLOAT = "DECIMAL_FROM_FLOAT";

    public static Instant now() {
        return PackRuntime.clock().instant();
    }

    /**
     * The business zone of {@code tenantId}: the tenant's resolved profile ({@code locale.zone}) through the
     * process-wide {@link org.sangam.core.time.BusinessClock}, which the server installs; a tenant without a profile is
     * in UTC (the documented fallback, logged once per tenant). SL-104 (ADR-D-20): the zone is tenant configuration,
     * never a fixed country zone.
     */
    public static java.time.ZoneId zone(String tenantId) {
        return org.sangam.core.time.BusinessClock.system().zone(tenantId);
    }

    /** Today's business date of {@code tenantId}: the pack clock's instant in the tenant's zone ({@link #zone}). */
    public static LocalDate today(String tenantId) {
        return LocalDate.ofInstant(now(), zone(tenantId));
    }

    public static Instant instant(VariableScope s, String name) {
        Object v = s.getVariable(name);
        if (v == null) {
            return null;
        }
        if (v instanceof Date d) {
            return d.toInstant();
        }
        return Instant.parse(v.toString());
    }

    public static List<String> csv(String s) {
        if (s == null || s.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
    }

    public static String joinCsv(List<String> parts) {
        return String.join(",", parts);
    }

    /**
     * Sets the case state on this scope and on every calling process instance of the same case (same business key),
     * so the case shows its state while a core block is still running (a rejection waiting out its appeal window was
     * still SCRUTINY on the case: SL-32, J-P3-E01). A called instance of another case (a batch item) stops the walk.
     */
    public static void setCaseState(org.operaton.bpm.engine.delegate.DelegateExecution ex, String state) {
        setOnCase(ex, CASE_STATE, state);
    }

    /**
     * Sets {@code name} on this process instance and on every calling instance of the same case (same business key),
     * as {@link #setCaseState} does for the case state: a called core block that waits (execution) makes its state
     * visible to the case's side exits, which run in the caller.
     */
    public static void setOnCase(org.operaton.bpm.engine.delegate.DelegateExecution ex, String name, Object value) {
        ex.setVariable(name, value);
        String bk = ex.getProcessBusinessKey();
        org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity pi =
                ((org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity) ex).getProcessInstance();
        for (var sup = pi.getSuperExecution(); sup != null; sup = sup.getProcessInstance().getSuperExecution()) {
            if (bk == null || !bk.equals(sup.getProcessBusinessKey())) {
                break;
            }
            sup.setVariable(name, value);
        }
    }

    // ---- JSON values stored as strings -------------------------------------------------------------------------

    public static String toJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            throw new ProcessEngineException("cannot write JSON", e);
        }
    }

    /**
     * Stores a JSON document that grows with the case (the actor log) as UTF-8 bytes: an engine string variable is
     * limited to 4000 characters (ACT_RU_VARIABLE.TEXT_), which a long case exceeds. {@link #jsonList} reads both.
     */
    /**
     * Decision 387 (1): sets a variable whose name the delegate does not fix (a connector response key, a decision
     * output, a step name read from the case), refusing a declared DSC document slot: only the document route sets one.
     */
    public static void setDynamic(VariableScope s, String name, Object value) {
        org.sangam.core.engine.DocumentSlotNames.refuse(List.of(name));
        s.setVariable(name, value);
    }

    public static void setJsonDocument(VariableScope s, String name, Object value) {
        org.sangam.core.engine.DocumentSlotNames.refuse(List.of(name));
        s.setVariable(name, org.operaton.bpm.engine.variable.Variables.byteArrayValue(
                toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public static List<Map<String, Object>> jsonList(VariableScope s, String name) {
        Object v = s.getVariable(name);
        if (v == null || v.toString().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(v instanceof byte[] b ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
                    : v.toString(), new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            throw new ProcessEngineException("variable " + name + " is not a JSON array", e);
        }
    }

    public static Map<String, Object> jsonMap(VariableScope s, String name) {
        Object v = s.getVariable(name);
        if (v == null || v.toString().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(v instanceof byte[] b ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
                    : v.toString(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new ProcessEngineException("variable " + name + " is not a JSON object", e);
        }
    }

    // ---- material payload and hash (VI.4 rule 5, K4) -------------------------------------------------------------

    /** The material fields of the case, canonicalised (decimals as plain strings). */
    public static Map<String, Object> materialPayload(VariableScope s) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : csv(str(s, MATERIAL_FIELDS))) {
            m.put(f, canonical(s.getVariable(f)));
        }
        return m;
    }

    public static Map<String, Object> payloadOf(VariableScope s, List<String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : names) {
            // "variable>parameter": the process variable is sent under the connector's contract name (the same
            // source>target form as call-activity mappings), so a pack's vocabulary never has to match a connector's
            int gt = f.indexOf('>');
            String var = gt < 0 ? f : f.substring(0, gt).trim();
            String param = gt < 0 ? f : f.substring(gt + 1).trim();
            if (var.isEmpty() || param.isEmpty()) {
                throw new IllegalArgumentException("connector input '" + f + "' must be 'variable' or 'variable>parameter'");
            }
            m.put(param, canonical(s.getVariable(var)));
        }
        return m;
    }

    public static Object canonical(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            return v;
        }
        if (v instanceof Number) {
            return decimal(v).stripTrailingZeros().toPlainString();
        }
        if (v instanceof Date d) {
            return d.toInstant().toString();
        }
        if (v instanceof byte[] b) {
            return "sha256:" + Hashes.hex(Hashes.sha256(b)); // deterministic, not the array identity
        }
        return v.toString();
    }

    /**
     * Converts a value to a type the engine stores without Java serialization. Decision 138: numbers stay exact. A
     * whole number that fits a long is a {@link Long} (or the given {@link Integer}); every other number is a
     * {@link BigDecimal}, which the engine stores losslessly as decimal text (type id {@code sangam-decimal-v1}, value
     * and scale kept). A binary floating-point value is refused ({@value #DECIMAL_FROM_FLOAT}), never stored.
     */
    public static Object engineValue(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Date) {
            return v;
        }
        if (v instanceof Number) {
            BigDecimal b = decimal(v); // refuses Double and Float
            if (b.signum() == 0 || b.stripTrailingZeros().scale() <= 0) {
                try {
                    return b.longValueExact();
                } catch (ArithmeticException beyondLong) {
                    return b; // whole but beyond a long: exact decimal, never a double
                }
            }
            return b;
        }
        if (v instanceof Map<?, ?> || v instanceof java.util.Collection<?>) {
            return toJson(v);
        }
        return v.toString();
    }

    public static String hash(Map<String, Object> payload) {
        return Hashes.hex(Hashes.sha256(CanonicalJson.write(payload)));
    }

    public static String materialHash(VariableScope s) {
        return hash(materialPayload(s));
    }

    /**
     * The stable operation id: a UUIDv5 string, which IntegrationConnectorGateway uses directly as the ledger id. The
     * tenant is part of the name, so two tenants with the same business key never collide in the global ledger. A case
     * without a tenant keeps the name {@code businessKey|step|attempt}.
     */
    public static String operationId(String tenantId, String businessKey, String step, int attempt) {
        String name = businessKey + "|" + step + "|" + attempt;
        if (tenantId != null && !tenantId.isBlank()) {
            name = "tenant=" + tenantId.length() + ":" + tenantId + "|" + name;
        }
        return Hashes.uuidV5(OPERATION_NS, name).toString();
    }

    // ---- audit ---------------------------------------------------------------------------------------------------

    public static void audit(DelegateExecution ex, String type, Map<String, Object> details) {
        String tenant = tenant(ex);
        PackRuntime.audit().record(new PackAuditEvent(type, tenant, ex.getProcessBusinessKey(),
                ex.getProcessInstanceId(), str(ex, "lastActorPerson", "system"), str(ex, "lastActorSeat"),
                str(ex, "lastActedForSeat"), str(ex, "lastAuthorityBasis"), str(ex, PACK_VERSION),
                str(ex, APPROVED_HASH), str(ex, OPERATION_ID), sandboxMarked(tenant, details), now()));
    }

    public static void auditTask(DelegateTask t, String type, String person, String seat, String actedFor,
                                 String basis, Map<String, Object> details) {
        String tenant = tenant(t);
        PackRuntime.audit().record(new PackAuditEvent(type, tenant, t.getExecution().getProcessBusinessKey(),
                t.getProcessInstanceId(), person, seat, actedFor, basis, str(t, PACK_VERSION), str(t, APPROVED_HASH),
                str(t, OPERATION_ID), sandboxMarked(tenant, details), now()));
    }

    /** Owner option (b): every pack audit event of a demo sandbox tenant carries {@code sandbox=true}. */
    static Map<String, Object> sandboxMarked(String tenant, Map<String, Object> details) {
        if (!org.sangam.packs.port.DemoSandbox.isSandbox(tenant)) {
            return details;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (details != null) {
            m.putAll(details);
        }
        m.put(org.sangam.packs.port.DemoSandbox.AUDIT_KEY, true);
        return m;
    }

    /** Map literal helper that tolerates null values. */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** Variable name of the simulator directive for a connector ({@code pfms.payment -> sim_pfms_payment}). */
    public static String simVar(String connector) {
        return "sim_" + connector.replace('.', '_').replace('-', '_');
    }

    /** Copies a simulator directive into a payload if the case carries one (no effect in production adapters). */
    public static Map<String, Object> withDirective(VariableScope s, String connector, Map<String, Object> payload) {
        Object d = s.getVariable(simVar(connector));
        if (d == null) {
            return payload;
        }
        Map<String, Object> m = new LinkedHashMap<>(payload);
        m.put(org.sangam.packs.sim.SimulatorConnectorGateway.DIRECTIVE, d.toString());
        return m;
    }
}
