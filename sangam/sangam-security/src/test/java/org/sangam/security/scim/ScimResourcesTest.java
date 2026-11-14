// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.sangam.core.security.SecurityPorts;

/** M-099: SCIM request parsing, ETags, postings view and projection. Written, not run. */
class ScimResourcesTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aUserBodyIsParsedWithItsPersonNameAndPrimaryEmail() throws Exception {
        ScimResources.UserInput in = ScimResources.user(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":" alice ","externalId":"sub-1",
                 "active":"False","name":{"givenName":"Alice","familyName":"Rao"},
                 "emails":[{"value":"home@x.org"},{"value":"work@x.org","primary":true}],"nickName":"dropped"}"""));
        assertEquals("alice", in.userName());
        assertEquals("sub-1", in.externalId());
        assertEquals(Boolean.FALSE, in.active(), "string booleans of some identity providers");
        assertEquals("Alice Rao", in.personName());
        assertEquals("work@x.org", in.email());
        assertNull(in.postings(), "not mentioned is not an empty list");
        assertTrue(in.attributes().contains("\"emails\""));
        assertFalse(in.attributes().contains("nickName"), "only the kept attributes are stored");
    }

    @Test
    void requiredSchemaAndValuesAreChecked() throws Exception {
        assertEquals("invalidSyntax", assertThrows(ScimException.class,
                () -> ScimResources.user(JSON.readTree("{\"userName\":\"a\"}"))).scimType());
        assertEquals("invalidValue", assertThrows(ScimException.class, () -> ScimResources.user(JSON.readTree(
                "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}"))).scimType());
        assertEquals("invalidValue", assertThrows(ScimException.class, () -> ScimResources.user(JSON.readTree(
                "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],\"userName\":\"a\",\"externalId\":\""
                        + "x".repeat(ScimStore.MAX_EXTERNAL_ID + 1) + "\"}"))).scimType());
        assertEquals("invalidValue", assertThrows(ScimException.class, () -> ScimResources.group(JSON.readTree(
                "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],\"displayName\":\"G\","
                        + "\"members\":[{\"value\":\"g2\",\"type\":\"Group\"}]}"))).scimType(), "no nested groups");
    }

    @Test
    void theRegularPostingKindIsShownAsSubstantiveWithUtcDates() {
        SecurityPorts.SeatHolder h = new SecurityPorts.SeatHolder("s1", "p1", "po1", "REGULAR", false,
                Instant.parse("2026-04-01T00:00:00Z"), null, BigDecimal.ONE);
        assertEquals(new ScimResources.Posting("s1", "SUBSTANTIVE", "2026-04-01", null), ScimResources.posting(h));
        SecurityPorts.SeatHolder a = new SecurityPorts.SeatHolder("s2", "p1", "po2", "ADDITIONAL_CHARGE", true,
                Instant.parse("2026-04-01T20:00:00Z"), Instant.parse("2026-05-01T00:00:00Z"), null);
        assertEquals(new ScimResources.Posting("s2", "ADDITIONAL_CHARGE", "2026-04-01", "2026-05-01"), ScimResources.posting(a));
    }

    @Test
    void postingsInTheRequestAreNormalisedForTheEchoComparison() throws Exception {
        ScimResources.UserInput in = ScimResources.user(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"a",
                 "urn:sangam:params:scim:schemas:extension:2.0:User":{"postings":[
                   {"seatId":"s1","kind":"substantive","validFrom":"2026-04-01"}]}}"""));
        assertEquals(Set.of(new ScimResources.Posting("s1", "SUBSTANTIVE", "2026-04-01", null)), in.postings());
    }

    @Test
    void etagsMatchWeakStrongAndStar() {
        assertEquals("W/\"3\"", ScimResources.etag(3));
        assertTrue(ScimResources.matches(null, 3));
        assertTrue(ScimResources.matches("*", 3));
        assertTrue(ScimResources.matches("W/\"2\", W/\"3\"", 3));
        assertTrue(ScimResources.matches("\"3\"", 3));
        assertFalse(ScimResources.matches("W/\"2\"", 3));
    }

    @Test
    void projectionKeepsIdAndSchemas() throws Exception {
        ObjectNode u = (ObjectNode) JSON.readTree("{\"schemas\":[\"s\"],\"id\":\"1\",\"userName\":\"a\",\"emails\":[],\"meta\":{}}");
        ObjectNode only = ScimResources.project(u, "urn:ietf:params:scim:schemas:core:2.0:User:userName", null);
        assertEquals(Set.of("schemas", "id", "userName"), Set.copyOf(iterable(only)));
        ObjectNode without = ScimResources.project(u, null, "emails,meta,id");
        assertEquals(Set.of("schemas", "id", "userName"), Set.copyOf(iterable(without)));
    }

    private static java.util.List<String> iterable(ObjectNode n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Test
    void theErrorBodyFollowsRfc7644() {
        ObjectNode e = ScimResources.error(ScimException.mutability("postings are read-only"));
        assertEquals("urn:ietf:params:scim:api:messages:2.0:Error", e.get("schemas").get(0).asText());
        assertEquals("400", e.get("status").asText());
        assertEquals("mutability", e.get("scimType").asText());
    }
}
