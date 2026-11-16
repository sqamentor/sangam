// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.sangam.rest.extension.TenantCustomFieldRegistry;
import org.sangam.rest.extension.TenantCustomFieldRegistry.FieldDefinition;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SL-40: {@code /api/v1/custom-fields}: the tenant's custom fields ({@link TenantCustomFieldRegistry}) on forms
 * ({@code form:<formKey>}) and registered object types. The tenant is always the caller's (VI.4 rule 6).
 * <ul>
 *   <li>Read: {@code form.read} (custom fields extend forms). Define and retire: {@code tenant.admin} (tenant
 *       configuration).</li>
 *   <li>A define adds a new effective-dated version (the previous one is closed on its start date); a retire closes the
 *       current version on {@code effectiveTo} and needs a reason. Nothing is deleted. Both are audited on the
 *       tenant's security chain ({@code sec:<tenant>}) in the change's transaction.</li>
 *   <li>Problems: 400 {@code VALIDATION_FAILED} (malformed request, with {@code field}) and {@code REASON_REQUIRED};
 *       404 {@code NOT_FOUND}; 409 {@code CUSTOM_FIELD_CONFLICT} (changed concurrently); 422
 *       {@code CUSTOM_FIELD_INVALID} (a well-formed definition that breaks a rule: key, schema profile, dates).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/custom-fields")
public class TenantCustomFieldController {

    static final String BASE = "/api/v1/custom-fields";
    private static final String INVALID_PREFIX = "CUSTOM_FIELD_INVALID: ";
    private static final String CONFLICT_PREFIX = "CUSTOM_FIELD_CONFLICT: ";

    /** One version of a custom field; dates are ISO {@code yyyy-MM-dd}, {@code effectiveTo} exclusive (null: open). */
    public record CustomField(String objectType, String key, int version, String label, boolean required,
                              boolean readOnly, Map<String, Object> schema, String effectiveFrom, String effectiveTo) {

        static CustomField of(FieldDefinition d) {
            return new CustomField(d.objectType(), d.key(), d.version(), d.label(), d.required(), d.readOnly(),
                    d.schema(), d.effectiveFrom().toString(), d.effectiveTo() == null ? null : d.effectiveTo().toString());
        }
    }

    /** A new version of a field. {@code required}/{@code readOnly} default to false. */
    public record DefineRequest(String objectType, String key, String label, Boolean required, Boolean readOnly,
                                Map<String, Object> schema, String effectiveFrom, String effectiveTo) {
    }

    /** Closes the current version on {@code effectiveTo} (exclusive); {@code reason} is mandatory (audited). */
    public record RetireRequest(String effectiveTo, String reason) {
    }

    private final ObjectProvider<TenantCustomFieldRegistry> registry;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final RestProperties props;

    public TenantCustomFieldController(ObjectProvider<TenantCustomFieldRegistry> registry, PermissionPort permissions,
                                       AuditPort audit, RestProperties props) {
        this.registry = registry;
        this.permissions = permissions;
        this.audit = audit;
        this.props = props;
    }

    private TenantCustomFieldRegistry registry(RequestContext ctx, String permission) {
        permissions.require(ctx, permission);
        TenantCustomFieldRegistry r = registry.getIfAvailable();
        if (r == null) {
            throw ApiProblem.notInstalled("tenant custom fields", "sangam-server");
        }
        return r;
    }

    static String tenant(RequestContext ctx) {
        if (ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw ApiProblem.forbidden("TENANT_REQUIRED", "The authenticated identity carries no tenant");
        }
        return ctx.tenantId();
    }

    static ApiProblem invalid(String field, String detail) {
        return ApiProblem.badRequest("VALIDATION_FAILED", detail).with("field", field);
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field, field + " is required");
        }
        return value;
    }

    /** An ISO date, or null when {@code value} is null; a malformed date is 400 naming the field. */
    static LocalDate date(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(field, field + " must be an ISO date (yyyy-MM-dd)");
        }
    }

    /** Maps the registry's refusals to problems (see the class comment); other failures are not rewritten. */
    static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (TenantCustomFieldRegistry.ConcurrentChangeException e) {
            throw ApiProblem.conflict("CUSTOM_FIELD_CONFLICT", strip(e.getMessage(), CONFLICT_PREFIX));
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(INVALID_PREFIX)) {
                throw ApiProblem.unprocessable("CUSTOM_FIELD_INVALID", strip(e.getMessage(), INVALID_PREFIX));
            }
            throw e;
        }
    }

    private static String strip(String message, String prefix) {
        return message != null && message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    /** All versions of the tenant's fields, filtered by object type and by the date they are in force on. */
    static List<CustomField> filter(List<FieldDefinition> history, String objectType, LocalDate on) {
        return history.stream()
                .filter(d -> objectType == null || objectType.equals(d.objectType()))
                .filter(d -> on == null || d.inForce(on))
                .map(CustomField::of).toList();
    }

    /** The versions route of a field, its object type and key query-encoded. */
    static String location(FieldDefinition d) {
        return BASE + "/versions?objectType=" + java.net.URLEncoder.encode(d.objectType(), java.nio.charset.StandardCharsets.UTF_8)
                + "&key=" + java.net.URLEncoder.encode(d.key(), java.nio.charset.StandardCharsets.UTF_8);
    }

    static String ref(FieldDefinition d) {
        return d.objectType() + "/" + d.key() + "@" + d.version();
    }

    private TenantCustomFieldRegistry.Audit audit(RequestContext ctx, String reason) {
        return (tenant, actor, action, payload) -> {
            Map<String, Object> p = new LinkedHashMap<>(payload);
            p.values().removeIf(java.util.Objects::isNull);
            if (reason != null) {
                p.put("reason", reason);
            }
            p.put("actor", ctx.actor());
            audit.record(ctx, "sec:" + tenant, action, p);
        };
    }

    @GetMapping
    public ResponseEntity<List<CustomField>> list(RequestContext ctx, @RequestParam(required = false) String objectType,
                                                  @RequestParam(required = false) String on,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        TenantCustomFieldRegistry r = registry(ctx, Permissions.FORM_READ);
        Page.PageRequest p = Lists.page(page, size);
        List<CustomField> all = filter(r.history(tenant(ctx)), objectType == null || objectType.isBlank() ? null : objectType,
                date(on, "on"));
        return Lists.ok(Page.slice(all, p, props.getCountCap()));
    }

    /**
     * UI-DEMO-1: an object type is "form:" + a form key, and form keys carry '/' ("rti/request"), which is refused
     * encoded in a path segment before the application, so the field is named by query parameters.
     */
    @GetMapping("/versions")
    public ResponseEntity<List<CustomField>> versions(RequestContext ctx, @RequestParam String objectType,
                                                      @RequestParam String key) {
        TenantCustomFieldRegistry r = registry(ctx, Permissions.FORM_READ);
        List<FieldDefinition> v = r.versions(tenant(ctx), objectType, key);
        if (v.isEmpty()) {
            throw ApiProblem.notFound("Custom field", objectType + "/" + key);
        }
        return Lists.ok(v.stream().map(CustomField::of).toList());
    }

    @PostMapping
    public ResponseEntity<CustomField> define(RequestContext ctx, @RequestBody(required = false) DefineRequest body) {
        TenantCustomFieldRegistry r = registry(ctx, Permissions.TENANT_ADMIN);
        String tenant = tenant(ctx);
        if (body == null) {
            throw invalid("objectType", "objectType is required");
        }
        String objectType = required(body.objectType(), "objectType");
        String key = required(body.key(), "key");
        if (body.schema() == null) {
            throw invalid("schema", "schema is required");
        }
        LocalDate from = date(required(body.effectiveFrom(), "effectiveFrom"), "effectiveFrom");
        LocalDate to = date(body.effectiveTo(), "effectiveTo");
        FieldDefinition d = call(() -> r.define(tenant, ctx.principalId(), objectType, key, body.label(),
                Boolean.TRUE.equals(body.required()), Boolean.TRUE.equals(body.readOnly()), body.schema(), from, to,
                audit(ctx, null)));
        return Lists.created(location(d), ref(d), CustomField.of(d));
    }

    @PostMapping("/retire")
    public ResponseEntity<CustomField> retire(RequestContext ctx, @RequestParam String objectType,
                                              @RequestParam String key,
                                              @RequestBody(required = false) RetireRequest body) {
        TenantCustomFieldRegistry r = registry(ctx, Permissions.TENANT_ADMIN);
        String tenant = tenant(ctx);
        if (body == null) {
            throw invalid("effectiveTo", "effectiveTo is required");
        }
        LocalDate to = date(required(body.effectiveTo(), "effectiveTo"), "effectiveTo");
        ProcessController.requireReason(body.reason());
        if (r.versions(tenant, objectType, key).isEmpty()) {
            throw ApiProblem.notFound("Custom field", objectType + "/" + key);
        }
        FieldDefinition d = call(() -> r.retire(tenant, ctx.principalId(), objectType, key, to,
                audit(ctx, body.reason())));
        return Lists.okRef(ref(d), CustomField.of(d));
    }
}
