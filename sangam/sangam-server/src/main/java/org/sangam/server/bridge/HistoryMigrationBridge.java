// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Map;
import java.util.TreeMap;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.history.HistoricInstanceMigration;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;

/**
 * Owner decision 258 (FC-18): the REST route over the engine's {@link HistoricInstanceMigration}. The bridge maps
 * shapes and errors only; every rule (ended instance, same tenant, later version of the same key, no removal time, no
 * legal hold, the rewrite and its PROCESS_HISTORY_MIGRATED audit event in one transaction) is the engine's. The actor
 * is the authenticated {@link SecurityIdentity} of the request, never a body field. Codes map to the contract
 * {@code routes/history_migration.py}: VALIDATION_FAILED 400, NOT_FOUND 404, HISTORY_INSTANCE_NOT_ENDED,
 * HISTORY_REMOVAL_TIME_SET and LEGAL_HOLD_ACTIVE 409, HISTORY_MIGRATION_TARGET_INVALID 422; an unknown code stays a
 * server error.
 */
public final class HistoryMigrationBridge {

    /** The contract's {@code HistoryInstanceMigrationResult}. */
    public record HistoryInstanceMigrationResult(String processInstanceId, String fromDefinitionId,
                                                 String toDefinitionId, int fromVersion, int toVersion,
                                                 Map<String, Integer> rowsByTable) {
    }

    /** The engine call; {@link #of(HistoricInstanceMigration)} for the real engine. */
    @FunctionalInterface
    public interface Migrations {
        HistoricInstanceMigration.Result migrate(SecurityIdentity actor, String processInstanceId,
                                                 String targetDefinitionId, String reason);

        static Migrations of(HistoricInstanceMigration engine) {
            return engine::migrate;
        }
    }

    private final Migrations engine;

    public HistoryMigrationBridge(Migrations engine) {
        this.engine = engine;
    }

    public HistoryInstanceMigrationResult migrate(RequestContext ctx, String processInstanceId,
                                                  String targetDefinitionId, String reason) {
        SecurityIdentity actor = SecurityIdentities.of(ctx);
        HistoricInstanceMigration.Result r;
        try {
            r = engine.migrate(actor, processInstanceId, targetDefinitionId, reason);
        } catch (RuntimeException e) {
            throw problem(e);
        }
        return new HistoryInstanceMigrationResult(r.processInstanceId(), r.fromDefinitionId(), r.toDefinitionId(),
                r.fromVersion(), r.toVersion(), new TreeMap<>(r.rowsByTable()));
    }

    /** The REST problem for an engine refusal (the cause chain is searched, so a wrapped refusal maps the same). */
    static RuntimeException problem(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ApiProblem p) {
                return p;
            }
            if (t instanceof HistoricInstanceMigration.HistoryMigrationException h) {
                String detail = h.getMessage();
                String prefix = h.code() + ": ";
                if (detail != null && detail.startsWith(prefix)) {
                    detail = detail.substring(prefix.length());
                }
                return switch (h.code()) {
                    case HistoricInstanceMigration.VALIDATION_FAILED -> ApiProblem.badRequest(h.code(), detail);
                    case HistoricInstanceMigration.NOT_FOUND -> new ApiProblem(404, h.code(), detail);
                    case HistoricInstanceMigration.NOT_ENDED, HistoricInstanceMigration.REMOVAL_TIME_SET,
                         HistoricInstanceMigration.LEGAL_HOLD_ACTIVE -> ApiProblem.conflict(h.code(), detail);
                    case HistoricInstanceMigration.TARGET_INVALID -> ApiProblem.unprocessable(h.code(), detail);
                    default -> new IllegalStateException("decision 258: unknown history migration code " + h.code(), h);
                };
            }
        }
        return e;
    }
}
