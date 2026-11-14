// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner decision 304, the status-lookup fallback: a DLT SMS message whose delivery report did not arrive is looked up
 * through its connector ({@link DltSmsConnector#lookup}, a gateway that offers a status query) and the answer goes
 * through the same {@link DltDeliveryReports#apply} as a webhook report, so the state rules and the single
 * {@code NOTIFICATION_DELIVERED} are the same whichever path reports first.
 * <ol>
 *   <li><b>Enrol</b>: every accepted send of a DLT SMS connector with a lookup (class (a) or (b)) within
 *       {@code window} gets its {@code NOTIFICATION_DELIVERY} row (state ACCEPTED), first looked up after
 *       {@code firstLookupAfter}.</li>
 *   <li><b>Claim</b> (skip-locked, several nodes never look up one message at once): rows not final, due, with fewer than
 *       {@code maxLookups} lookups; each claim pushes the next lookup out by {@code lookupEvery}.</li>
 *   <li><b>Look up</b> outside any transaction, then apply the gateway's status with source LOOKUP.</li>
 * </ol>
 */
public final class DltDeliveryStatusPoller {

    private static final Logger LOG = LoggerFactory.getLogger(DltDeliveryStatusPoller.class);

    /**
     * @param firstLookupAfter wait after the send before the first lookup (the webhook usually reports first)
     * @param lookupEvery      wait between lookups of one message
     * @param window           sends older than this are not enrolled
     * @param maxLookups       lookups of one message before the fallback gives up on it
     * @param batchSize        messages per pass
     */
    public record Settings(Duration firstLookupAfter, Duration lookupEvery, Duration window, int maxLookups, int batchSize) {
        public static Settings defaults() {
            return new Settings(Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofDays(3), 12, 100);
        }

        public Settings {
            Objects.requireNonNull(firstLookupAfter, "firstLookupAfter");
            Objects.requireNonNull(lookupEvery, "lookupEvery");
            Objects.requireNonNull(window, "window");
            if (maxLookups < 1 || batchSize < 1) {
                throw new IllegalArgumentException("maxLookups and batchSize must be at least 1");
            }
        }
    }

    /** One message due for a lookup. */
    record Due(String tenantId, UUID operationId, String connectorId) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final ConnectorRegistry registry;
    private final ConnectorCallJournal journal;
    private final DltDeliveryReports reports;
    private final Settings settings;

    public DltDeliveryStatusPoller(Dialect dialect, Transactions tx, ConnectorRegistry registry, ConnectorCallJournal journal,
                                   DltDeliveryReports reports, Settings settings) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.settings = settings == null ? Settings.defaults() : settings;
    }

    /** One pass: enrol, claim, look up. Returns the number of messages looked up. */
    public int runOnce() {
        List<String> ids = lookupConnectors();
        if (ids.isEmpty()) {
            return 0;
        }
        enrol(ids);
        return lookUp(claim());
    }

    /** DLT SMS connectors that offer a status lookup. */
    List<String> lookupConnectors() {
        return registry.descriptors().stream()
                .filter(d -> DltSmsConnector.TYPE.equals(d.type()) && d.capabilityClass() != CapabilityClass.C_NEITHER)
                .map(ConnectorDescriptor::id).distinct().toList();
    }

    /** Creates the delivery rows of accepted sends that have none (portable INSERT .. SELECT .. NOT EXISTS). */
    int enrol(List<String> connectorIds) {
        String in = String.join(",", java.util.Collections.nCopies(connectorIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(settings.firstLookupAfter().toMillis());
        params.addAll(connectorIds);
        params.add(-settings.window().toMillis());
        String sql = "INSERT INTO NOTIFICATION_DELIVERY(tenant_id, operation_id, connector_id, root_id, message_id, state, state_rank,"
                + " reason, reported_at, source, reports, lookups, next_lookup_at, created_at, updated_at)"
                + " SELECT c.tenant_id, c.operation_id, c.connector_id, c.root_id, c.provider_ref, 'ACCEPTED', 1, NULL, NULL, 'LOOKUP', 0, 0, "
                + dialect.nowPlusMillisParam() + ", " + dialect.now() + ", " + dialect.now()
                + " FROM CONNECTOR_CALL c WHERE c.connector_id IN (" + in + ") AND c.operation='" + DltSmsConnector.OP_SEND
                + "' AND c.last_result='SUCCESS' AND c.updated_at > " + dialect.nowPlusMillisParam()
                + " AND NOT EXISTS (SELECT 1 FROM NOTIFICATION_DELIVERY d WHERE d.tenant_id = c.tenant_id AND d.operation_id = c.operation_id)";
        try {
            return tx.inTransaction(c -> Jdbc.update(c, sql, params.toArray()));
        } catch (RuntimeException e) {
            // another node enrolled the same send at the same moment (primary key); the next pass enrols the rest
            LOG.debug("delivery enrolment pass skipped: {}", e.getMessage());
            return 0;
        }
    }

    private List<Due> claim() {
        return tx.inTransaction(c -> {
            String where = "d.state_rank < " + DltDeliveryReports.FINAL_RANK + " AND d.lookups < " + settings.maxLookups()
                    + " AND d.next_lookup_at IS NOT NULL AND d.next_lookup_at <= " + dialect.now();
            List<Due> due = Jdbc.query(c, dialect.skipLockedSelect("d.tenant_id, d.operation_id, d.connector_id",
                    "NOTIFICATION_DELIVERY", "d", where, "d.next_lookup_at", settings.batchSize()),
                    rs -> new Due(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3)));
            for (Due d : due) {
                Jdbc.update(c, "UPDATE NOTIFICATION_DELIVERY SET lookups=lookups+1, next_lookup_at=" + dialect.nowPlusMillisParam()
                        + ", updated_at=" + dialect.now() + " WHERE tenant_id=? AND operation_id=?",
                        settings.lookupEvery().toMillis(), d.tenantId(), d.operationId());
            }
            return due;
        });
    }

    /** Looks each message up and applies the answer (package-private for tests: the claim is dialect SQL). */
    int lookUp(List<Due> due) {
        int n = 0;
        for (Due d : due) {
            try {
                lookUp(d);
                n++;
            } catch (RuntimeException e) {
                LOG.warn("delivery status lookup of operation {} failed; looked up again later", d.operationId(), e);
            }
        }
        return n;
    }

    private void lookUp(Due d) {
        Optional<ConnectorCallJournal.Call> call = tx.inTransaction(c -> journal.find(c, d.operationId()));
        Optional<SangamConnector> conn = registry.find(d.tenantId(), d.connectorId());
        if (call.isEmpty() || conn.isEmpty()) {
            return;
        }
        ConnectorCallJournal.Call cc = call.get();
        ConnectorRequest req = new ConnectorRequest(cc.tenantId(), cc.operationId(), "delivery-status", cc.attempts(), cc.rootId(),
                cc.businessKey(), cc.operation(), cc.requestJson() == null ? Map.of() : Json.readMap(cc.requestJson()),
                Instant.now().plusSeconds(30));
        LookupResult lr = conn.get().lookup(req);
        String status = switch (lr) {
            case LookupResult.Found f -> f.output() == null || f.output().get("status") == null ? null
                    : String.valueOf(f.output().get("status")).toUpperCase(Locale.ROOT);
            case LookupResult.NotFound nf -> null;
            case LookupResult.Unavailable u -> null;
            case LookupResult.NotSupported ns -> {
                stop(d);
                yield null;
            }
        };
        if (status == null || DltDeliveryReports.rank(status) < 0) {
            return;
        }
        String messageId = lr instanceof LookupResult.Found f ? f.providerRef() : null;
        String reason = lr instanceof LookupResult.Found f && !f.succeeded() ? f.failureCode() : null;
        reports.apply(d.tenantId(), d.connectorId(), new DltDeliveryReports.Report(null, cc.operationId().toString(), messageId,
                status, reason, null), DltDeliveryReports.Source.LOOKUP);
    }

    /** The connector has no lookup after all: no further lookups of this message. */
    private void stop(Due d) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE NOTIFICATION_DELIVERY SET next_lookup_at=NULL, updated_at=" + dialect.now()
                + " WHERE tenant_id=? AND operation_id=?", d.tenantId(), d.operationId()));
    }
}
