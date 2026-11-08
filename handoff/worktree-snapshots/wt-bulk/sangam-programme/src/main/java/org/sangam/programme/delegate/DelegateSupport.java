// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.delegate;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.sangam.ports.support.EngineTx;
import org.sangam.programme.ProgrammeModule;

import java.sql.Connection;
import java.time.LocalDate;

/**
 * Shared helpers of the programme delegates. Product rows are written on the engine command's connection, so they
 * commit or roll back with the engine state (Q1). Inputs are read from a field injection first, then from a process
 * variable of the same name. The tenant is the execution's tenant ("" when none).
 */
abstract class DelegateSupport {

    static ProgrammeModule module() {
        return ProgrammeModule.get();
    }

    static Connection connection() {
        return EngineTx.connection();
    }

    static String tenant(DelegateExecution ex) {
        return ex.getTenantId() == null ? "" : ex.getTenantId();
    }

    static String input(Expression field, DelegateExecution ex, String name, String dflt) {
        if (field != null) {
            Object v = field.getValue(ex);
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        Object v = ex.getVariable(name);
        return v == null || v.toString().isBlank() ? dflt : v.toString();
    }

    static String required(Expression field, DelegateExecution ex, String name) {
        String v = input(field, ex, name, null);
        if (v == null) {
            throw new ProcessEngineException("missing input '" + name + "'");
        }
        return v;
    }

    static String actor(DelegateExecution ex) {
        Object a = ex.getVariable("actor");
        if (a != null && !a.toString().isBlank()) {
            return a.toString();
        }
        String user = ex.getProcessEngineServices().getIdentityService().getCurrentAuthentication() == null ? null
                : ex.getProcessEngineServices().getIdentityService().getCurrentAuthentication().getUserId();
        return user == null ? "system" : user;
    }

    static LocalDate asOf(Expression field, DelegateExecution ex) {
        String v = input(field, ex, "asOfDate", null);
        return v == null ? org.sangam.programme.support.Db.today(connection(), module().dialect()) : LocalDate.parse(v.substring(0, 10));
    }
}
