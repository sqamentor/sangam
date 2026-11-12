// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.util.ArrayList;
import java.util.List;

import org.sangam.rest.port.ApiProblem;

/**
 * SL-80: the one naming rule for correlation parameters (and a unique start on the business key) that the REST event
 * API declares as JSON pointers: the parameter is named by the pointer's segments, unescaped (RFC 6901: {@code ~1} is
 * {@code /}, {@code ~0} is {@code ~}) and joined with {@code .}, so {@code /billId} is {@code billId} and
 * {@code /bill/id} is {@code bill.id}. That dotted name is the event-registry convention (sangam-flowable-ports
 * {@code Json.at} reads {@code bill.id} as {@code /bill/id}) and the process variable the event correlates on.
 *
 * <p>Before, the ports bridge named a nested business key {@code bill/id}, its correlation parameters {@code bill.id},
 * and the JDBC event adapter only the last segment {@code id} (so {@code /a/id} and {@code /b/id} collided).
 *
 * <p>A segment that is empty or holds {@code .} or {@code /} has no unambiguous dotted name and is refused with
 * {@code 400 VALIDATION_FAILED} naming the field, instead of correlating on a different path.
 */
public final class CorrelationNames {

    private CorrelationNames() {
    }

    /**
     * The parameter name of {@code pointer} (a JSON pointer starting with {@code /}).
     *
     * @param field the request field, named in the problem when the pointer is refused
     */
    public static String fromPointer(String pointer, String field) {
        if (pointer == null || !pointer.startsWith("/") || pointer.length() == 1) {
            throw invalid(field, "'" + pointer + "' is not a JSON pointer to a field (it must start with '/')");
        }
        List<String> names = new ArrayList<>();
        for (String raw : pointer.substring(1).split("/", -1)) {
            String segment = raw.replace("~1", "/").replace("~0", "~");
            if (segment.isEmpty() || segment.contains(".") || segment.contains("/")) {
                throw invalid(field, "'" + pointer + "' has a segment that is empty or holds '.' or '/', so it has no"
                        + " unambiguous parameter name");
            }
            names.add(segment);
        }
        return String.join(".", names);
    }

    private static ApiProblem invalid(String field, String detail) {
        return ApiProblem.badRequest("VALIDATION_FAILED", field + ": " + detail).with("field", field);
    }
}
