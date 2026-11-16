// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.util.Collection;
import java.util.Map;

import org.sangam.core.engine.DocumentSlotNames;
import org.sangam.rest.port.ApiProblem;

/**
 * Owner decision 385 (2), layer 2, on the REST side: the caller's variables of every write path (task completion and
 * return, worker completion, variable set, resubmission, process start and modification, message correlation, signals,
 * case commands) are converted with {@link #callerVariables} (or their names passed to {@link #refuse}), which answers
 * {@code 422 DOCUMENT_SLOT_VARIABLE} for a declared DSC document slot ({@link DocumentSlotNames}) before anything is
 * written. Only the document route ({@link DocumentSlots}) sets a slot.
 */
public final class SlotVariableGuard {

    public static final String CODE = DocumentSlotNames.CODE;

    private SlotVariableGuard() {
    }

    /** {@link TypedVariables#fromJson} of a caller's variables, refused when one names a declared slot. */
    public static Map<String, Object> callerVariables(Map<String, Object> json) {
        Map<String, Object> vars = TypedVariables.fromJson(json);
        refuse(vars.keySet());
        return vars;
    }

    /** Refuses {@code 422 DOCUMENT_SLOT_VARIABLE} when any of {@code variableNames} is a declared slot. */
    public static void refuse(Collection<String> variableNames) {
        try {
            DocumentSlotNames.refuse(variableNames);
        } catch (DocumentSlotNames.Refused e) {
            throw ApiProblem.unprocessable(CODE, e.detail());
        }
    }
}
