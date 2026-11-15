// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.io.IOException;
import java.sql.Connection;
import java.util.function.BiFunction;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.security.scim.ScimDiscovery;
import org.sangam.security.scim.ScimException;
import org.sangam.security.scim.ScimResources;
import org.sangam.security.scim.ScimService;
import org.sangam.server.bridge.SecurityIdentities;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCIM 2.0 endpoint {@code /scim/v2} (M-099, contract {@code sangam-rest/api/routes/scim.py}; owner decision 154).
 * A thin adapter: the endpoint permission {@code scim.provision} (SecurityPermissionBridge), then
 * {@link ScimService} in one product transaction, which applies the object checks, the read-only postings rule and the
 * audit. Bodies are read and written as text with the {@code application/scim+json} media type, and every error is a
 * SCIM error body (RFC 7644 section 3.12), not RFC 9457, because identity providers follow the RFC.
 */
@RestController
@RequestMapping("/scim/v2")
public class ScimController {

    public static final String SCIM_JSON = "application/scim+json";
    public static final String PERMISSION = "scim.provision";
    private static final MediaType SCIM = MediaType.parseMediaType(SCIM_JSON);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectProvider<ScimService> scim;
    private final ObjectProvider<DataSource> dataSource;
    private final PermissionPort permissions;

    public ScimController(ObjectProvider<ScimService> scim, ObjectProvider<DataSource> dataSource, PermissionPort permissions) {
        this.scim = scim;
        this.dataSource = dataSource;
        this.permissions = permissions;
    }

    // ------------------------------------------------------------------ discovery

    @GetMapping("/ServiceProviderConfig")
    public ResponseEntity<String> serviceProviderConfig(RequestContext ctx) {
        return guarded(ctx, () -> ok(ScimDiscovery.serviceProviderConfig()));
    }

    @GetMapping("/ResourceTypes")
    public ResponseEntity<String> resourceTypes(RequestContext ctx) {
        return guarded(ctx, () -> ok(ScimDiscovery.resourceTypes()));
    }

    @GetMapping("/Schemas")
    public ResponseEntity<String> schemas(RequestContext ctx) {
        return guarded(ctx, () -> ok(ScimDiscovery.schemas()));
    }

    // ------------------------------------------------------------------ users

    @GetMapping("/Users")
    public ResponseEntity<String> listUsers(RequestContext ctx, @RequestParam(required = false) String filter,
                                            @RequestParam(required = false) Integer startIndex,
                                            @RequestParam(required = false) Integer count,
                                            @RequestParam(required = false) String attributes,
                                            @RequestParam(required = false) String excludedAttributes) {
        return guarded(ctx, () -> ok(project(tx((s, c) -> s.listUsers(c, id(ctx), filter, startIndex, count)),
                attributes, excludedAttributes)));
    }

    @PostMapping("/Users")
    public ResponseEntity<String> createUser(RequestContext ctx, @RequestBody(required = false) String body) {
        return guarded(ctx, () -> {
            JsonNode b = parse(body);
            return created(tx((s, c) -> s.createUser(c, id(ctx), b)));
        });
    }

    @GetMapping("/Users/{id}")
    public ResponseEntity<String> getUser(RequestContext ctx, @PathVariable String id,
                                          @RequestParam(required = false) String attributes,
                                          @RequestParam(required = false) String excludedAttributes) {
        return guarded(ctx, () -> resource(ScimResources.project(tx((s, c) -> s.getUser(c, id(ctx), id)), attributes,
                excludedAttributes)));
    }

    @PutMapping("/Users/{id}")
    public ResponseEntity<String> replaceUser(RequestContext ctx, @PathVariable String id,
                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                              @RequestBody(required = false) String body) {
        return guarded(ctx, () -> {
            JsonNode b = parse(body);
            return resource(tx((s, c) -> s.replaceUser(c, id(ctx), id, b, ifMatch)));
        });
    }

    @PatchMapping("/Users/{id}")
    public ResponseEntity<String> patchUser(RequestContext ctx, @PathVariable String id,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                            @RequestBody(required = false) String body) {
        return guarded(ctx, () -> {
            JsonNode b = parse(body);
            return resource(tx((s, c) -> s.patchUser(c, id(ctx), id, b, ifMatch)));
        });
    }

    @DeleteMapping("/Users/{id}")
    public ResponseEntity<String> deleteUser(RequestContext ctx, @PathVariable String id,
                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return guarded(ctx, () -> {
            tx((s, c) -> {
                s.deleteUser(c, id(ctx), id, ifMatch);
                return null;
            });
            return ResponseEntity.noContent().build();
        });
    }

    // ------------------------------------------------------------------ groups

    @GetMapping("/Groups")
    public ResponseEntity<String> listGroups(RequestContext ctx, @RequestParam(required = false) String filter,
                                             @RequestParam(required = false) Integer startIndex,
                                             @RequestParam(required = false) Integer count,
                                             @RequestParam(required = false) String attributes,
                                             @RequestParam(required = false) String excludedAttributes) {
        return guarded(ctx, () -> ok(project(tx((s, c) -> s.listGroups(c, id(ctx), filter, startIndex, count)),
                attributes, excludedAttributes)));
    }

    @PostMapping("/Groups")
    public ResponseEntity<String> createGroup(RequestContext ctx, @RequestBody(required = false) String body) {
        return guarded(ctx, () -> {
            JsonNode b = parse(body);
            return created(tx((s, c) -> s.createGroup(c, id(ctx), b)));
        });
    }

    @GetMapping("/Groups/{id}")
    public ResponseEntity<String> getGroup(RequestContext ctx, @PathVariable String id,
                                           @RequestParam(required = false) String attributes,
                                           @RequestParam(required = false) String excludedAttributes) {
        return guarded(ctx, () -> resource(ScimResources.project(tx((s, c) -> s.getGroup(c, id(ctx), id)), attributes,
                excludedAttributes)));
    }

    @PutMapping("/Groups/{id}")
    public ResponseEntity<String> replaceGroup(RequestContext ctx, @PathVariable String id,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @RequestBody(required = false) String body) {
        return guarded(ctx, () -> {
            JsonNode b = parse(body);
            return resource(tx((s, c) -> s.replaceGroup(c, id(ctx), id, b, ifMatch)));
        });
    }

    @PatchMapping("/Groups/{id}")
    public ResponseEntity<String> patchGroup(RequestContext ctx, @PathVariable String id,
                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                             @RequestBody(required = false) String body) {
        return guarded(ctx, () -> {
            JsonNode b = parse(body);
            return resource(tx((s, c) -> s.patchGroup(c, id(ctx), id, b, ifMatch)));
        });
    }

    @DeleteMapping("/Groups/{id}")
    public ResponseEntity<String> deleteGroup(RequestContext ctx, @PathVariable String id,
                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return guarded(ctx, () -> {
            tx((s, c) -> {
                s.deleteGroup(c, id(ctx), id, ifMatch);
                return null;
            });
            return ResponseEntity.noContent().build();
        });
    }

    // ------------------------------------------------------------------ plumbing

    /** The endpoint check, then the work; every failure becomes a SCIM error body. */
    private ResponseEntity<String> guarded(RequestContext ctx, java.util.function.Supplier<ResponseEntity<String>> work) {
        try {
            permissions.require(ctx, PERMISSION);
            return work.get();
        } catch (ScimException e) {
            return error(e);
        } catch (SangamSecurityException e) {
            return error(new ScimException(e.httpStatus(), null, e.getMessage()));
        } catch (ApiProblem e) {
            return error(new ScimException(e.status(), null, e.detail() != null ? e.detail() : e.title()));
        }
    }

    private <T> T tx(BiFunction<ScimService, Connection, T> work) {
        ScimService service = scim.getIfAvailable();
        if (service == null) {
            throw new ScimException(501, null, "SCIM provisioning is not installed (module sangam-security)");
        }
        return Transactions.of(dataSource.getObject()).inTransaction(c -> work.apply(service, c));
    }

    private static SecurityIdentity id(RequestContext ctx) {
        return SecurityIdentities.of(ctx);
    }

    static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new ScimException(400, "invalidSyntax", "a JSON body is required");
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new ScimException(400, "invalidSyntax", "the body is not valid JSON");
        }
    }

    private static ObjectNode project(ObjectNode list, String attributes, String excludedAttributes) {
        JsonNode items = list.get("Resources");
        if (items != null && items.isArray()) {
            for (int i = 0; i < items.size(); i++) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) items).set(i,
                        ScimResources.project((ObjectNode) items.get(i), attributes, excludedAttributes));
            }
        }
        return list;
    }

    private static ResponseEntity<String> ok(ObjectNode body) {
        return ResponseEntity.ok().contentType(SCIM).body(body.toString());
    }

    /** One resource: its ETag in the header as well as in meta.version (RFC 7644 section 3.14). */
    private static ResponseEntity<String> resource(ObjectNode body) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(SCIM);
        String etag = body.path("meta").path("version").asText(null);
        if (etag != null) {
            b.eTag(etag);
        }
        return b.body(body.toString());
    }

    private static ResponseEntity<String> created(ObjectNode body) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(201).contentType(SCIM);
        String location = body.path("meta").path("location").asText(null);
        if (location != null) {
            b.header(HttpHeaders.LOCATION, location);
        }
        String etag = body.path("meta").path("version").asText(null);
        if (etag != null) {
            b.eTag(etag);
        }
        return b.body(body.toString());
    }

    static ResponseEntity<String> error(ScimException e) {
        return ResponseEntity.status(e.status()).contentType(SCIM).body(ScimResources.error(e).toString());
    }
}
