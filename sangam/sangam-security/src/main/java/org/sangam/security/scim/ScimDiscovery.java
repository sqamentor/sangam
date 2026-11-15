// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.security.support.Sql;

/**
 * The SCIM discovery documents (RFC 7643 sections 5 to 7) for what {@link ScimService} supports: PATCH, filters
 * (eq, co, sw on userName, externalId, displayName; at most {@link ScimService#MAX_COUNT} results), ETags; no bulk, no
 * sort, no password change. Postings are read-only in the Sangam extension (owner decision 154).
 */
public final class ScimDiscovery {

    public static final String SPC_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";
    public static final String RESOURCE_TYPE_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ResourceType";
    public static final String SCHEMA_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Schema";

    private ScimDiscovery() {
    }

    public static ObjectNode serviceProviderConfig() {
        ObjectNode r = Sql.JSON.createObjectNode();
        r.set("schemas", ScimResources.schemas(SPC_SCHEMA));
        r.putObject("patch").put("supported", true);
        r.putObject("bulk").put("supported", false).put("maxOperations", 0).put("maxPayloadSize", 0);
        r.putObject("filter").put("supported", true).put("maxResults", ScimService.MAX_COUNT);
        r.putObject("changePassword").put("supported", false);
        r.putObject("sort").put("supported", false);
        r.putObject("etag").put("supported", true);
        ArrayNode auth = r.putArray("authenticationSchemes");
        auth.addObject().put("type", "oauthbearertoken").put("name", "OAuth Bearer Token")
                .put("description", "An access token of the identity provider's service principal with scim.provision");
        r.putObject("meta").put("resourceType", "ServiceProviderConfig").put("location", ScimResources.BASE + "ServiceProviderConfig");
        return r;
    }

    public static ObjectNode resourceTypes() {
        ArrayNode items = Sql.JSON.createArrayNode();
        ObjectNode user = items.addObject();
        user.set("schemas", ScimResources.schemas(RESOURCE_TYPE_SCHEMA));
        user.put("id", "User").put("name", "User").put("endpoint", "/Users").put("schema", ScimResources.USER_SCHEMA);
        user.putArray("schemaExtensions").addObject().put("schema", ScimResources.SANGAM_USER).put("required", false);
        user.putObject("meta").put("resourceType", "ResourceType").put("location", ScimResources.BASE + "ResourceTypes/User");
        ObjectNode group = items.addObject();
        group.set("schemas", ScimResources.schemas(RESOURCE_TYPE_SCHEMA));
        group.put("id", "Group").put("name", "Group").put("endpoint", "/Groups").put("schema", ScimResources.GROUP_SCHEMA);
        group.putObject("meta").put("resourceType", "ResourceType").put("location", ScimResources.BASE + "ResourceTypes/Group");
        return list(items);
    }

    public static ObjectNode schemas() {
        ArrayNode items = Sql.JSON.createArrayNode();
        ObjectNode user = schema(items, ScimResources.USER_SCHEMA, "User", "A Sangam person");
        ArrayNode ua = user.putArray("attributes");
        attr(ua, "userName", "string", "readWrite", "server", false).put("required", true);
        attr(ua, "externalId", "string", "readWrite", "server", true);
        attr(ua, "displayName", "string", "readWrite", "none", false);
        attr(ua, "active", "boolean", "readWrite", "none", false);
        complex(ua, "name", false);
        complex(ua, "emails", true);
        complex(ua, "phoneNumbers", true);
        attr(ua, "locale", "string", "readWrite", "none", false);
        attr(ua, "preferredLanguage", "string", "readWrite", "none", false);
        attr(ua, "timezone", "string", "readWrite", "none", false);
        attr(ua, "title", "string", "readWrite", "none", false);
        ObjectNode groups = complex(ua, "groups", true);
        groups.put("mutability", "readOnly");

        ObjectNode group = schema(items, ScimResources.GROUP_SCHEMA, "Group", "A Sangam group (persons only, no nested groups)");
        ArrayNode ga = group.putArray("attributes");
        attr(ga, "displayName", "string", "readWrite", "none", false).put("required", true);
        attr(ga, "externalId", "string", "readWrite", "server", true);
        complex(ga, "members", true);

        ObjectNode ext = schema(items, ScimResources.SANGAM_USER, "SangamUser", "Sangam person id and postings (read-only)");
        ArrayNode ea = ext.putArray("attributes");
        attr(ea, "personId", "string", "readOnly", "server", true);
        attr(ea, "orgUnitId", "string", "readOnly", "none", true);
        ObjectNode postings = complex(ea, "postings", true);
        postings.put("mutability", "readOnly");
        postings.put("description", "Seats held (IX.5). Read-only: a request that changes them is 400 mutability (decision 154)");
        return list(items);
    }

    private static ObjectNode list(ArrayNode items) {
        ObjectNode r = Sql.JSON.createObjectNode();
        r.set("schemas", ScimResources.schemas(ScimResources.LIST_RESPONSE));
        r.put("totalResults", items.size());
        r.put("itemsPerPage", items.size());
        r.put("startIndex", 1);
        r.set("Resources", items);
        return r;
    }

    private static ObjectNode schema(ArrayNode items, String id, String name, String description) {
        ObjectNode s = items.addObject();
        s.set("schemas", ScimResources.schemas(SCHEMA_SCHEMA));
        s.put("id", id).put("name", name).put("description", description);
        s.putObject("meta").put("resourceType", "Schema").put("location", ScimResources.BASE + "Schemas/" + id);
        return s;
    }

    private static ObjectNode attr(ArrayNode attrs, String name, String type, String mutability, String uniqueness,
                                   boolean caseExact) {
        return attrs.addObject().put("name", name).put("type", type).put("multiValued", false).put("required", false)
                .put("caseExact", caseExact).put("mutability", mutability).put("returned", "default")
                .put("uniqueness", uniqueness);
    }

    private static ObjectNode complex(ArrayNode attrs, String name, boolean multiValued) {
        return attrs.addObject().put("name", name).put("type", "complex").put("multiValued", multiValued)
                .put("required", false).put("mutability", "readWrite").put("returned", "default");
    }
}
