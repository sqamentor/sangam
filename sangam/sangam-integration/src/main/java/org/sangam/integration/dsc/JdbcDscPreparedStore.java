// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

/**
 * {@link DscPreparedStore} on table {@value #TABLE} (reference DDL {@code db/sangam-dsc-reference.sql}; the changelog
 * belongs to sangam-persistence). {@link #consume} is one conditional UPDATE, so of two concurrent completes exactly
 * one marks the row used; the row is then read in the same transaction.
 */
public final class JdbcDscPreparedStore implements DscPreparedStore {

    public static final String TABLE = "INTEGRATION_DSC_PREPARED";
    public static final String VERIFIED_VALID = "VERIFIED_VALID";

    private static final String COLUMNS = "tenant_id, prepare_id, task_id, document_id, document_version, document_sha256,"
            + " principal_id, signer_cert_sha256, enrolment_id, enrolment_approval_seq, intended_signer_name, algorithm,"
            + " prepared_digest_sha256,"
            + " signed_attributes, signing_time, prepared_pdf, created_at, expires_at";

    private final Transactions tx;

    public JdbcDscPreparedStore(Transactions tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public void save(Entry e) {
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES"
                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.tenantId(), e.prepareId(), e.taskId(), e.documentId(), e.documentVersion(), e.documentSha256(),
                e.principalId(), e.signerCertificateSha256(), e.enrolmentId(), e.enrolmentApprovalSeq(),
                e.intendedSignerName(), e.algorithm(),
                e.preparedDigestSha256(), e.signedAttributes(), e.signingTime(), e.preparedPdf(), e.createdAt(),
                e.expiresAt()));
    }

    @Override
    public Optional<Entry> consume(String tenantId, String prepareId, String taskId, String principalId, Instant now) {
        if (tenantId == null || prepareId == null || taskId == null || principalId == null || now == null) {
            return Optional.empty();
        }
        return tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE " + TABLE + " SET used_at = ? WHERE tenant_id = ? AND prepare_id = ?"
                            + " AND task_id = ? AND principal_id = ? AND used_at IS NULL AND expires_at > ?",
                    now, tenantId, prepareId, taskId, principalId, now);
            if (n != 1) {
                return Optional.empty();
            }
            return Jdbc.one(c, "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE tenant_id = ? AND prepare_id = ?",
                    JdbcDscPreparedStore::entry, tenantId, prepareId);
        });
    }

    @Override
    public void recordOutcome(String tenantId, String prepareId, String outcome, String reasonCode,
                              Integer signedDocumentVersion, Instant at) {
        Objects.requireNonNull(outcome, "outcome");
        int n = tx.inTransaction(c -> Jdbc.update(c, "UPDATE " + TABLE + " SET outcome = ?, reason_code = ?,"
                        + " signed_document_version = ?, completed_at = ?, prepared_pdf = NULL"
                        + " WHERE tenant_id = ? AND prepare_id = ? AND used_at IS NOT NULL",
                outcome, reasonCode, signedDocumentVersion, at, tenantId, prepareId));
        if (n != 1) {
            throw new IllegalStateException("DSC prepare " + prepareId + " was not consumed, so it has no outcome");
        }
    }

    @Override
    public List<VerifiedSignature> verifiedSignatures(String tenantId, String taskId, String documentId,
                                                      String principalId) {
        if (tenantId == null || taskId == null || documentId == null || principalId == null) {
            return List.of();
        }
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT prepare_id, signer_cert_sha256, enrolment_id,"
                        + " enrolment_approval_seq FROM " + TABLE + " WHERE tenant_id = ? AND task_id = ?"
                        + " AND document_id = ? AND principal_id = ? AND outcome = ?"
                        + " AND signed_document_version IS NOT NULL ORDER BY created_at, prepare_id",
                rs -> new VerifiedSignature(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                tenantId, taskId, documentId, principalId, VERIFIED_VALID));
    }

    @Override
    public int purgeExpired(Instant before) {
        return tx.inTransaction(c -> Jdbc.update(c, "DELETE FROM " + TABLE + " WHERE used_at IS NULL AND expires_at < ?",
                before));
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        return new Entry(rs.getString("tenant_id"), rs.getString("prepare_id"), rs.getString("task_id"),
                rs.getString("document_id"), rs.getInt("document_version"), rs.getString("document_sha256"),
                rs.getString("principal_id"), rs.getString("signer_cert_sha256"), rs.getString("enrolment_id"),
                rs.getLong("enrolment_approval_seq"), rs.getString("intended_signer_name"),
                rs.getString("algorithm"), rs.getString("prepared_digest_sha256"), rs.getBytes("signed_attributes"),
                Jdbc.instant(rs, "signing_time"), rs.getBytes("prepared_pdf"), Jdbc.instant(rs, "created_at"),
                Jdbc.instant(rs, "expires_at"));
    }
}
