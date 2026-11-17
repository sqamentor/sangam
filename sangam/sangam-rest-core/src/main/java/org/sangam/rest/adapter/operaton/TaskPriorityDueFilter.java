// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.Instant;
import java.util.Date;

import org.operaton.bpm.engine.task.TaskQuery;
import org.sangam.rest.port.TaskPort;

/**
 * Lane R9-filters: the priority and due-date criteria of {@code GET /api/v1/tasks}, added to the engine task query
 * after its tenant and visibility predicate (so they only narrow). Operaton renders them for every supported database.
 */
final class TaskPriorityDueFilter {

    private TaskPriorityDueFilter() {
    }

    /** Adds the criteria of {@code f} to {@code q}; returns {@code q}. */
    static TaskQuery apply(TaskQuery q, TaskPort.TaskFilter f) {
        if (f.priority() != null) {
            q.taskPriority(f.priority());
        }
        if (f.minPriority() != null) {
            q.taskMinPriority(f.minPriority());
        }
        if (f.maxPriority() != null) {
            q.taskMaxPriority(f.maxPriority());
        }
        // One upper bound: the earlier of dueBefore and (overdue) the server's now; the query keeps one dueBefore.
        Instant before = f.dueBefore();
        if (Boolean.TRUE.equals(f.overdue())) {
            Instant now = Instant.now();
            before = before == null || now.isBefore(before) ? now : before;
        }
        if (before != null) {
            q.dueBefore(Date.from(before));
        }
        if (f.dueAfter() != null) {
            q.dueAfter(Date.from(f.dueAfter()));
        }
        return q;
    }
}
