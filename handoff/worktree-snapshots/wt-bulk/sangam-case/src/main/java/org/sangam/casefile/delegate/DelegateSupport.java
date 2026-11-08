// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.delegate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.operaton.bpm.engine.impl.identity.Authentication;
import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.CaseModule;
import org.sangam.casefile.support.Db;
import org.sangam.ports.support.EngineTx;

import java.io.IOException;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared plumbing of the case-module delegates: the installed {@link CaseModule}, the engine command's connection (Q1),
 * the tenant, the acting user (the authenticated user, else the {@code initiator} variable, else {@code system}),
 * typed variable reads and the mapping of {@link CaseFileException} to a BPMN error ({@code CASEFILE_<CODE>}) so a
 * model can catch refusals with a boundary error event.
 */
final class DelegateSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DelegateSupport() {
    }

    static CaseModule module() {
        return CaseModule.get();
    }

    static Connection connection() {
        return EngineTx.connection();
    }

    static String tenant(DelegateExecution ex) {
        return Db.tenant(ex.getTenantId());
    }

    static String actor(DelegateExecution ex) {
        Authentication a = ex.getProcessEngineServices().getIdentityService().getCurrentAuthentication();
        if (a != null && a.getUserId() != null) {
            return a.getUserId();
        }
        Object initiator = ex.getVariable("initiator");
        return initiator == null ? "system" : initiator.toString();
    }

    static String field(Expression e, DelegateExecution ex, String dflt) {
        if (e == null) {
            return dflt;
        }
        Object v = e.getValue(ex);
        return v == null || v.toString().isBlank() ? dflt : v.toString().trim();
    }

    static String str(VariableScope s, String name) {
        Object v = s.getVariable(name);
        return v == null ? null : v.toString();
    }

    static String req(VariableScope s, String name) {
        String v = str(s, name);
        if (v == null || v.isBlank()) {
            throw new ProcessEngineException("variable " + name + " is required");
        }
        return v;
    }

    static Instant instant(VariableScope s, String name) {
        Object v = s.getVariable(name);
        if (v == null) {
            return null;
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant();
        }
        if (v instanceof Instant i) {
            return i;
        }
        String t = v.toString();
        return t.length() == 10 ? java.time.LocalDate.parse(t).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() : Instant.parse(t);
    }

    static boolean bool(VariableScope s, String name) {
        Object v = s.getVariable(name);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(VariableScope s, String name) {
        Object v = s.getVariable(name);
        if (v == null) {
            return new LinkedHashMap<>();
        }
        if (v instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        try {
            return MAPPER.readValue(v.toString(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            throw new ProcessEngineException("variable " + name + " is not a JSON object");
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(VariableScope s, String name) {
        Object v = s.getVariable(name);
        if (v == null) {
            return new ArrayList<>();
        }
        if (v instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>();
            l.forEach(x -> out.add((Map<String, Object>) x));
            return out;
        }
        try {
            return MAPPER.readValue(v.toString(), new TypeReference<ArrayList<Map<String, Object>>>() {
            });
        } catch (IOException e) {
            throw new ProcessEngineException("variable " + name + " is not a JSON array of objects");
        }
    }

    static List<String> list(VariableScope s, String name) {
        return Db.strings(s.getVariable(name));
    }

    /** Runs the operation; a {@link CaseFileException} becomes the BPMN error {@code CASEFILE_<CODE>}. */
    static void run(Runnable op) {
        try {
            op.run();
        } catch (CaseFileException e) {
            throw new org.operaton.bpm.engine.delegate.BpmnError("CASEFILE_" + e.code().name(), e.getMessage());
        }
    }
}
