// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.action;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Action models: named, versioned, reusable user-triggered behaviour. An action declares a label, a scope (case, process or task type),
 * a permission, an optional form and a target: start a BPMN process, call a REST path, or run a script through the out-of-process
 * worker (ADR-D-8). The permission check is on the server, every invocation has an idempotent operation id, and every UI gets the same result.
 */
public final class ActionModels {

    public enum Scope { CASE, PROCESS, TASK }

    public enum TargetType { BPMN, REST, SCRIPT }

    public record Action(String key, int version, String label, Scope scope, String scopeType, String permission, String formKey, TargetType targetType, String target) {
    }

    /** Runs the target. Binding: REST adapter for BPMN and REST, worker topic for SCRIPT. It receives the operation id as idempotency key. */
    @FunctionalInterface
    public interface Dispatcher {
        Map<String, Object> dispatch(String tenantId, UUID operationId, Action action, String targetRef, Map<String, Object> input);
    }

    /** Server-side check of the action's permission for this user and object. */
    @FunctionalInterface
    public interface Permission {
        boolean allowed(String tenantId, String user, String permission, String targetRef);
    }

    public record Invocation(UUID operationId, Map<String, Object> result, boolean replayed) {
    }

    private static final UUID NS = UUID.fromString("c1d6c6a0-6f3a-5a6b-8e39-6f0c55ad2e11");

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final Permission permissions;
    private final Dispatcher dispatcher;

    public ActionModels(Dialect dialect, Transactions tx, AuditChain audit, Permission permissions, Dispatcher dispatcher) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = audit;
        this.permissions = permissions;
        this.dispatcher = dispatcher;
    }

    public Action publish(String tenantId, String key, String label, Scope scope, String scopeType, String permission, String formKey, TargetType type, String target, String actor) {
        if (permission == null || permission.isBlank() || target == null || target.isBlank() || !key.matches("[A-Za-z0-9_.-]{1,120}")) {
            throw new IllegalArgumentException("an action needs a valid key, a permission and a target");
        }
        if (type == TargetType.REST && !target.startsWith("/")) {
            throw new IllegalArgumentException("a REST target is a path on this server");
        }
        return tx.inTransaction(c -> {
            int v = Jdbc.one(c, "SELECT COALESCE(MAX(version),0)+1 FROM MOD_ACTION WHERE tenant_id=? AND action_key=?", rs -> rs.getInt(1), tenantId, key).orElse(1);
            Action a = new Action(key, v, label, scope, scopeType, permission, formKey, type, target);
            Jdbc.update(c, "UPDATE MOD_ACTION SET active=0 WHERE tenant_id=? AND action_key=?", tenantId, key);
            Jdbc.update(c, "INSERT INTO MOD_ACTION(tenant_id,action_key,version,document,active,created_by,created_at) VALUES (?,?,?,?,1,?,?)", tenantId, key, v, Json.write(a), actor, Jdbc.dbNow(c, dialect));
            audit.append(c, tenantId, "actions:" + tenantId, "ACTION_PUBLISHED", Map.of("key", key, "version", v, "actor", actor));
            return a;
        });
    }

    /** The active actions for an object type that this user may invoke on the object. */
    public List<Action> available(String tenantId, String user, Scope scope, String scopeType, String targetRef, Predicate<Action> extraFilter) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT document FROM MOD_ACTION WHERE tenant_id=? AND active=1 ORDER BY action_key", rs -> Json.MAPPER.convertValue(Json.readMap(rs.getString(1)), Action.class), tenantId))
                .stream().filter(a -> a.scope() == scope && (a.scopeType() == null || a.scopeType().equalsIgnoreCase(scopeType)))
                .filter(a -> permissions.allowed(tenantId, user, a.permission(), targetRef)).filter(extraFilter).toList();
    }

    /**
     * Invokes the active version once per {@code clientKey}: a repeat returns the stored result. The row is claimed first, so concurrent
     * duplicates cannot both dispatch. A crash after the claim leaves a row with no result, reported as {@code ACTION_OUTCOME_UNKNOWN}.
     */
    public Invocation invoke(String tenantId, String user, String key, String targetRef, Map<String, Object> input, String clientKey) {
        Action action = tx.inTransaction(c -> Jdbc.one(c, "SELECT document FROM MOD_ACTION WHERE tenant_id=? AND action_key=? AND active=1", rs -> Json.MAPPER.convertValue(Json.readMap(rs.getString(1)), Action.class), tenantId, key))
                .orElseThrow(() -> new IllegalArgumentException("ACTION_NOT_FOUND"));
        if (!permissions.allowed(tenantId, user, action.permission(), targetRef)) {
            throw new SecurityException("ACTION_NOT_PERMITTED");
        }
        UUID op = Hashes.uuidV5(NS, String.join("|", tenantId, key, targetRef, user, clientKey));
        boolean claimed = tx.inTransaction(c -> {
            boolean first = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO MOD_ACTION_RUN(operation_id,tenant_id,action_key,version,target_ref,actor,created_at) VALUES (?,?,?,?,?,?,?)",
                    op.toString(), tenantId, key, action.version(), targetRef, user, Jdbc.dbNow(c, dialect));
            if (first) {
                audit.append(c, tenantId, "action:" + op, "ACTION_INVOKED", Map.of("key", key, "version", action.version(), "target", targetRef, "actor", user, "operationId", op.toString()));
            }
            return first;
        });
        if (!claimed) {
            Optional<String> stored = tx.inTransaction(c -> Jdbc.one(c, "SELECT result_json FROM MOD_ACTION_RUN WHERE operation_id=?", rs -> Optional.ofNullable(rs.getString(1)), op.toString())).orElse(Optional.empty());
            return new Invocation(op, Json.readMap(stored.orElseThrow(() -> new IllegalStateException("ACTION_OUTCOME_UNKNOWN: " + op))), true);
        }
        Map<String, Object> result = dispatcher.dispatch(tenantId, op, action, targetRef, input);
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE MOD_ACTION_RUN SET result_json=? WHERE operation_id=?", Json.write(result), op.toString()));
        return new Invocation(op, result, false);
    }

    /** All active actions of a tenant, for the administrator's list; no per-object permission filter. */
    public List<Action> listActive(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT document FROM MOD_ACTION WHERE tenant_id=? AND active=1 ORDER BY action_key", rs -> Json.MAPPER.convertValue(Json.readMap(rs.getString(1)), Action.class), tenantId));
    }

    /** Deactivates an action; past runs stay. Returns false when no active version existed. */
    public boolean retire(String tenantId, String key, String actor, String reason) {
        return tx.inTransaction(c -> {
            if (Jdbc.update(c, "UPDATE MOD_ACTION SET active=0 WHERE tenant_id=? AND action_key=? AND active=1", tenantId, key) == 0) {
                return false;
            }
            audit.append(c, tenantId, "actions:" + tenantId, "ACTION_RETIRED", Map.of("key", key, "actor", actor, "reason", reason));
            return true;
        });
    }
}
