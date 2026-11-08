// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Who acts, for which tenant, and why. Every registry service call takes one. The tenant always comes from the
 * caller's identity (VI.4 rule 6), never from a request body.
 *
 * @param tenant      tenant id
 * @param actor       acting principal id (a person, or {@code system:...} for jobs)
 * @param onBehalfOf  the seat or person a system principal acts for, or null
 * @param purpose     declared processing purpose (privacy and consent checks), or null
 * @param operationId R36 operationId of the business step, or null for interactive calls
 * @param zone        tenant time zone used for calendar dates (validity, periods)
 */
public record RegistryContext(String tenant, String actor, String onBehalfOf, String purpose, String operationId,
                              ZoneId zone) {

    public RegistryContext {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(actor, "actor");
        zone = zone == null ? org.sangam.core.time.BusinessClock.system().zone(tenant) : zone;   // tenant business zone
    }

    public static RegistryContext of(String tenant, String actor) {
        return new RegistryContext(tenant, actor, null, null, null, null);
    }

    public RegistryContext withPurpose(String p) {
        return new RegistryContext(tenant, actor, onBehalfOf, p, operationId, zone);
    }

    public RegistryContext withOperation(String op) {
        return new RegistryContext(tenant, actor, onBehalfOf, purpose, op, zone);
    }
}
