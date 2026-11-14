// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.util.Locale;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.PackRuntime;

/**
 * Reads ONE value of the case's bound configuration profile (read-only) and sets it as a case variable, failing closed.
 *
 * <p>The query goes to the {@code config.profile} connector, operation {@code profile.get}, with {@code paths} = the
 * fixed {@code path} of the model (never a variable). The connector reads the profile the case bound at its start (the
 * gateway sends the root case, SL-119), so a running case keeps the value it started with.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code path}: the profile path, a literal of the model (required);</li>
 *   <li>{@code variable}: the case variable to set (required); {@code <variable>Status} records the connector status and
 *       {@code <variable>Binding} the profile binding the answer came from;</li>
 *   <li>{@code type}: {@code BOOLEAN} (default) or {@code STRING}, the only type of value accepted.</li>
 * </ul>
 *
 * <p>The variable is cleared before the query and is set only when the answer completed and its {@code values} map holds
 * the path with a value of the declared type. Otherwise it stays null and {@code <variable>Problem} says why
 * ({@code NOT_COMPLETED}, {@code MISSING}, {@code WRONG_TYPE}); the model routes a null value to a coded refusal. No start
 * variable, form field, pack default or earlier answer can stand in for the profile value.
 */
public class ProfileValueDelegate implements JavaDelegate {

    /** The pack connector id of the profile values (sangam-config ConfigConnector, bound by the server wiring). */
    public static final String CONNECTOR = "config.profile";
    /** The read-only operation that answers {@code values} for a comma list of {@code paths}. */
    public static final String OPERATION = "profile.get";

    private Expression path;
    private Expression variable;
    private Expression type;

    /** The value a profile answer gives for {@code path}, or the reason it gives none. */
    public record Answer(Object value, String problem) {
    }

    @Override
    public void execute(DelegateExecution ex) {
        String p = Vars.field(path, ex);
        String v = Vars.field(variable, ex);
        if (p == null || v == null) {
            throw new ProcessEngineException("ProfileValueDelegate needs the fields path and variable");
        }
        String t = Vars.field(type, ex, "BOOLEAN").toUpperCase(Locale.ROOT);
        if (!"BOOLEAN".equals(t) && !"STRING".equals(t)) {
            throw new ProcessEngineException("ProfileValueDelegate type must be BOOLEAN or STRING, not " + t);
        }
        // cleared first: a start variable, a form or an earlier answer never stands in for the profile value
        ex.setVariable(v, null);
        ex.setVariable(v + "Binding", null);
        ex.setVariable(v + "Problem", null);
        Map<String, Object> payload = Map.of("paths", p);
        String bk = ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId() : ex.getProcessBusinessKey();
        ConnectorResult r = PackRuntime.gateway().query(new ConnectorRequest(Vars.tenant(ex), CONNECTOR, OPERATION, null,
                bk, ex.getProcessInstanceId(), Vars.withDirective(ex, CONNECTOR, payload), Vars.hash(payload)));
        ex.setVariable(v + "Status", r == null ? OperationStatus.UNKNOWN.name() : r.status().name());
        Answer a = answer(r, p, t);
        if (a.problem() != null) {
            ex.setVariable(v + "Problem", a.problem());
            return;
        }
        ex.setVariable(v, a.value());
        Object binding = r.data().get("binding");
        ex.setVariable(v + "Binding", binding == null ? null : binding.toString());
    }

    /**
     * The value of {@code path} in a {@code profile.get} answer: present only when the call completed and its
     * {@code values} map holds the path with a {@link Boolean} ({@code BOOLEAN}) or a {@link String} ({@code STRING}).
     * Text such as "true" is not a boolean: the profile stores a boolean, so anything else is a wrong type.
     */
    public static Answer answer(ConnectorResult r, String path, String type) {
        if (r == null || r.status() != OperationStatus.COMPLETED) {
            return new Answer(null, "NOT_COMPLETED");
        }
        Object values = r.data().get("values");
        if (!(values instanceof Map<?, ?> m) || m.get(path) == null) {
            return new Answer(null, "MISSING");
        }
        Object value = m.get(path);
        boolean typed = "STRING".equals(type) ? value instanceof String s && !s.isBlank() : value instanceof Boolean;
        return typed ? new Answer(value, null) : new Answer(null, "WRONG_TYPE");
    }
}
