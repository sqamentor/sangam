// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.tenant;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.repository.Deployment;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.tenant.ExecutionTenantPolicy;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineComponents;
import org.sangam.engine.SangamProcessEnginePlugin;

/**
 * Decision 144 with owner answer 197: the shared-deployment registry ({@code SHARED_DEPLOYMENT}, changeset
 * {@code 0045-06-shared_deployment}), the only proof that an untenanted engine deployment is genuinely shared
 * (system-owned) work. Without an active row, untenanted work fails closed ({@link WorkTenants}).
 *
 * <p>Every change runs in one engine transaction on the engine connection: the row and its audit event
 * ({@value #REGISTERED} or {@value #REVOKED}, class B, chain root {@code platform:shared-deployment:<id>}, written under
 * the configured system audit scope) commit or roll back together. Rules (W4-M contract {@code routes/shared_deployments.py},
 * W4-E ACK 2026-11-13T01:46:14Z):
 * <ul>
 *   <li>the caller is a platform deployer: a non-system identity in the platform marker group
 *       {@value #PLATFORM_MARKER} and in {@value #DEPLOYER_GROUP} (defence in depth behind the server's
 *       {@code platform.shared-deployment.manage}; the actor recorded is the identity's person id, never a body field);</li>
 *   <li>{@code deploymentId} 1..64 characters, {@code reason} 1..1000 characters ({@code VALIDATION_FAILED});</li>
 *   <li>the id is an engine deployment without a tenant ({@code SHARED_DEPLOYMENT_INVALID} otherwise: a tenant-owned
 *       deployment never needs, and must never get, the shared classification);</li>
 *   <li>an active row refuses a second registration ({@value #REGISTERED}); a revoked id is never registered again and
 *       is not revoked twice ({@value #REVOKED}); revoking an id never registered is {@code NOT_FOUND}.</li>
 * </ul>
 * The registry is platform-wide: no tenant column, no tenant filter.
 */
public final class SharedDeployments {

    /** Audit event and 409 code of a registration. */
    public static final String REGISTERED = "SHARED_DEPLOYMENT_REGISTERED";
    /** Audit event of a revocation; 409 code of a second registration or revocation of a revoked id. */
    public static final String REVOKED = "SHARED_DEPLOYMENT_REVOKED";
    /** 422: not an engine deployment, or a tenant-owned one. */
    public static final String INVALID = "SHARED_DEPLOYMENT_INVALID";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String NOT_PERMITTED = "ACTION_NOT_PERMITTED";

    /** Platform-issuer marker group (equal to the server's PlatformIssuers marker, SL-70). */
    public static final String PLATFORM_MARKER = "sangam-platform";
    /** Platform deployer group (equal to SecurityPermissionBridge.GROUP_PLATFORM_DEPLOYER, decision 197). */
    public static final String DEPLOYER_GROUP = "sangam-platform-deployer";

    static final int MAX_ID = 64;
    static final int MAX_REASON = 1000;

    /** One registry row; {@code revokedAt == null} means active. */
    public record Registration(String deploymentId, String registeredBy, String reason, Instant registeredAt,
                               String revokedBy, Instant revokedAt, String revokeReason) {
        public boolean active() {
            return revokedAt == null;
        }
    }

    /** A refused registry change; {@link #code()} is the REST problem code. */
    public static final class SharedDeploymentException extends RuntimeException {
        private final String code;

        public SharedDeploymentException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final String COLUMNS = "deployment_id, registered_by, reason, registered_at, revoked_by, revoked_at,"
            + " revoke_reason";

    private final OperatonEngineAdapter adapter;
    private final SangamEngineComponents components;
    private final ProcessEngine engine;

    public SharedDeployments(OperatonEngineAdapter adapter) {
        this.adapter = adapter;
        this.engine = adapter.processEngine();
        this.components = componentsOf(engine);
    }

    private static SangamEngineComponents componentsOf(ProcessEngine engine) {
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        return cfg.getProcessEnginePlugins().stream().filter(SangamProcessEnginePlugin.class::isInstance)
                .map(p -> ((SangamProcessEnginePlugin) p).components()).findFirst()
                .orElseThrow(() -> new IllegalStateException("decision 144: not a Sangam engine (no SangamProcessEnginePlugin)"));
    }

    /** Registers {@code deploymentId} as a shared deployment; returns the new row. */
    public Registration register(SecurityIdentity actor, String deploymentId, String reason) {
        String by = requirePlatformDeployer(actor);
        requireLength("deploymentId", deploymentId, MAX_ID);
        requireLength("reason", reason, MAX_REASON);
        return adapter.inEngineTransaction(c -> {
            Optional<Registration> existing = find(c, deploymentId);
            if (existing.isPresent()) {
                throw existing.get().active()
                        ? new SharedDeploymentException(REGISTERED, "the deployment is already registered as shared")
                        : new SharedDeploymentException(REVOKED, "the deployment was revoked; a revoked id is never registered again");
            }
            Deployment d = engine.getRepositoryService().createDeploymentQuery().deploymentId(deploymentId).singleResult();
            if (d == null) {
                throw new SharedDeploymentException(INVALID, "not an engine deployment");
            }
            if (ExecutionTenantPolicy.normalise(d.getTenantId()) != null) {
                throw new SharedDeploymentException(INVALID, "the deployment is tenant-owned");
            }
            Instant now = components.clock().now();
            Jdbc.update(c, "INSERT INTO SHARED_DEPLOYMENT (deployment_id, registered_by, reason, registered_at)"
                    + " VALUES (?, ?, ?, ?)", deploymentId, by, reason, now);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("deploymentId", deploymentId);
            p.put("actor", by);
            p.put("reason", reason);
            p.put("registeredAt", now.toString());
            audit().append(c, scope(), root(deploymentId), REGISTERED, p);
            return new Registration(deploymentId, by, reason, now, null, null, null);
        });
    }

    /** Revokes the registration of {@code deploymentId} (final); returns the revoked row. */
    public Registration revoke(SecurityIdentity actor, String deploymentId, String reason) {
        String by = requirePlatformDeployer(actor);
        requireLength("deploymentId", deploymentId, MAX_ID);
        requireLength("reason", reason, MAX_REASON);
        return adapter.inEngineTransaction(c -> {
            Registration r = find(c, deploymentId)
                    .orElseThrow(() -> new SharedDeploymentException(NOT_FOUND, "the deployment was never registered"));
            if (!r.active()) {
                throw new SharedDeploymentException(REVOKED, "the registration is already revoked");
            }
            Instant now = components.clock().now();
            if (Jdbc.update(c, "UPDATE SHARED_DEPLOYMENT SET revoked_by = ?, revoked_at = ?, revoke_reason = ?"
                    + " WHERE deployment_id = ? AND revoked_at IS NULL", by, now, reason, deploymentId) != 1) {
                throw new SharedDeploymentException(REVOKED, "the registration was revoked concurrently");
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("deploymentId", deploymentId);
            p.put("actor", by);
            p.put("reason", reason);
            p.put("revokedAt", now.toString());
            p.put("registeredBy", r.registeredBy());
            p.put("registeredAt", r.registeredAt().toString());
            audit().append(c, scope(), root(deploymentId), REVOKED, p);
            return new Registration(deploymentId, r.registeredBy(), r.reason(), r.registeredAt(), by, now, reason);
        });
    }

    /** One row, active or revoked. */
    public Optional<Registration> find(String deploymentId) {
        return adapter.inEngineTransaction(c -> find(c, deploymentId));
    }

    /** The platform-wide registry, ordered by registration time then id; revoked rows only when asked. */
    public List<Registration> list(boolean includeRevoked) {
        return adapter.inEngineReadTransaction(c -> Jdbc.query(c, "SELECT " + COLUMNS + " FROM SHARED_DEPLOYMENT"
                + (includeRevoked ? "" : " WHERE revoked_at IS NULL") + " ORDER BY registered_at, deployment_id",
                SharedDeployments::row));
    }

    /** The row of {@code deploymentId} on {@code c}. */
    public static Optional<Registration> find(Connection c, String deploymentId) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM SHARED_DEPLOYMENT WHERE deployment_id = ?",
                SharedDeployments::row, deploymentId);
    }

    /** Whether {@code deploymentId} has an active (not revoked) row on {@code c}; null or blank ids never do. */
    public static boolean isActive(Connection c, String deploymentId) {
        if (deploymentId == null || deploymentId.isBlank()) {
            return false;
        }
        return Jdbc.one(c, "SELECT deployment_id FROM SHARED_DEPLOYMENT WHERE deployment_id = ? AND revoked_at IS NULL",
                rs -> rs.getString(1), deploymentId).isPresent();
    }

    /** The audit chain root of a deployment's registry events. */
    public static String root(String deploymentId) {
        return "platform:shared-deployment:" + deploymentId;
    }

    private static Registration row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Registration(rs.getString("deployment_id"), rs.getString("registered_by"), rs.getString("reason"),
                Jdbc.instant(rs, "registered_at"), rs.getString("revoked_by"), Jdbc.instant(rs, "revoked_at"),
                rs.getString("revoke_reason"));
    }

    private AuditSink audit() {
        return components.auditSink();
    }

    private String scope() {
        return components.workTenants().systemAuditScope();
    }

    static String requirePlatformDeployer(SecurityIdentity actor) {
        if (actor == null || actor.system() || !actor.groups().containsAll(Set.of(PLATFORM_MARKER, DEPLOYER_GROUP))) {
            throw new SharedDeploymentException(NOT_PERMITTED, "only a platform deployer registers or revokes a shared deployment");
        }
        return actor.personId();
    }

    static void requireLength(String field, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new SharedDeploymentException(VALIDATION_FAILED, field + " must be 1.." + max + " characters");
        }
    }
}
