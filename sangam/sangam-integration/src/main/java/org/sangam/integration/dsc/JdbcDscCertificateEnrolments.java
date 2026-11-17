// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

/**
 * {@link DscCertificateEnrolments} on table {@value #TABLE} (reference DDL {@code db/sangam-dsc-enrolment-reference.sql};
 * the changelog belongs to sangam-persistence). The primary key (tenant, certificate SHA-256) keeps one row per
 * certificate; a new enrolment of a REJECTED or REVOKED certificate is a conditional UPDATE of that row, and every
 * decision is a conditional UPDATE on the expected status, so of two concurrent decisions exactly one wins.
 *
 * <p>Built on a {@link Transactions} that joins the caller's transaction (the server passes its {@code TxSupport}), so
 * a decision and its audit record commit together.
 */
public final class JdbcDscCertificateEnrolments implements DscCertificateEnrolments {

    public static final String TABLE = "INTEGRATION_DSC_CERTIFICATE";

    private static final String COLUMNS = "tenant_id, certificate_sha256, enrolment_id, principal_id, issuer_dn,"
            + " serial_hex, subject_dn, not_before, not_after, certificate, status, enrolled_at, decided_by, decided_at,"
            + " decision_reason, approval_seq";

    private final Transactions tx;

    public JdbcDscCertificateEnrolments(Transactions tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public boolean enrol(Enrolment e) {
        if (e.status() != Status.PENDING) {
            throw new IllegalArgumentException("a new enrolment is PENDING");
        }
        return tx.inTransaction(c -> {
            Optional<String> current = Jdbc.one(c, "SELECT status FROM " + TABLE
                    + " WHERE tenant_id = ? AND certificate_sha256 = ?", rs -> rs.getString(1), e.tenantId(),
                    e.certificateSha256());
            if (current.isEmpty()) {
                // a concurrent first enrolment of the same certificate fails on the primary key
                Jdbc.update(c, "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES"
                                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        e.tenantId(), e.certificateSha256(), e.enrolmentId(), e.principalId(), e.issuer(),
                        e.serialHex(), e.subject(), e.notBefore(), e.notAfter(), e.certificate(), e.status().name(),
                        e.enrolledAt(), null, null, null, 0L);
                return true;
            }
            // approval_seq is not touched: it carries on across enrolments of the same certificate
            return Jdbc.update(c, "UPDATE " + TABLE + " SET enrolment_id = ?, principal_id = ?, issuer_dn = ?,"
                            + " serial_hex = ?, subject_dn = ?, not_before = ?, not_after = ?, certificate = ?,"
                            + " status = ?, enrolled_at = ?, decided_by = NULL, decided_at = NULL,"
                            + " decision_reason = NULL WHERE tenant_id = ? AND certificate_sha256 = ?"
                            + " AND status IN (?, ?)",
                    e.enrolmentId(), e.principalId(), e.issuer(), e.serialHex(), e.subject(), e.notBefore(),
                    e.notAfter(), e.certificate(), e.status().name(), e.enrolledAt(), e.tenantId(),
                    e.certificateSha256(), Status.REJECTED.name(), Status.REVOKED.name()) == 1;
        });
    }

    @Override
    public Optional<Enrolment> byId(String tenantId, String enrolmentId) {
        if (tenantId == null || enrolmentId == null) {
            return Optional.empty();
        }
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT " + COLUMNS + " FROM " + TABLE
                + " WHERE tenant_id = ? AND enrolment_id = ?", JdbcDscCertificateEnrolments::row, tenantId, enrolmentId));
    }

    @Override
    public Optional<Enrolment> byCertificate(String tenantId, String certificateSha256) {
        if (tenantId == null || certificateSha256 == null) {
            return Optional.empty();
        }
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE tenant_id = ? AND certificate_sha256 = ?", JdbcDscCertificateEnrolments::row, tenantId,
                certificateSha256));
    }

    @Override
    public List<Enrolment> ofPrincipal(String tenantId, String principalId) {
        return tx.read(c -> Jdbc.query(c, "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE tenant_id = ? AND principal_id = ? ORDER BY enrolled_at DESC, enrolment_id",
                JdbcDscCertificateEnrolments::row, tenantId, principalId));
    }

    @Override
    public List<Enrolment> withStatus(String tenantId, Status status, int limit) {
        List<Enrolment> all = tx.read(c -> Jdbc.query(c, "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE tenant_id = ? AND status = ? ORDER BY enrolled_at, enrolment_id",
                JdbcDscCertificateEnrolments::row, tenantId, status.name()));
        return all.size() <= limit ? all : new ArrayList<>(all.subList(0, Math.max(0, limit)));
    }

    @Override
    public boolean decide(String tenantId, String enrolmentId, Set<Status> from, Status to, String decidedBy, Instant at,
                          String reason) {
        if (from == null || from.isEmpty()) {
            throw new IllegalArgumentException("from is required");
        }
        Objects.requireNonNull(to, "to");
        List<Object> args = new ArrayList<>();
        args.add(to.name());
        args.add(decidedBy);
        args.add(at);
        args.add(reason);
        args.add(tenantId);
        args.add(enrolmentId);
        StringBuilder in = new StringBuilder();
        for (Status s : from) {
            in.append(in.isEmpty() ? "?" : ", ?");
            args.add(s.name());
        }
        String seq = to == Status.APPROVED ? ", approval_seq = approval_seq + 1" : "";
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE " + TABLE + " SET status = ?, decided_by = ?,"
                + " decided_at = ?, decision_reason = ?" + seq + " WHERE tenant_id = ? AND enrolment_id = ?"
                + " AND status IN (" + in + ")", args.toArray())) == 1;
    }

    private static Enrolment row(ResultSet rs) throws SQLException {
        return new Enrolment(rs.getString("tenant_id"), rs.getString("enrolment_id"), rs.getString("principal_id"),
                rs.getString("certificate_sha256"), rs.getString("issuer_dn"), rs.getString("serial_hex"),
                rs.getString("subject_dn"), Jdbc.instant(rs, "not_before"), Jdbc.instant(rs, "not_after"),
                rs.getBytes("certificate"), Status.valueOf(rs.getString("status")), Jdbc.instant(rs, "enrolled_at"),
                rs.getString("decided_by"), Jdbc.instant(rs, "decided_at"), rs.getString("decision_reason"),
                rs.getLong("approval_seq"));
    }
}
