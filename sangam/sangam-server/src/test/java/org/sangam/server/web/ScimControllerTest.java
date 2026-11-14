// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.security.scim.ScimService;
import org.sangam.server.config.ScimWiring;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;

/**
 * M-099 web adapter (decision 154): the endpoint permission, SCIM error bodies (never RFC 9457) with the SCIM media
 * type, and the discovery documents. The provisioning itself is covered by ScimServiceH2Test (sangam-security) and,
 * on Oracle and SQL Server, by ScimAcceptanceIT. Written, not run.
 */
class ScimControllerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final RequestContext IDP = new RequestContext("t", "idp-client", Set.of("scim-client"), null, null, null, null,
            null, "OIDC");

    static PermissionPort grants(boolean allowed) {
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return allowed && ScimController.PERMISSION.equals(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return allowed ? Set.of(ScimController.PERMISSION) : Set.of();
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return allowed;
            }
        };
    }

    /** No ScimService bean (security module off) and no data source. */
    static ScimController controller(boolean allowed) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        return new ScimController(beans.getBeanProvider(ScimService.class), beans.getBeanProvider(DataSource.class), grants(allowed));
    }

    static JsonNode body(ResponseEntity<String> r) throws Exception {
        return JSON.readTree(r.getBody());
    }

    static void assertScimError(ResponseEntity<String> r, int status, String scimType) throws Exception {
        assertEquals(status, r.getStatusCode().value());
        assertEquals(ScimController.SCIM_JSON, r.getHeaders().getContentType().toString());
        JsonNode b = body(r);
        assertEquals("urn:ietf:params:scim:api:messages:2.0:Error", b.get("schemas").get(0).asText());
        assertEquals(Integer.toString(status), b.get("status").asText(), "status is a string in SCIM");
        if (scimType != null) {
            assertEquals(scimType, b.get("scimType").asText());
        }
    }

    @Test
    void aCallerWithoutScimProvisionGetsA403ScimError() throws Exception {
        ScimController c = controller(false);
        assertScimError(c.listUsers(IDP, null, null, null, null, null), 403, null);
        assertScimError(c.createUser(IDP, "{}"), 403, null);
        assertScimError(c.serviceProviderConfig(IDP), 403, null);
    }

    @Test
    void withoutTheSecurityModuleTheAnswerIs501InScimForm() throws Exception {
        assertScimError(controller(true).getUser(IDP, "p1", null, null), 501, null);
    }

    @Test
    void anUnreadableBodyIsInvalidSyntaxBeforeAnyWork() throws Exception {
        ScimController c = controller(true);
        assertScimError(c.createUser(IDP, "{not json"), 400, "invalidSyntax");
        assertScimError(c.patchGroup(IDP, "g1", null, ""), 400, "invalidSyntax");
    }

    @Test
    void discoveryDocumentsDescribeWhatIsSupported() throws Exception {
        ScimController c = controller(true);
        ResponseEntity<String> spc = c.serviceProviderConfig(IDP);
        assertEquals(200, spc.getStatusCode().value());
        assertEquals(ScimController.SCIM_JSON, spc.getHeaders().getContentType().toString());
        JsonNode b = body(spc);
        assertTrue(b.get("patch").get("supported").asBoolean());
        assertEquals(false, b.get("bulk").get("supported").asBoolean());
        assertEquals(ScimService.MAX_COUNT, b.get("filter").get("maxResults").asInt());
        assertEquals(2, body(c.resourceTypes(IDP)).get("totalResults").asInt());
        JsonNode schemas = body(c.schemas(IDP));
        assertEquals(3, schemas.get("totalResults").asInt());
        String text = schemas.toString();
        assertTrue(text.contains("\"name\":\"postings\"") && text.contains("readOnly"), "postings are read-only (decision 154)");
    }

    @Test
    void theSubjectSettingAcceptsTheTwoAttributesOnly() {
        assertEquals(ScimService.SubjectFrom.EXTERNAL_ID, ScimWiring.subjectFrom(null));
        assertEquals(ScimService.SubjectFrom.EXTERNAL_ID, ScimWiring.subjectFrom("external-id"));
        assertEquals(ScimService.SubjectFrom.USER_NAME, ScimWiring.subjectFrom("USER_NAME"));
        assertThrows(IllegalStateException.class, () -> ScimWiring.subjectFrom("email"));
    }
}
