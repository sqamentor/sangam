// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;

/**
 * Analytics and process mining (R15 XES/OCEL export, R63 heatmap/duration overlay, R65 KPIs, R66 variants, UP-07).
 * Every figure is tenant-scoped and authorized; a dashboard never reveals counts of hidden records.
 */
public interface AnalyticsPort {

    record Range(String processDefinitionKey, Instant from, Instant to) {
    }

    record Summary(String processDefinitionKey, Instant from, Instant to, long started, long completed, long active,
                   long cancelled, long openIncidents, Long avgDurationMs, Long p50DurationMs, Long p95DurationMs) {
    }

    record ActivityStat(String activityId, String activityName, String activityType, long count, long active,
                        Long avgDurationMs, Long maxDurationMs, long incidents) {
    }

    record TaskStat(String taskDefinitionKey, String name, long completed, long open, Long avgDurationMs,
                    Long p95DurationMs) {
    }

    record Variant(List<String> activities, long count, double share, Long avgDurationMs) {
    }

    Summary summary(RequestContext ctx, Range range);

    List<ActivityStat> activities(RequestContext ctx, Range range);

    List<TaskStat> tasks(RequestContext ctx, Range range);

    List<Variant> variants(RequestContext ctx, Range range, int limit);

    /** IEEE 1849 XES (application/xml). */
    Download exportXes(RequestContext ctx, Range range, int maxTraces);

    /** OCEL 2.0 JSON (objects: process instance, task, business key; events: activity instances). */
    Download exportOcel(RequestContext ctx, Range range, int maxTraces);
}
