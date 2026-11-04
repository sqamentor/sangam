// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;

/**
 * Analytics beyond the core set (R65 throughput, duration reports PF-07 and threshold alerts; R79 SLA; R66
 * conformance against the deployed BPMN; R72 agent usage). Tenant-scoped; hidden records are never counted.
 */
public interface InsightsPort {

    record TimePoint(Instant t, long started, long completed) {
    }

    record SlaRow(String key, String name, long total, long withinSla, long warning, long breached,
                  Double avgBusinessDays) {
    }

    /** kind: SKIPPED | INSERTED | ORDER. */
    record Deviation(String activityId, String kind, long count) {
    }

    /** fitness 0..1 over replayed traces; fixtureId names the conformance fixture used (R66 "no mining claim without fixtures"). */
    record ConformanceResult(double fitness, Double precision, List<Deviation> deviations, String fixtureId) {
    }

    record DurationReportRow(String period, long count, long avgMs, long minMs, long maxMs) {
    }

    /** operator: &gt; | &lt; | &gt;= | &lt;=. channel: IN_APP | EMAIL | WEBHOOK. */
    record AlertRule(String id, String name, String kpi, String operator, double threshold, String channel,
                     List<String> recipients, boolean active) {
    }

    record AgentUsage(String agent, long runs, long tokens, double costUnits, Double budget, long guardrailBlocks,
                      long humanReviews) {
    }

    /** interval: hour | day | week | month. */
    List<TimePoint> throughput(RequestContext ctx, AnalyticsPort.Range range, String interval);

    List<SlaRow> sla(RequestContext ctx, AnalyticsPort.Range range);

    ConformanceResult conformance(RequestContext ctx, AnalyticsPort.Range range);

    /** period: day | week | month. */
    List<DurationReportRow> durationReport(RequestContext ctx, AnalyticsPort.Range range, String period);

    List<AlertRule> alerts(RequestContext ctx);

    AlertRule createAlert(RequestContext ctx, AlertRule rule);

    AlertRule updateAlert(RequestContext ctx, String id, AlertRule rule);

    void deleteAlert(RequestContext ctx, String id);

    List<AgentUsage> agentUsage(RequestContext ctx, AnalyticsPort.Range range);
}
