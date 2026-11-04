// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.ledger.LedgerConnector;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;

/**
 * The ledger connector adapter for the {@code ledger.*} pack connector ids: registers the tenant's ledger definitions
 * (from its configuration profile) before delegating to {@link LedgerConnector}. The posting reference is the pack
 * step's {@code operationId}, so a retried step never posts twice (class a, lookup from the stored entry).
 */
public final class LedgerDefiningConnector implements SangamConnector {

    private final SangamConnector delegate;
    private final LedgerTenantDefinitions definitions;

    /** @param delegate {@link LedgerConnector}, or another connector posting on tenant ledgers (billing) */
    public LedgerDefiningConnector(SangamConnector delegate, LedgerTenantDefinitions definitions) {
        this.delegate = delegate;
        this.definitions = definitions;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        definitions.ensure(request.tenantId());
        return delegate.invoke(request);
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        definitions.ensure(request.tenantId());
        return delegate.lookup(request);
    }
}
