// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.booking.BookingModel;
import org.sangam.resources.booking.BookingService;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.catalogue.ResourceCatalogue;
import org.sangam.resources.catalogue.ResourceType;
import org.sangam.resources.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;

/**
 * Evaluates the entitlement rule of a resource type without booking (pre-check in an application form process).
 *
 * <p>Inputs: {@code resourceId} or {@code typeId}; optional {@code requesterId}, {@code entitlementClass},
 * {@code purpose}, {@code quantity}, {@code startsAt}, {@code endsAt}, {@code requestAttributes} (map).
 * Outputs: {@code entitled} (boolean), {@code entitlementClass}, {@code entitlementReason}, {@code tariffCode},
 * {@code requiresApproval}, {@code priorityLevel}, {@code entitlementDecision} (table hash).
 */
public class EntitlementCheckDelegate implements JavaDelegate {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution e) {
        ResourceCatalogue catalogue = ResourcesRuntime.service(ResourceCatalogue.class);
        BookingService bookings = ResourcesRuntime.service(BookingService.class);
        Connection c = ResourcesRuntime.connection();
        SecurityIdentity actor = ResourcesRuntime.identity(e);
        String t = actor.tenantId();
        String resourceId = ResourcesRuntime.str(e, "resourceId");
        Resource r = resourceId == null ? null : catalogue.get(c, t, resourceId);
        ResourceType type = catalogue.type(c, t, r != null ? r.typeId() : ResourcesRuntime.required(e, "typeId"));
        String from = ResourcesRuntime.str(e, "startsAt");
        String to = ResourcesRuntime.str(e, "endsAt");
        Object attrs = e.getVariable("requestAttributes");
        String requester = ResourcesRuntime.opt(e, "requesterId").orElse(actor.onBehalfOf() != null ? actor.onBehalfOf() : actor.personId());
        Map<String, Object> in = bookings.rules().inputs(actor, type, r, requester, ResourcesRuntime.str(e, "entitlementClass"),
                ResourcesRuntime.str(e, "purpose"), Sql.intOr(e.getVariable("quantity"), 1),
                from == null ? null : Instant.parse(from), to == null ? null : Instant.parse(to),
                ResourcesRuntime.service(org.sangam.resources.ResourcesContext.class).now(c),
                attrs instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
        BookingModel.Entitlement ent = bookings.rules().entitlement(t, type, in, Sql.intOrNull(e.getVariable("priorityLevel")));
        e.setVariable("entitled", ent.allowed());
        e.setVariable("entitlementClass", ent.entitlementClass());
        e.setVariable("entitlementReason", ent.reason());
        e.setVariable("tariffCode", ent.tariffCode());
        e.setVariable("requiresApproval", ent.requiresApproval());
        e.setVariable("priorityLevel", ent.priorityLevel());
        e.setVariable("entitlementDecision", ent.decisionHash());
    }
}
