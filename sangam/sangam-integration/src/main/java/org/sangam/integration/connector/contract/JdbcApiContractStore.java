// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.contract;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

/**
 * M-068: {@link ApiContractStore} over {@code API_CONTRACT} (changeset 0043-08, all five dialects; primary key tenant,
 * package, contract id, version).
 * <ul>
 *   <li>The column {@code contract_id} is {@code VARCHAR(255)}, so the store itself enforces the contract id form
 *       {@code [A-Za-z0-9_.-]{1,120}} ({@link ApiContractStore#parse}); tenant and package ids are at most
 *       {@value #MAX_ID} characters, the spec version at most {@value #MAX_SPEC}, {@code created_by} at most
 *       {@value #MAX_CREATED_BY}. A value that does not fit is refused, never truncated.</li>
 *   <li>A document is admitted only through {@link ApiContractStore#accept}: OpenAPI 3.x or AsyncAPI 2.x/3.x JSON whose
 *       operations list and whose schemas compile without leaving the document.</li>
 *   <li>Versions are immutable: there is no update and no delete. {@link #put} inserts {@code MAX(version) + 1}; the same
 *       document as the latest stored version (same SHA-256 of the text) answers that version. Two writers racing for
 *       the same next version meet on the primary key: the loser re-reads and either finds its document stored or takes
 *       the following version.</li>
 *   <li>{@code created_at} is the database time ({@link Dialect#now()}).</li>
 *   <li>Each unit runs in {@link Transactions}: given one that joins the caller's transaction (the server's), the
 *       insert, the {@code onStored} audit and the caller's package lock commit or roll back together.</li>
 * </ul>
 */
public final class JdbcApiContractStore implements ApiContractStore {

    static final int MAX_ID = 64;
    static final int MAX_SPEC = 16;
    static final int MAX_CREATED_BY = 255;
    /** Attempts of one put against concurrent writers of the same contract. */
    static final int MAX_ATTEMPTS = 8;

    private static final String COLS = "tenant_id, package_id, contract_id, version, format, spec_version, document, sha256";
    private static final String LATEST = "version=(SELECT MAX(l.version) FROM API_CONTRACT l WHERE l.tenant_id=a.tenant_id"
            + " AND l.package_id=a.package_id AND l.contract_id=a.contract_id)";

    private final Dialect dialect;
    private final Transactions tx;

    public JdbcApiContractStore(Dialect dialect, Transactions tx) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public ApiContract put(String tenantId, String packageId, String contractId, String json) {
        return put(tenantId, packageId, contractId, json, null);
    }

    @Override
    public ApiContract put(String tenantId, String packageId, String contractId, String json, String createdBy) {
        return put(tenantId, packageId, contractId, json, createdBy, stored -> { });
    }

    /** {@code onStored} runs inside the insert's transaction unit, after the insert; if it throws, the unit rolls back. */
    @Override
    public ApiContract put(String tenantId, String packageId, String contractId, String json, String createdBy,
                           Consumer<ApiContract> onStored) {
        Objects.requireNonNull(onStored, "onStored");
        id("tenant id", tenantId);
        id("package id", packageId);
        if (createdBy != null && createdBy.length() > MAX_CREATED_BY) {
            throw new IllegalArgumentException("created by is longer than " + MAX_CREATED_BY + " characters");
        }
        ApiContract candidate = ApiContractStore.accept(tenantId, packageId, contractId, 1, json);
        if (candidate.specVersion().length() > MAX_SPEC) {
            throw new IllegalArgumentException("spec version is longer than " + MAX_SPEC + " characters");
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<ApiContract> stored = tx.inTransaction(c -> {
                Optional<ApiContract> latest = latest(c, tenantId, packageId, contractId);
                if (latest.isPresent() && latest.get().sha256().equals(candidate.sha256())) {
                    return latest;
                }
                int next = latest.map(l -> l.version() + 1).orElse(1);
                boolean inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO API_CONTRACT(tenant_id, package_id,"
                                + " contract_id, version, format, spec_version, document, sha256, created_by, created_at)"
                                + " VALUES (?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                        tenantId, packageId, contractId, next, candidate.format().name(), candidate.specVersion(), json,
                        candidate.sha256(), createdBy);
                if (!inserted) {
                    return Optional.<ApiContract>empty();
                }
                ApiContract created = new ApiContract(tenantId, packageId, contractId, next, candidate.format(),
                        candidate.specVersion(), candidate.document(), candidate.sha256());
                onStored.accept(created);
                return Optional.of(created);
            });
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        throw new IllegalStateException("API contract " + contractId + " of package " + packageId
                + " is being written concurrently; no version could be stored after " + MAX_ATTEMPTS + " attempts");
    }

    @Override
    public Optional<ApiContract> find(String tenantId, String packageId, String contractId, Integer version) {
        if (!fits(tenantId) || !fits(packageId) || contractId == null || !contractId.matches("[A-Za-z0-9_.-]{1,120}")
                || (version != null && version < 1)) {
            return Optional.empty();   // nothing that does not fit the table can be stored in it
        }
        return tx.inTransaction(c -> version == null ? latest(c, tenantId, packageId, contractId)
                : Jdbc.one(c, "SELECT " + COLS + " FROM API_CONTRACT WHERE tenant_id=? AND package_id=? AND contract_id=?"
                        + " AND version=?", JdbcApiContractStore::row, tenantId, packageId, contractId, version));
    }

    @Override
    public List<ApiContract> list(String tenantId, String packageId) {
        if (!fits(tenantId) || !fits(packageId)) {
            return List.of();
        }
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT " + COLS + " FROM API_CONTRACT a WHERE a.tenant_id=?"
                        + " AND a.package_id=? AND a." + LATEST, JdbcApiContractStore::row, tenantId, packageId))
                .stream().sorted(Comparator.comparing(ApiContract::contractId)).toList();
    }

    private static Optional<ApiContract> latest(Connection c, String tenantId, String packageId, String contractId) {
        return Jdbc.one(c, "SELECT " + COLS + " FROM API_CONTRACT a WHERE a.tenant_id=? AND a.package_id=?"
                + " AND a.contract_id=? AND a." + LATEST, JdbcApiContractStore::row, tenantId, packageId, contractId);
    }

    /** A stored row: the document as stored, its stored SHA-256 (the version's identity), no re-validation. */
    private static ApiContract row(ResultSet rs) throws SQLException {
        String text = rs.getString("document");
        JsonNode doc;
        try {
            doc = Json.MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("API_CONTRACT.document of " + rs.getString("contract_id") + "@"
                    + rs.getInt("version") + " is not JSON", e);
        }
        return new ApiContract(rs.getString("tenant_id"), rs.getString("package_id"), rs.getString("contract_id"),
                rs.getInt("version"), Format.valueOf(rs.getString("format")), rs.getString("spec_version"), doc,
                rs.getString("sha256"));
    }

    private static void id(String what, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        if (value.length() > MAX_ID) {
            throw new IllegalArgumentException(what + " is longer than " + MAX_ID + " characters");
        }
    }

    private static boolean fits(String id) {
        return id != null && !id.isBlank() && id.length() <= MAX_ID;
    }
}
