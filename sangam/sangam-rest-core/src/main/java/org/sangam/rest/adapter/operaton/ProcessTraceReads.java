// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort.ProcessInstance;
import org.sangam.rest.support.TxSupport;

/**
 * M-044: the read side of {@code ProcessInstance.traceId} for {@link OperatonProcessAdapter}: the recorded starting
 * trace id of each instance ({@code PROCESS_TRACE}, written by the engine in the starting command). An instance without
 * a row, or with a stored value that is not a valid trace id, has none.
 */
final class ProcessTraceReads {

    /** W3C trace id: 32 lowercase hex, not all zeros (contract ProcessInstance.traceId). */
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    private ProcessTraceReads() {
    }

    /** The trace id of one instance, or null. */
    static String of(TxSupport tx, String processInstanceId) {
        return byIds(tx, List.of(processInstanceId)).get(processInstanceId);
    }

    /** The page with each instance's trace id filled in, read with one query for the whole page. */
    static Page<ProcessInstance> fill(TxSupport tx, Page<ProcessInstance> page) {
        Map<String, String> traces = byIds(tx, page.items().stream().map(ProcessInstance::id).toList());
        return new Page<>(page.items().stream().map(p -> p.withTraceId(traces.get(p.id()))).toList(), page.total(),
                page.capped());
    }

    /** The valid trace ids of the given instances, by instance id (one IN query). */
    static Map<String, String> byIds(TxSupport tx, Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<String[]> rows = tx.withConnection(c -> Jdbc.query(c,
                "SELECT process_instance_id, trace_id FROM PROCESS_TRACE WHERE process_instance_id IN (" + marks + ")",
                rs -> new String[]{rs.getString(1), rs.getString(2)}, ids.toArray()));
        Map<String, String> out = new HashMap<>();
        for (String[] r : rows) {
            if (r[1] != null && TRACE_ID.matcher(r[1]).matches() && !r[1].chars().allMatch(ch -> ch == '0')) {
                out.put(r[0], r[1]);
            }
        }
        return out;
    }
}
