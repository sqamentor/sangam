// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.lifecycle;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.List;

/**
 * Legal holds (SANGAM.md VI.5.1 "keeps legal holds", R29, R61 Public Records Act: unauthorised destruction must not
 * happen). Table {@code LEGAL_HOLD(tenant_id, root_id, hold_id, reason, held_by, created_at, released_at, released_by)}.
 * While a hold is active, erasure and retention cleanup of the root are refused. Category-A government records are
 * held permanently by the records module (R61) through {@link #place}.
 */
public final class LegalHoldStore {

    public record Hold(String tenantId, String rootId, String holdId, String reason, String heldBy) {
    }

    private final Dialect dialect;

    public LegalHoldStore(Dialect dialect) {
        this.dialect = dialect;
    }

    public void place(Connection c, String tenantId, String rootId, String holdId, String reason, String actor) {
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO LEGAL_HOLD(tenant_id, root_id, hold_id, reason, held_by, created_at, released_at,"
                + " released_by) VALUES (?,?,?,?,?," + dialect.now() + ",NULL,NULL)", tenantId, rootId, holdId, reason, actor);
    }

    public boolean release(Connection c, String tenantId, String rootId, String holdId, String actor) {
        return Jdbc.update(c, "UPDATE LEGAL_HOLD SET released_at=" + dialect.now() + ", released_by=? WHERE tenant_id=? AND root_id=?"
                + " AND hold_id=? AND released_at IS NULL", actor, tenantId, rootId, holdId) == 1;
    }

    public List<Hold> active(Connection c, String tenantId, String rootId) {
        return Jdbc.query(c, "SELECT tenant_id, root_id, hold_id, reason, held_by FROM LEGAL_HOLD WHERE tenant_id=? AND root_id=?"
                        + " AND released_at IS NULL",
                rs -> new Hold(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), tenantId, rootId);
    }

    public boolean isHeld(Connection c, String tenantId, String rootId) {
        return !active(c, tenantId, rootId).isEmpty();
    }
}
