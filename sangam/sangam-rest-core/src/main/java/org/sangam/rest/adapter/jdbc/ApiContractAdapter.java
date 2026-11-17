// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import org.sangam.core.api.ReadWork;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.contract.ApiContractStore;
import org.sangam.integration.connector.contract.ContractOperations;
import org.sangam.rest.port.ApiContractPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;

/**
 * M-068: {@link ApiContractPort} over the {@link ApiContractStore} (in the server the JDBC store over
 * {@code API_CONTRACT}, joined to the Spring transaction through {@link #joined}). The package is looked up through
 * {@link PackagePort#get} first, so a package of another tenant or an unknown one is 404 before any contract is read or
 * written; the tenant is the caller's. The document is stored as its compact JSON text, so the same document sent with
 * other whitespace is the same version.
 *
 * <p>Owner decision 115: a version is stored only while the package is {@code DRAFT} (the hash freezes at submit, R43);
 * otherwise 409 {@code INVALID_TRANSITION}. The package row is read with the dialect's locking select in the same
 * transaction as the insert, so a submit cannot commit between the check and the store. Every new version writes the
 * class-B event {@value #EVENT} on the package's chain ({@code pkg:<key>}, as the package lifecycle events) inside the
 * insert's transaction: if the audit write fails, nothing is stored. An identical re-send stores and audits nothing.
 */
public final class ApiContractAdapter implements ApiContractPort {

    /** Class-B audit event of a stored contract version (change control: SANGAM.md X.4.0.3). */
    public static final String EVENT = "API_CONTRACT_STORED";

    private final ApiContractStore store;
    private final PackagePort packages;
    private final TxSupport tx;
    private final AuditPort audit;

    public ApiContractAdapter(ApiContractStore store, PackagePort packages, TxSupport tx, AuditPort audit) {
        this.store = Objects.requireNonNull(store, "store");
        this.packages = Objects.requireNonNull(packages, "packages");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** {@link Transactions} whose units join the current Spring transaction (or start one) on its bound connection. */
    public static Transactions joined(TxSupport tx) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                return tx.withConnection(work);
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                return tx.withReadConnection(work);
            }
        };
    }

    @Override
    public ApiContractSummary put(RequestContext ctx, String packageId, String contractId, JsonNode document) {
        String tenant = JdbcSupport.tenant(ctx);
        PackagePort.Package p = packages.get(ctx, packageId);
        if (contractId == null || !contractId.matches("[A-Za-z0-9_.-]{1,120}")) {
            throw JdbcSupport.invalid("contractId", "the contract id must be 1 to 120 of A-Z a-z 0-9 _ . -");
        }
        if (document == null || !document.isObject()) {
            throw JdbcSupport.invalid("body", "the contract document must be a JSON object");
        }
        String json = Json.write(document);
        return tx.inTx(() -> {
            String state = tx.withConnection(c -> Jdbc.one(c, tx.dialect().lockingSelect("state", "PACKAGE_VERSION",
                    "tenant_id=? AND package_id=?"), rs -> rs.getString(1), tenant, packageId))
                    .orElseThrow(() -> ApiProblem.notFound("Package", packageId));
            if (!"DRAFT".equals(state)) {
                throw ApiProblem.conflict("INVALID_TRANSITION", "A contract version can be stored only while the package"
                        + " is DRAFT; it is " + state).with("state", state);
            }
            try {
                return summary(store.put(tenant, packageId, contractId, json, ctx.principalId(),
                        stored -> audit.record(ctx, "pkg:" + p.key(), EVENT, JdbcSupport.payload("tenantId", tenant,
                                "packageId", packageId, "key", p.key(), "contractId", stored.contractId(),
                                "version", stored.version(), "sha256", stored.sha256(), "createdBy", ctx.principalId(),
                                "actor", ctx.actor()))));
            } catch (IllegalArgumentException e) {
                throw JdbcSupport.invalid("body", e.getMessage());
            }
        });
    }

    @Override
    public List<ApiContractSummary> list(RequestContext ctx, String packageId) {
        String tenant = JdbcSupport.tenant(ctx);
        packages.get(ctx, packageId);
        return store.list(tenant, packageId).stream().map(ApiContractAdapter::summary).toList();
    }

    @Override
    public ApiContract get(RequestContext ctx, String packageId, String contractId, Integer version) {
        ApiContractStore.ApiContract c = stored(ctx, packageId, contractId, version);
        return new ApiContract(c.tenantId(), c.packageId(), c.contractId(), c.version(), c.format().name(),
                c.specVersion(), c.sha256(), c.document());
    }

    @Override
    public List<ContractOperation> operations(RequestContext ctx, String packageId, String contractId, Integer version) {
        return ContractOperations.list(stored(ctx, packageId, contractId, version)).stream()
                .map(o -> new ContractOperation(o.operationId(), o.format().name(), o.method(), o.path(), o.action(),
                        o.channel(), o.summary(), o.requestSchema(), o.responseSchemas(), o.servers()))
                .toList();
    }

    private ApiContractStore.ApiContract stored(RequestContext ctx, String packageId, String contractId, Integer version) {
        String tenant = JdbcSupport.tenant(ctx);
        packages.get(ctx, packageId);
        return store.find(tenant, packageId, contractId, version).orElseThrow(() -> ApiProblem.notFound("ApiContract",
                contractId + (version == null ? "" : "@" + version)));
    }

    private static ApiContractSummary summary(ApiContractStore.ApiContract c) {
        return new ApiContractSummary(c.tenantId(), c.packageId(), c.contractId(), c.version(), c.format().name(),
                c.specVersion(), c.sha256());
    }
}
