// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Objects;

import org.sangam.billing.BillingConnector;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.tenant.TenantContext;

/**
 * The server's {@link BillingConnector.ActorSource} (RM-37): the actor of a billing posting is the authenticated
 * identity the security filter bound to the calling thread ({@link TenantContext}), never a value of the step input or
 * of a request body.
 * <ul>
 *   <li>No identity on the thread (an engine job, a relay delivery) or a system identity: nobody is authenticated, the
 *       connector records its system actor.</li>
 *   <li>An identity of the request's tenant: its person. The REST layer names the caller by the principal of the
 *       token, which is the identity's person id or its subject; an input actor equal to either is that same person
 *       and is returned as written, so the caller is never refused against their own name.</li>
 *   <li>An identity of another tenant than the request's: refused (the connector answers ACTOR_UNRESOLVED and posts
 *       nothing).</li>
 * </ul>
 */
public final class RequestActorSource implements BillingConnector.ActorSource {

    @Override
    public String authenticatedActor(ConnectorRequest request) {
        SecurityIdentity id = TenantContext.current().orElse(null);
        if (id == null || id.system()) {
            return null;
        }
        if (!Objects.equals(id.tenantId(), request.tenantId())) {
            throw new IllegalStateException("the authenticated identity belongs to another tenant than the request");
        }
        String claimed = request.str("actor");
        if (claimed != null && (claimed.equals(id.personId()) || claimed.equals(id.subject()))) {
            return claimed;
        }
        return id.personId() != null && !id.personId().isBlank() ? id.personId() : id.subject();
    }
}
