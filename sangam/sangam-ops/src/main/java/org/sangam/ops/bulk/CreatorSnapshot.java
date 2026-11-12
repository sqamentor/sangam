// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.bulk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.core.security.SecurityIdentity;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The creator identity a bulk job keeps in {@code OPS_BULK_JOB.creator_identity} (M-130, owner decision 59): tenant,
 * person, subject, groups, roles, seats, acting-for seats and authentication method, as resolved for the request that
 * created the job. Client address and further claims (such as e-mail) are not kept. The system principal is never
 * snapshotted, so no row is ever applied as the system.
 */
public final class CreatorSnapshot {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final int VERSION = 1;

    private CreatorSnapshot() {
    }

    /**
     * The creator of a new job: the identity bound to the creating request, which must be the job's tenant and the
     * calling principal.
     *
     * @throws BulkIngest.BulkException {@code INVALID / CREATOR_IDENTITY_REQUIRED} when no matching identity is bound,
     *                                  {@code INVALID / SYSTEM_CREATOR_REFUSED} for the system principal
     */
    public static SecurityIdentity forJob(Optional<SecurityIdentity> bound, String tenant, String actor) {
        SecurityIdentity id = bound.orElseThrow(() -> new BulkIngest.BulkException(BulkIngest.BulkException.Kind.INVALID,
                "CREATOR_IDENTITY_REQUIRED", "this object type needs the creator's resolved identity"));
        if (!tenant.equals(id.tenantId()) || !(id.personId().equals(actor) || java.util.Objects.equals(id.subject(), actor))) {
            throw new BulkIngest.BulkException(BulkIngest.BulkException.Kind.INVALID, "CREATOR_IDENTITY_REQUIRED",
                    "the bound identity is not the caller of this tenant");
        }
        return requireNotSystem(id);
    }

    static SecurityIdentity requireNotSystem(SecurityIdentity id) {
        if (id.system()) {
            throw new BulkIngest.BulkException(BulkIngest.BulkException.Kind.INVALID, "SYSTEM_CREATOR_REFUSED",
                    "a bulk job of this object type is never applied as the system principal");
        }
        return id;
    }

    public static String write(SecurityIdentity id) {
        ObjectNode n = JSON.createObjectNode().put("v", VERSION).put("tenantId", id.tenantId()).put("personId", id.personId())
                .put("subject", id.subject()).put("authMethod", id.authMethod());
        array(n, "groups", id.groups());
        array(n, "roles", id.roles());
        array(n, "seats", id.seats());
        array(n, "actingForSeats", id.actingForSeats());
        try {
            return JSON.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The creator for applying a row of a job of {@code tenant}.
     *
     * @throws BulkIngest.RowRejectedException when the job has no snapshot, it is unreadable, or of another tenant
     */
    public static SecurityIdentity forRow(String snapshot, String tenant) {
        if (snapshot == null || snapshot.isBlank()) {
            throw new BulkIngest.RowRejectedException("CREATOR_IDENTITY_MISSING", "the job has no creator identity snapshot");
        }
        JsonNode n;
        try {
            n = JSON.readTree(snapshot);
        } catch (JsonProcessingException e) {
            throw new BulkIngest.RowRejectedException("CREATOR_IDENTITY_INVALID", "the creator identity snapshot is unreadable");
        }
        if (n.path("v").asInt() != VERSION || !tenant.equals(n.path("tenantId").asText(null)) || !n.hasNonNull("personId")) {
            throw new BulkIngest.RowRejectedException("CREATOR_IDENTITY_INVALID", "the creator identity snapshot does not match the job");
        }
        return new SecurityIdentity(tenant, n.get("personId").asText(), n.path("subject").asText(n.get("personId").asText()),
                set(n, "groups"), set(n, "roles"), set(n, "seats"), set(n, "actingForSeats"), n.path("authMethod").asText(null),
                null, null, false, Map.of());
    }

    private static void array(ObjectNode n, String field, Set<String> values) {
        ArrayNode a = n.putArray(field);
        values.stream().sorted().forEach(a::add);
    }

    private static Set<String> set(JsonNode n, String field) {
        Set<String> out = new LinkedHashSet<>();
        n.path(field).forEach(x -> out.add(x.asText()));
        return out;
    }
}
