// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** M-099: RFC 7644 section 3.5.2 PATCH on a resource copy. Written, not run. */
class ScimPatchTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode user() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"id":"p1","userName":"alice","active":true,
                 "name":{"givenName":"Alice"},
                 "emails":[{"value":"a@x.org","type":"work","primary":true},{"value":"a@home.org","type":"home"}],
                 "urn:sangam:params:scim:schemas:extension:2.0:User":{"personId":"p1","postings":[{"seatId":"s1"}]}}""");
    }

    static JsonNode ops(String operations) throws Exception {
        return JSON.readTree("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":" + operations + "}");
    }

    @Test
    void replaceWithAndWithoutPathAndCaseInsensitiveNames() throws Exception {
        ObjectNode u = user();
        ObjectNode r = ScimPatch.apply(u, ops("[{\"op\":\"Replace\",\"path\":\"ACTIVE\",\"value\":false},"
                + "{\"op\":\"replace\",\"value\":{\"userName\":\"alice2\",\"name\":{\"familyName\":\"Rao\"}}}]"));
        assertFalse(r.get("active").asBoolean());
        assertEquals("alice2", r.get("userName").asText());
        assertEquals("Rao", r.get("name").get("familyName").asText());
        assertEquals("Alice", r.get("name").get("givenName").asText(), "sub-attributes not given stay (RFC 7644 3.5.2.3)");
        r = ScimPatch.apply(r, ops("[{\"op\":\"replace\",\"path\":\"name.givenName\",\"value\":\"Al\"}]"));
        assertEquals("Al", r.get("name").get("givenName").asText());
        assertTrue(u.get("active").asBoolean(), "the input is not changed");
    }

    @Test
    void filteredPathsReplaceAndRemoveMatchingElements() throws Exception {
        ObjectNode r = ScimPatch.apply(user(), ops("[{\"op\":\"replace\",\"path\":\"emails[type eq \\\"work\\\"].value\","
                + "\"value\":\"new@x.org\"},{\"op\":\"remove\",\"path\":\"emails[type eq \\\"home\\\"]\"}]"));
        assertEquals(1, r.get("emails").size());
        assertEquals("new@x.org", r.get("emails").get(0).get("value").asText());
        ScimException e = assertThrows(ScimException.class, () -> ScimPatch.apply(user(),
                ops("[{\"op\":\"remove\",\"path\":\"emails[type eq \\\"other\\\"]\"}]")));
        assertEquals("noTarget", e.scimType());
    }

    @Test
    void addAppendsToMultiValuedAttributesWithoutDuplicates() throws Exception {
        ObjectNode g = (ObjectNode) JSON.readTree("{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],"
                + "\"displayName\":\"G\",\"members\":[{\"value\":\"p1\"}]}");
        ObjectNode r = ScimPatch.apply(g, ops("[{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"p1\"},{\"value\":\"p2\"}]}]"));
        assertEquals(2, r.get("members").size());
        r = ScimPatch.apply(r, ops("[{\"op\":\"remove\",\"path\":\"members[value eq \\\"p1\\\"]\"}]"));
        assertEquals("p2", r.get("members").get(0).get("value").asText());
        r = ScimPatch.apply(r, ops("[{\"op\":\"remove\",\"path\":\"members\"}]"));
        assertFalse(r.has("members"));
    }

    @Test
    void extensionPathsReachTheSangamExtensionSoThePostingsRuleSeesThem() throws Exception {
        ObjectNode r = ScimPatch.apply(user(), ops("[{\"op\":\"replace\",\"path\":"
                + "\"urn:sangam:params:scim:schemas:extension:2.0:User:postings\",\"value\":[]}]"));
        assertEquals(0, r.get("urn:sangam:params:scim:schemas:extension:2.0:User").get("postings").size());
        r = ScimPatch.apply(user(), ops("[{\"op\":\"replace\",\"path\":\"urn:ietf:params:scim:schemas:core:2.0:User:userName\","
                + "\"value\":\"bob\"}]"));
        assertEquals("bob", r.get("userName").asText(), "the core URN prefix addresses the core attribute");
    }

    @Test
    void malformedRequestsAreRefused() throws Exception {
        assertEquals("invalidSyntax", assertThrows(ScimException.class,
                () -> ScimPatch.apply(user(), JSON.readTree("{\"Operations\":[]}"))).scimType());
        assertEquals("invalidSyntax", assertThrows(ScimException.class,
                () -> ScimPatch.apply(user(), ops("[{\"op\":\"move\",\"path\":\"userName\"}]"))).scimType());
        assertEquals("noTarget", assertThrows(ScimException.class,
                () -> ScimPatch.apply(user(), ops("[{\"op\":\"remove\"}]"))).scimType());
        assertEquals("invalidPath", assertThrows(ScimException.class,
                () -> ScimPatch.apply(user(), ops("[{\"op\":\"add\",\"path\":\"emails[type eq \\\"x\\\"]\",\"value\":{}}]"))).scimType());
        assertEquals("invalidFilter", assertThrows(ScimException.class,
                () -> ScimPatch.apply(user(), ops("[{\"op\":\"remove\",\"path\":\"emails[type co \\\"w\\\"]\"}]"))).scimType());
        assertEquals("invalidPath", assertThrows(ScimException.class,
                () -> ScimPatch.apply(user(), ops("[{\"op\":\"replace\",\"path\":\"a.b.c\",\"value\":1}]"))).scimType());
    }
}
