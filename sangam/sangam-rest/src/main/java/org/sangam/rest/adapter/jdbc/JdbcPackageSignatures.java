// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.sql.Connection;
import java.util.Base64;
import java.util.List;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.PackagePort.PackageSignature;

/**
 * M-074: the {@code PACKAGE_SIGNATURE} rows (changeset 0043-01), one per tenant, package hash and signing role
 * ({@code signer_role}). A role that signs the same hash again (a rejected package resubmitted unchanged, or a bundle
 * imported again after its package was removed) replaces its row. Runs on the caller's connection, inside the
 * adapter's transaction.
 */
final class JdbcPackageSignatures {

    private static final String COLS = "package_hash, signer_role, principal_id, seat_id, alg, key_id, signature, signed_at";

    private JdbcPackageSignatures() {
    }

    static void put(Connection c, String tenant, PackageSignature s) {
        byte[] hash = PackageManifests.hashBytes(s.packageHash());
        Jdbc.update(c, "DELETE FROM PACKAGE_SIGNATURE WHERE tenant_id=? AND package_hash=? AND signer_role=?",
                tenant, hash, s.role());
        Jdbc.update(c, "INSERT INTO PACKAGE_SIGNATURE(tenant_id, " + COLS + ") VALUES (?,?,?,?,?,?,?,?,?)", tenant, hash,
                s.role(), s.principalId(), s.seatId(), s.alg(), s.keyId(), Base64.getDecoder().decode(s.signature()),
                s.signedAt());
    }

    /** The signatures over {@code packageHash}, by role. */
    static List<PackageSignature> load(Connection c, String tenant, String packageHash) {
        byte[] hash = PackageManifests.hashBytes(packageHash);
        if (hash == null) {
            return List.of();
        }
        return Jdbc.query(c, "SELECT " + COLS + " FROM PACKAGE_SIGNATURE WHERE tenant_id=? AND package_hash=?"
                        + " ORDER BY signer_role",
                rs -> new PackageSignature(Hashes.hex(rs.getBytes("package_hash")), rs.getString("signer_role"),
                        rs.getString("principal_id"), rs.getString("seat_id"), rs.getString("alg"),
                        rs.getString("key_id"), Base64.getEncoder().encodeToString(rs.getBytes("signature")),
                        Jdbc.instant(rs, "signed_at")),
                tenant, hash);
    }
}
