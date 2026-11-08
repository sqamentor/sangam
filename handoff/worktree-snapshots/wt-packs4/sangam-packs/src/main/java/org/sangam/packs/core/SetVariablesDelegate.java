// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/**
 * Sets literal variables. {@code values} always sets, {@code defaults} sets only variables that are still null (pack
 * configuration that a department may override at start). Format: {@code name=value;name=value}; values
 * {@code true}/{@code false} become booleans, whole numbers become integers (up to 9 digits) or longs (10 to 18 digits:
 * money is integer minor units, and 1 crore rupees is already 10 digits of paise), {@code null} clears, anything else is a
 * string. A value starting with {@code @} copies another variable ({@code a=@b}).
 */
public class SetVariablesDelegate implements JavaDelegate, ExecutionListener {

    private Expression values;
    private Expression defaults;

    @Override
    public void execute(DelegateExecution ex) {
        apply(ex);
    }

    @Override
    public void notify(DelegateExecution ex) {
        apply(ex);
    }

    private void apply(DelegateExecution ex) {
        assign(ex, Vars.field(defaults, ex), true);
        assign(ex, Vars.field(values, ex), false);
    }

    private static void assign(DelegateExecution ex, String spec, boolean onlyIfNull) {
        if (spec == null) {
            return;
        }
        for (String pair : spec.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String raw = pair.substring(eq + 1).trim();
            if (onlyIfNull && ex.getVariable(name) != null) {
                continue;
            }
            ex.setVariable(name, parse(ex, raw));
        }
    }

    private static Object parse(DelegateExecution ex, String raw) {
        if (raw.equals("null")) {
            return null;
        }
        if (raw.equals("true") || raw.equals("false")) {
            return Boolean.parseBoolean(raw);
        }
        if (raw.startsWith("@")) {
            return ex.getVariable(raw.substring(1));
        }
        if (raw.matches("-?\\d{1,9}")) {
            return Integer.parseInt(raw);
        }
        if (raw.matches("-?\\d{10,18}")) {
            return Long.parseLong(raw);
        }
        if (raw.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(raw);
        }
        return raw;
    }
}
