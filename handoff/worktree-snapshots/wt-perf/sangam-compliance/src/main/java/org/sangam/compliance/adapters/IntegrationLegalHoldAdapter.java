// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.adapters;

import org.sangam.compliance.ports.CompliancePorts.LegalHoldPort;
import org.sangam.integration.lifecycle.LegalHoldStore;

import java.sql.Connection;
import java.util.List;

/** {@link LegalHoldPort} over the integration module's {@link LegalHoldStore} (VI.5.1, R61). No logic of its own. */
public final class IntegrationLegalHoldAdapter implements LegalHoldPort {

    private final LegalHoldStore store;

    public IntegrationLegalHoldAdapter(LegalHoldStore store) {
        this.store = store;
    }

    @Override
    public boolean isHeld(Connection c, String tenantId, String rootId) {
        return store.isHeld(c, tenantId, rootId);
    }

    @Override
    public List<String> activeHoldIds(Connection c, String tenantId, String rootId) {
        return store.active(c, tenantId, rootId).stream().map(LegalHoldStore.Hold::holdId).toList();
    }

    @Override
    public void place(Connection c, String tenantId, String rootId, String holdId, String reason, String actor) {
        store.place(c, tenantId, rootId, holdId, reason, actor);
    }

    @Override
    public boolean release(Connection c, String tenantId, String rootId, String holdId, String actor) {
        return store.release(c, tenantId, rootId, holdId, actor);
    }
}
