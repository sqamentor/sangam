// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.List;

import org.sangam.ports.sla.SlaService;
import org.sangam.rest.port.AnalyticsPort;
import org.sangam.rest.port.InsightsPort;
import org.sangam.rest.port.RequestContext;

/**
 * {@link InsightsPort} with the SLA figures from sangam-flowable-ports' {@link SlaService} (R79, EC-08: the SLA clocks
 * kept in {@code SLA_INSTANCE}, aggregated per process definition key and SLA key; tenant-scoped). Everything else
 * (throughput, conformance, duration report, alert rules, agent usage) delegates to the default
 * {@code OperatonInsightsAdapter}.
 *
 * <p>Mapping of {@link SlaService.SlaStats} to {@link InsightsPort.SlaRow}: {@code key} = SLA key, {@code name} =
 * process definition key, {@code withinSla} = clocks MET, {@code warning} = clocks that passed their warning
 * threshold, {@code breached} = clocks breached, {@code avgBusinessDays} = mean elapsed time of stopped clocks in days
 * with SLA pauses excluded ({@code avgElapsedMillis}; the working calendar is already applied when the due date is
 * computed, so the elapsed figure is wall time minus pauses). A {@code processDefinitionKey} filter selects one
 * definition.
 */
public final class PortsInsightsBridge implements InsightsPort {

    private static final double MILLIS_PER_DAY = 86_400_000d;

    private final SlaService sla;
    private final InsightsPort fallback;

    public PortsInsightsBridge(SlaService sla, InsightsPort fallback) {
        this.sla = sla;
        this.fallback = fallback;
    }

    @Override
    public List<SlaRow> sla(RequestContext ctx, AnalyticsPort.Range range) {
        AnalyticsPort.Range r = range == null ? new AnalyticsPort.Range(null, null, null) : range;
        List<SlaService.SlaStats> stats = PortsErrors.call(() -> sla.stats(ctx.tenantId(), r.from(), r.to()));
        List<SlaRow> out = new ArrayList<>();
        for (SlaService.SlaStats s : stats) {
            if (r.processDefinitionKey() != null && !r.processDefinitionKey().equals(s.definitionKey())) {
                continue;
            }
            Double days = s.avgElapsedMillis() == null ? null
                    : Math.round(s.avgElapsedMillis() / MILLIS_PER_DAY * 100d) / 100d;
            out.add(new SlaRow(s.slaKey(), s.definitionKey(), s.total(), s.met(), s.warned(), s.breached(), days));
        }
        return out;
    }

    @Override
    public List<TimePoint> throughput(RequestContext ctx, AnalyticsPort.Range range, String interval) {
        return fallback.throughput(ctx, range, interval);
    }

    @Override
    public ConformanceResult conformance(RequestContext ctx, AnalyticsPort.Range range) {
        return fallback.conformance(ctx, range);
    }

    @Override
    public List<DurationReportRow> durationReport(RequestContext ctx, AnalyticsPort.Range range, String period) {
        return fallback.durationReport(ctx, range, period);
    }

    @Override
    public List<AlertRule> alerts(RequestContext ctx) {
        return fallback.alerts(ctx);
    }

    @Override
    public AlertRule createAlert(RequestContext ctx, AlertRule rule) {
        return fallback.createAlert(ctx, rule);
    }

    @Override
    public AlertRule updateAlert(RequestContext ctx, String id, AlertRule rule) {
        return fallback.updateAlert(ctx, id, rule);
    }

    @Override
    public void deleteAlert(RequestContext ctx, String id) {
        fallback.deleteAlert(ctx, id);
    }

    @Override
    public List<AgentUsage> agentUsage(RequestContext ctx, AnalyticsPort.Range range) {
        return fallback.agentUsage(ctx, range);
    }
}
