// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    private Vars() {
    }

    public static String tenant(DelegateExecution ex) {
        if (ex.getTenantId() != null) {
            return ex.getTenantId();
        }
        Object t = ex.getVariable("tenantId");
        return t == null ? null : t.toString();
    }

    public static String tenant(DelegateTask task) {
        if (task.getTenantId() != null) {
            return task.getTenantId();
        }
        Object t = task.getVariable("tenantId");
        return t == null ? null : t.toString();
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
        if (v instanceof Number n) {
            return n.intValue();
        }
        return v == null || v.toString().isBlank() ? dflt : Integer.parseInt(v.toString().trim());
    }

    public static BigDecimal decimal(VariableScope s, String name) {
        return decimal(s.getVariable(name));
    }

    public static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Double || v instanceof Float) {
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.longValue());
        }
        String t = v.toString().replace(",", "").trim();
        return t.isEmpty() ? null : new BigDecimal(t);
    }

    public static Instant now() {
        return PackRuntime.clock().instant();
    }

    public static LocalDate today() {
        return LocalDate.ofInstant(now(), ZoneOffset.ofHoursMinutes(5, 30));
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
        ex.setVariable(CASE_STATE, state);
        String bk = ex.getProcessBusinessKey();
        org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity pi =
                ((org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity) ex).getProcessInstance();
        for (var sup = pi.getSuperExecution(); sup != null; sup = sup.getProcessInstance().getSuperExecution()) {
            if (bk == null || !bk.equals(sup.getProcessBusinessKey())) {
                break;
            }
            sup.setVariable(CASE_STATE, state);
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
    public static void setJsonDocument(VariableScope s, String name, Object value) {
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
            m.put(f, canonical(s.getVariable(f)));
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

    /** Converts a value to a type the engine stores natively (no Java serialization): numbers to Long or Double. */
    public static Object engineValue(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Double || v instanceof Date) {
            return v;
        }
        if (v instanceof java.math.BigDecimal b) {
            if (b.signum() == 0 || b.scale() <= 0 || b.stripTrailingZeros().scale() <= 0) {
                try {
                    return b.longValueExact();
                } catch (ArithmeticException e) {
                    return b.doubleValue();
                }
            }
            return b.doubleValue();
        }
        if (v instanceof Number n) {
            return n.doubleValue();
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
        PackRuntime.audit().record(new PackAuditEvent(type, tenant(ex), ex.getProcessBusinessKey(),
                ex.getProcessInstanceId(), str(ex, "lastActorPerson", "system"), str(ex, "lastActorSeat"),
                str(ex, "lastActedForSeat"), str(ex, "lastAuthorityBasis"), str(ex, PACK_VERSION),
                str(ex, APPROVED_HASH), str(ex, OPERATION_ID), details, now()));
    }

    public static void auditTask(DelegateTask t, String type, String person, String seat, String actedFor,
                                 String basis, Map<String, Object> details) {
        PackRuntime.audit().record(new PackAuditEvent(type, tenant(t), t.getExecution().getProcessBusinessKey(),
                t.getProcessInstanceId(), person, seat, actedFor, basis, str(t, PACK_VERSION), str(t, APPROVED_HASH),
                str(t, OPERATION_ID), details, now()));
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
