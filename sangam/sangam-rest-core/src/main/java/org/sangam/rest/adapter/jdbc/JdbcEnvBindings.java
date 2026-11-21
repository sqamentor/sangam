// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PackagePort.EnvBinding;
import org.sangam.rest.port.PackagePort.EnvBindingApproval;
import org.sangam.rest.port.PackagePort.EnvBindingRequest;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.PackageSigning;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;

/**
 * M-074 (X.2.4.5): the {@code ENV_BINDING} rows (changeset 0043-02) and the three {@code /env-bindings} operations of
 * {@link JdbcPackageAdapter}. A binding is versioned per (tenant, env, name): every change is a new PENDING version;
 * approving it makes it APPROVED and RETIRES the APPROVED version before it, so at most one version is in force.
 * Approval is four-eyes (approver differs from the person who set the version); in production the approver also
 * differs from every deployer of a package in that environment whose manifest names the binding (X.2.4.5). The
 * static row helpers run on the caller's connection, inside its transaction.
 */
final class JdbcEnvBindings {

    static final String PENDING = "PENDING";
    static final String APPROVED = "APPROVED";
    static final String RETIRED = "RETIRED";
    static final Set<String> STATES = Set.of(PENDING, APPROVED, RETIRED);

    private static final String COLS = "tenant_id, env, name, version, value_ref, state, created_by, created_at,"
            + " approved_by, approved_at, reason";

    private static EnvBinding row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EnvBinding(rs.getString("tenant_id"), rs.getString("env"), rs.getString("name"), rs.getInt("version"),
                rs.getString("value_ref"), rs.getString("state"), rs.getString("created_by"),
                Jdbc.instant(rs, "created_at"), rs.getString("approved_by"), Jdbc.instant(rs, "approved_at"),
                rs.getString("reason"));
    }

    /**
     * The latest version per (env, name) among the rows passing the filters (each may be null): without {@code state}
     * the newest version of each binding, with it the newest version in that state (for APPROVED: the one in force).
     */
    static List<EnvBinding> list(Connection c, String tenant, String env, String name, String state) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM ENV_BINDING WHERE tenant_id=?");
        List<Object> params = new ArrayList<>(List.of(tenant));
        if (env != null) {
            sql.append(" AND env=?");
            params.add(env);
        }
        if (name != null) {
            sql.append(" AND name=?");
            params.add(name);
        }
        if (state != null) {
            sql.append(" AND state=?");
            params.add(state);
        }
        sql.append(" ORDER BY env, name, version DESC");
        Map<String, EnvBinding> latest = new LinkedHashMap<>();
        for (EnvBinding b : Jdbc.query(c, sql.toString(), JdbcEnvBindings::row, params.toArray())) {
            latest.putIfAbsent(b.env() + "\u0000" + b.name(), b);
        }
        return List.copyOf(latest.values());
    }

    static Optional<EnvBinding> find(Connection c, String tenant, String env, String name, int version) {
        return Jdbc.one(c, "SELECT " + COLS + " FROM ENV_BINDING WHERE tenant_id=? AND env=? AND name=? AND version=?",
                JdbcEnvBindings::row, tenant, env, name, version);
    }

    /** Inserts the next version as PENDING. A concurrent insert of the same version fails on the primary key. */
    static EnvBinding insertPending(Connection c, String tenant, String env, String name, String valueRef, String createdBy,
                                    String reason, Instant now) {
        int version = Jdbc.one(c, "SELECT MAX(version) FROM ENV_BINDING WHERE tenant_id=? AND env=? AND name=?",
                rs -> rs.getInt(1), tenant, env, name).orElse(0) + 1;
        Jdbc.update(c, "INSERT INTO ENV_BINDING(" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)", tenant, env, name, version,
                valueRef, PENDING, createdBy, now, null, null, reason);
        return new EnvBinding(tenant, env, name, version, valueRef, PENDING, createdBy, now, null, null, reason);
    }

    /**
     * Approves a PENDING version: the APPROVED version of the same binding becomes RETIRED, this one APPROVED. False
     * when the version was no longer PENDING (another approval won). The row keeps the reason it was set with; the
     * approval's reason is in the audit event.
     */
    static boolean approve(Connection c, String tenant, String env, String name, int version, String approvedBy,
                           Instant now) {
        int n = Jdbc.update(c, "UPDATE ENV_BINDING SET state=?, approved_by=?, approved_at=? WHERE tenant_id=?"
                + " AND env=? AND name=? AND version=? AND state=?", APPROVED, approvedBy, now, tenant, env, name,
                version, PENDING);
        if (n == 0) {
            return false;
        }
        Jdbc.update(c, "UPDATE ENV_BINDING SET state=? WHERE tenant_id=? AND env=? AND name=? AND state=? AND version<>?",
                RETIRED, tenant, env, name, APPROVED, version);
        return true;
    }

    /** The binding names with an APPROVED version in {@code env}. */
    static Set<String> approvedNames(Connection c, String tenant, String env) {
        return new TreeSet<>(Jdbc.query(c, "SELECT name FROM ENV_BINDING WHERE tenant_id=? AND env=? AND state=?",
                rs -> rs.getString(1), tenant, env, APPROVED));
    }

    // ------------------------------------------------------------------ the three REST operations

    /** Deployers (principal ids) of the tenant's packages deployed in an environment whose manifest names a binding. */
    @FunctionalInterface
    interface Deployers {
        Set<String> of(String tenant, String env, String bindingName);
    }

    private final TxSupport tx;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final PackageSigning signing;
    private final Deployers deployers;

    JdbcEnvBindings(TxSupport tx, PermissionPort permissions, AuditPort audit, PackageSigning signing, Deployers deployers) {
        this.tx = tx;
        this.permissions = permissions;
        this.audit = audit;
        this.signing = signing;
        this.deployers = deployers;
    }

    List<EnvBinding> list(RequestContext ctx, String env, String name, String state) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        if (state != null && !STATES.contains(state)) {
            throw JdbcSupport.invalid("state", "state must be one of " + new TreeSet<>(STATES));
        }
        return tx.withReadConnection(c -> list(c, JdbcSupport.tenant(ctx), env, name, state));
    }

    EnvBinding set(RequestContext ctx, String name, EnvBindingRequest request) {
        permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
        if (!PackageManifests.validBindingName(name)) {
            throw JdbcSupport.invalid("name", "binding name must be 1..128 of [A-Za-z0-9._-]");
        }
        String env = environment(JdbcSupport.required(request.env(), "env"));
        String valueRef = JdbcSupport.required(request.valueRef(), "valueRef").strip();
        requireReference(valueRef);
        String reason = JdbcSupport.required(request.reason(), "reason");
        String tenant = JdbcSupport.tenant(ctx);
        return tx.inTx(() -> {
            EnvBinding b;
            try {
                b = tx.withConnection(c -> insertPending(c, tenant, env, name, valueRef, ctx.principalId(), reason,
                        Jdbc.dbNow(c, tx.dialect())));
            } catch (Jdbc.LayerSqlException e) {
                if (tx.dialect().isUniqueViolation(e.sql())) {
                    throw ApiProblem.conflict("ENV_BINDING_CONFLICT", "Another version of " + name + " was set at the "
                            + "same time; retry");
                }
                throw e;
            }
            audit.record(ctx, chain(env, name), "ENV_BINDING_SET", JdbcSupport.payload("env", env, "name", name,
                    "version", b.version(), "valueRef", valueRef, "reason", reason, "actor", ctx.actor()));
            return b;
        });
    }

    EnvBinding approve(RequestContext ctx, String name, EnvBindingApproval approval) {
        permissions.require(ctx, Permissions.PACKAGE_APPROVE);
        String env = environment(JdbcSupport.required(approval.env(), "env"));
        int version = JdbcSupport.requiredObj(approval.version(), "version");
        String reason = JdbcSupport.required(approval.reason(), "reason");
        String tenant = JdbcSupport.tenant(ctx);
        return tx.inTx(() -> tx.withConnection(c -> {
            EnvBinding b = find(c, tenant, env, name, version).orElseThrow(() ->
                    ApiProblem.notFound("EnvBinding", env + "/" + name + "/" + version));
            if (!PENDING.equals(b.state())) {
                throw ApiProblem.conflict("INVALID_TRANSITION", "Only a PENDING binding version can be approved; it is "
                        + b.state()).with("state", b.state());
            }
            if (java.util.Objects.equals(ctx.principalId(), b.createdBy())) {
                throw sod("The approver must differ from the person who set the binding");
            }
            if (signing.production() && deployers.of(tenant, env, name).contains(ctx.principalId())) {
                throw sod("In production the binding approver must differ from the deployer of a package that uses it");
            }
            Instant now = Jdbc.dbNow(c, tx.dialect());
            if (!approve(c, tenant, env, name, version, ctx.principalId(), now)) {
                throw ApiProblem.conflict("INVALID_TRANSITION", "The binding version is no longer PENDING");
            }
            audit.record(ctx, chain(env, name), "ENV_BINDING_APPROVED", JdbcSupport.payload("env", env, "name", name,
                    "version", version, "valueRef", b.valueRef(), "reason", reason, "actor", ctx.actor()));
            return find(c, tenant, env, name, version).orElseThrow();
        }));
    }

    private static ApiProblem sod(String detail) {
        return ApiProblem.unprocessable("SEGREGATION_OF_DUTIES", detail).with("role", "binding-approver");
    }

    private static String chain(String env, String name) {
        return "envb:" + env + ":" + name;
    }

    private static String environment(String env) {
        if (!PackageManifests.validBindingName(env) || env.length() > 64) {
            throw JdbcSupport.invalid("env", "environment must be 1..64 of [A-Za-z0-9._-]");
        }
        return env;
    }

    /**
     * A binding holds an endpoint or a secret reference, never a secret value (X.2.4.5): the value must be an absolute
     * URI ({@code https://...}, {@code secret://...}).
     */
    static void requireReference(String valueRef) {
        boolean ok;
        try {
            java.net.URI u = new java.net.URI(valueRef);
            ok = u.isAbsolute() && valueRef.contains("://") && valueRef.length() <= 1000;
        } catch (java.net.URISyntaxException e) {
            ok = false;
        }
        if (!ok) {
            throw JdbcSupport.invalid("valueRef", "valueRef must be an endpoint URL or a secret reference such as "
                    + "secret://area/name, never a secret value");
        }
    }
}
