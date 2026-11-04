// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;

/**
 * A connector registered under a neutral id ({@code fhir}, {@code pas}, {@code crd}, {@code x12}) so the global pack
 * bindings stay the same while each tenant registers its own simulator or live instance under that id.
 */
public final class HealthRenamedConnector implements SangamConnector {

    private final String id;
    private final SangamConnector delegate;

    public HealthRenamedConnector(String id, SangamConnector delegate) {
        this.id = id;
        this.delegate = delegate;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        ConnectorDescriptor d = delegate.descriptor();
        return new ConnectorDescriptor(id, d.type(), d.capabilityClass(), d.operations(), d.profile());
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        return delegate.invoke(request);
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        return delegate.lookup(request);
    }

    @Override
    public boolean healthy() {
        return delegate.healthy();
    }
}
