// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.ports;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound ports of the programme module. The module owns no organisation or geography model, no DMN engine, no
 * process starter and no message channel: the integrator binds these ports in {@code sangam-server}. Each port has a
 * "none" default so the module runs air-gapped (ARCHITECTURE §1.9).
 */
public final class ProgrammePorts {

    private ProgrammePorts() {
    }

    /**
     * The org or geo hierarchy along which targets are allocated and achievements roll up. Bound to WS-SECURITY's
     * {@code OrgUnitService} (effective-dated org units) or to a sangam-config classification tree (geography). Node ids
     * are opaque strings; levels are data (level types of the tenant).
     */
    public interface HierarchyPort {
        /** Direct children of {@code nodeId} (effective now). */
        List<String> children(Connection c, String tenantId, String nodeId);

        /** Parent of {@code nodeId}, empty at the root. */
        Optional<String> parent(Connection c, String tenantId, String nodeId);

        /** Level type of the node (e.g. a configured level name); used to pick reporting offices. */
        String level(Connection c, String tenantId, String nodeId);

        /** All nodes under {@code rootId} at {@code level} (the reporting offices of a campaign). */
        List<String> nodesAtLevel(Connection c, String tenantId, String rootId, String level);

        /** Seat or person responsible for a node (reminder recipient); empty if unknown. */
        default Optional<String> responsible(Connection c, String tenantId, String nodeId) {
            return Optional.empty();
        }

        HierarchyPort FLAT = new HierarchyPort() {
            @Override
            public List<String> children(Connection c, String tenantId, String nodeId) {
                return List.of();
            }

            @Override
            public Optional<String> parent(Connection c, String tenantId, String nodeId) {
                return Optional.empty();
            }

            @Override
            public String level(Connection c, String tenantId, String nodeId) {
                return "";
            }

            @Override
            public List<String> nodesAtLevel(Connection c, String tenantId, String rootId, String level) {
                return List.of(rootId);
            }
        };
    }

    /**
     * Starts one process per return of a campaign (GP-30 campaign per period, GP-09 one instance per office).
     * Bound to the engine adapter; runs in the caller's transaction (engine command) so the return row and the
     * process instance commit together (Q1).
     */
    public interface CampaignStarter {
        /** @return the process instance id */
        String start(Connection c, String tenantId, String processKey, String businessKey, Map<String, Object> variables);

        CampaignStarter NONE = (c, t, k, b, v) -> null;
    }

    /** A finding produced by an external validator (DMN decision table bound by the integrator). */
    public record ExternalFinding(String indicatorKey, String severity, String code, String message) {
    }

    /** External validation of a return, typically a DMN decision (N15 "validation DMN"). */
    public interface ReturnValidator {
        List<ExternalFinding> validate(Connection c, String tenantId, String decisionKey, Map<String, Object> inputs);

        ReturnValidator NONE = (c, t, k, in) -> List.of();
    }

    /** A reminder / notice to an office (defaulter lists and reminders, C15). */
    public record Reminder(String tenantId, String recipientRef, String orgUnit, String kind, String subjectRef,
                           Map<String, Object> attributes) {
    }

    /** Message channel (bound to the outbox + integration connectors, exactly once). */
    public interface Notifier {
        /** @return delivery reference (outbox id), written in the caller's transaction */
        String send(Connection c, Reminder reminder);

        Notifier NONE = (c, r) -> null;
    }

    /** One dashboard row pushed to C14 (WS-UI dashboards, analytics read model). */
    public record DashboardRow(String tenantId, String programmeId, String periodId, String orgUnit, BigDecimal physicalPct,
                               BigDecimal financialPct, BigDecimal gapPct, int returnsDue, int returnsAccepted, int returnsOverdue,
                               int openAlerts, Map<String, Object> indicators) {
    }

    /** Push side of the dashboard feed, beyond the outbox event; e.g. a direct analytics-store writer. */
    public interface DashboardPort {
        void publish(Connection c, DashboardRow row);

        DashboardPort NONE = (c, r) -> {
        };
    }

    /** Financial actuals source (e.g. sangam-ledger consumption or an external finance system connector). */
    public interface FinancialSource {
        /** Rows {@code head -> [allocated, released, committed, expended]} for a programme, period and node. */
        Map<String, BigDecimal[]> actuals(Connection c, String tenantId, String programmeId, String periodId, String orgUnit);

        FinancialSource NONE = (c, t, p, per, o) -> Map.of();
    }
}
