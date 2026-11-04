// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.config;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.api.ConfigConnector;
import org.sangam.config.catalogue.DocumentType;
import org.sangam.config.catalogue.DocumentTypeCatalogue;
import org.sangam.config.common.Finding;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.MasterCode;
import org.sangam.config.master.MasterStore;
import org.sangam.config.master.SeedLoader;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileRepository;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ProfileValidator;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/config} (sangam-config {@code api/ConfigPorts}): layered configuration profiles with maker-checker
 * lifecycle, master codes and classification schemes, the document-type catalogue and crosswalks (ADR-D-20).
 *
 * <p>Reads are open to {@code config.read} for any scope (configuration is jurisdiction data, not personal data). Writes
 * are limited to the caller's own scope: a profile id equal to the tenant or below it ({@code <tenant>.x},
 * {@code <tenant>/office}); platform administrators ({@code sangam-admin}) may write any profile.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final ProfileResolver resolver;
    private final ConfigAdminService admin;
    private final ProfileRepository repository;
    private final MasterStore master;
    private final ClassificationRegistry classification;
    private final ProfileValidator validator;
    private final ConfigConnector connector;
    private final PermissionPort permissions;

    public ConfigController(ProfileResolver resolver, ConfigAdminService admin, ProfileRepository repository,
                            MasterStore master, ClassificationRegistry classification, ProfileValidator validator,
                            ConfigConnector connector, PermissionPort permissions) {
        this.resolver = resolver;
        this.admin = admin;
        this.repository = repository;
        this.master = master;
        this.classification = classification;
        this.validator = validator;
        this.connector = connector;
        this.permissions = permissions;
    }

    /** One stored version of a profile document. */
    public record ProfileVersion(String id, String version, String layer, String status, String hash,
                                 LocalDate effectiveFrom, LocalDate effectiveTo, String source) {
    }

    public record ProfileVersionDocument(ProfileVersion version, Map<String, Object> document) {
    }

    public record FindingsResult(boolean valid, List<Finding> findings) {
        static FindingsResult of(List<Finding> f) {
            return new FindingsResult(!Finding.anyError(f), f);
        }
    }

    public record ApproveRequest(String presentedHash) {
    }

    public record RejectRequest(String reason) {
    }

    public record ActivateRequest(List<String> scopesToCheck) {
    }

    public record ValidateRequest(String scope, String office, LocalDate asOf) {
    }

    public record SeedResult(int count) {
    }

    public record ClassificationRequest(String scope, String office, String scheme, String code, LocalDate asOf) {
    }

    public record DocumentCheckRequest(String scope, String office, Map<String, Object> document, LocalDate asOf) {
    }

    public record CrosswalkRequest(String scope, String office, String code, String amount, LocalDate asOf) {
    }

    // ---------------------------------------------------------------- profiles

    @GetMapping("/profiles")
    public ResolvedProfile resolve(RequestContext ctx, @RequestParam(required = false) String scope,
            @RequestParam(required = false) String office,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        permissions.require(ctx, ConfigPermissions.READ);
        return resolver.resolve(scope(ctx, scope), blank(office), asOf == null ? LocalDate.now() : asOf);
    }

    @GetMapping("/profiles/{id}/versions")
    public ResponseEntity<List<ProfileVersion>> versions(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, ConfigPermissions.READ);
        return Lists.ok(repository.versions(id).stream().map(ConfigController::view).toList());
    }

    @GetMapping("/profiles/{id}/versions/{version}")
    public ProfileVersionDocument version(RequestContext ctx, @PathVariable String id, @PathVariable String version) {
        permissions.require(ctx, ConfigPermissions.READ);
        ProfileRepository.Stored s = repository.get(id, version)
                .orElseThrow(() -> ApiProblem.notFound("profile version", id + "@" + version));
        return new ProfileVersionDocument(view(s), s.document().toTree());
    }

    /** Saves a draft; the body is the profile document as YAML or JSON (its id and version must match the path). */
    @PutMapping(value = "/profiles/{id}/versions/{version}",
            consumes = {"application/yaml", "application/x-yaml", "text/yaml", MediaType.TEXT_PLAIN_VALUE,
                    MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<FindingsResult> saveDraft(RequestContext ctx, @PathVariable String id,
                                                    @PathVariable String version, @RequestBody String body) {
        permissions.require(ctx, ConfigPermissions.WRITE);
        requireOwned(ctx, id);
        ProfileDocument doc;
        try {
            doc = ProfileLoader.parse(body);
        } catch (RuntimeException e) {
            throw ApiProblem.badRequest("PROFILE_UNPARSEABLE", e.getMessage());
        }
        if (!id.equals(doc.id()) || !version.equals(doc.version())) {
            throw ApiProblem.badRequest("ID_MISMATCH", "the document is " + doc.id() + "@" + doc.version()
                    + ", the path is " + id + "@" + version);
        }
        return Lists.okRef(id + "@" + version, FindingsResult.of(admin.saveDraft(doc, ctx.principalId())));
    }

    @PostMapping("/profiles/{id}/versions/{version}/submit")
    public ResponseEntity<Void> submit(RequestContext ctx, @PathVariable String id, @PathVariable String version) {
        permissions.require(ctx, ConfigPermissions.WRITE);
        requireOwned(ctx, id);
        admin.submit(id, version, ctx.principalId());
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id + "@" + version).build();
    }

    @PostMapping("/profiles/{id}/versions/{version}/approve")
    public ResponseEntity<Void> approve(RequestContext ctx, @PathVariable String id, @PathVariable String version,
                                        @RequestBody ApproveRequest body) {
        permissions.require(ctx, ConfigPermissions.ADMIN);
        requireOwned(ctx, id);
        if (body == null || body.presentedHash() == null || body.presentedHash().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "presentedHash (the hash the checker reviewed) is required");
        }
        admin.approve(id, version, ctx.principalId(), body.presentedHash());
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id + "@" + version).build();
    }

    @PostMapping("/profiles/{id}/versions/{version}/reject")
    public ResponseEntity<Void> reject(RequestContext ctx, @PathVariable String id, @PathVariable String version,
                                       @RequestBody RejectRequest body) {
        permissions.require(ctx, ConfigPermissions.ADMIN);
        requireOwned(ctx, id);
        if (body == null || body.reason() == null || body.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "a rejection needs a reason");
        }
        admin.reject(id, version, ctx.principalId(), body.reason());
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id + "@" + version).build();
    }

    @PostMapping("/profiles/{id}/versions/{version}/activate")
    public ResponseEntity<FindingsResult> activate(RequestContext ctx, @PathVariable String id,
            @PathVariable String version, @RequestBody(required = false) ActivateRequest body) {
        permissions.require(ctx, ConfigPermissions.ADMIN);
        requireOwned(ctx, id);
        List<String> scopes = body == null || body.scopesToCheck() == null ? List.of() : body.scopesToCheck();
        return Lists.okRef(id + "@" + version, FindingsResult.of(admin.activate(id, version, ctx.principalId(), scopes)));
    }

    @GetMapping("/profiles/{id}/history")
    public ResponseEntity<List<ConfigAdminService.ChangeRecord>> history(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, ConfigPermissions.READ);
        return Lists.ok(admin.history(id));
    }

    @PostMapping("/validate")
    public FindingsResult validate(RequestContext ctx, @RequestBody ValidateRequest body) {
        permissions.require(ctx, ConfigPermissions.READ);
        String scope = scope(ctx, body == null ? null : body.scope());
        LocalDate asOf = body == null || body.asOf() == null ? LocalDate.now() : body.asOf();
        return FindingsResult.of(validator.validate(resolver, scope, body == null ? null : blank(body.office()), asOf));
    }

    // ---------------------------------------------------------------- master data and classification

    @GetMapping("/master/{scheme}/{segment}")
    public ResponseEntity<List<MasterCode>> codes(RequestContext ctx, @PathVariable String scheme,
            @PathVariable String segment, @RequestParam(required = false) String scope,
            @RequestParam(required = false) String parent,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        permissions.require(ctx, ConfigPermissions.READ);
        String s = scope(ctx, scope);
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        List<MasterCode> all = parent == null ? master.list(s, scheme, segment)
                : master.find(s, scheme, segment, parent, null);
        return Lists.ok(all.stream().filter(c -> c.validOn(d)).toList());
    }

    /** Loads master codes from a CSV or YAML body ({@code ?format=csv|yaml}); idempotent (upsert by key). */
    @PostMapping(value = "/master/seed",
            consumes = {"text/csv", "application/yaml", "application/x-yaml", "text/yaml", MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<SeedResult> seed(RequestContext ctx, @RequestParam(required = false) String scope,
            @RequestParam(required = false, defaultValue = "csv") String format, @RequestBody String body) {
        permissions.require(ctx, ConfigPermissions.WRITE);
        String s = scope(ctx, scope);
        requireOwned(ctx, s);
        List<MasterCode> codes = switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "csv" -> SeedLoader.csv(s, body);
            case "yaml", "yml" -> SeedLoader.yaml(s, body);
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "format must be csv or yaml");
        };
        return Lists.okRef(s, new SeedResult(SeedLoader.load(master, codes)));
    }

    @PostMapping("/master/correction-slips")
    public ResponseEntity<FindingsResult> correctionSlip(RequestContext ctx,
                                                         @RequestBody ClassificationRegistry.CorrectionSlip slip) {
        permissions.require(ctx, ConfigPermissions.WRITE);
        if (slip == null || slip.scope() == null || slip.scheme() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "scope and scheme are required");
        }
        requireOwned(ctx, slip.scope());
        return Lists.okRef(slip.id(), FindingsResult.of(classification.apply(slip)));
    }

    @PostMapping("/classification/validate")
    public Map<String, Object> validateClassification(RequestContext ctx, @RequestBody ClassificationRequest body) {
        permissions.require(ctx, ConfigPermissions.READ);
        if (body == null || body.scheme() == null || body.code() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "scheme and code are required");
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("scheme", body.scheme());
        in.put("code", body.code());
        put(in, "office", body.office());
        put(in, "asOf", body.asOf());
        return ModuleConnectors.call(connector, ctx, scope(ctx, body.scope()), "classification.validate", in);
    }

    // ---------------------------------------------------------------- document types and crosswalks

    @GetMapping("/document-types")
    public ResponseEntity<List<Map<String, Object>>> documentTypes(RequestContext ctx,
            @RequestParam(required = false) String scope, @RequestParam(required = false) String family,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        permissions.require(ctx, ConfigPermissions.READ);
        DocumentTypeCatalogue cat = DocumentTypeCatalogue.of(resolver.resolve(scope(ctx, scope),
                asOf == null ? LocalDate.now() : asOf));
        List<DocumentType> types = family == null ? cat.all() : cat.family(family);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocumentType t : types) {
            Map<String, Object> m = new LinkedHashMap<>(t.raw());
            m.putIfAbsent("id", t.id());
            out.add(m);
        }
        return Lists.ok(out);
    }

    @PostMapping("/document-types/{id}/check")
    public Map<String, Object> checkDocument(RequestContext ctx, @PathVariable String id,
                                             @RequestBody DocumentCheckRequest body) {
        permissions.require(ctx, ConfigPermissions.READ);
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("typeId", id);
        in.put("document", body == null || body.document() == null ? Map.of() : body.document());
        put(in, "office", body == null ? null : body.office());
        put(in, "asOf", body == null ? null : body.asOf());
        return ModuleConnectors.call(connector, ctx, scope(ctx, body == null ? null : body.scope()),
                "documentType.check", in);
    }

    @PostMapping("/crosswalks/{id}/map")
    public Map<String, Object> mapCrosswalk(RequestContext ctx, @PathVariable String id,
                                            @RequestBody CrosswalkRequest body) {
        permissions.require(ctx, ConfigPermissions.READ);
        if (body == null || body.code() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "code is required");
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("crosswalk", id);
        in.put("code", body.code());
        put(in, "amount", body.amount());
        put(in, "office", body.office());
        put(in, "asOf", body.asOf());
        return ModuleConnectors.call(connector, ctx, scope(ctx, body.scope()), "crosswalk.map", in);
    }

    // ---------------------------------------------------------------- helpers

    private static ProfileVersion view(ProfileRepository.Stored s) {
        ProfileDocument d = s.document();
        return new ProfileVersion(d.id(), d.version(), d.layer(), s.status().name(), s.hash(), d.effectiveFrom(),
                d.effectiveTo(), d.source());
    }

    /** The scope defaults to the caller's tenant (a tenant id is a scope id, e.g. {@code RJ.PWD}). */
    private static String scope(RequestContext ctx, String scope) {
        return scope == null || scope.isBlank() ? ctx.tenantId() : scope.trim();
    }

    /** Writes only within the caller's own scope, unless platform administrator. */
    private static void requireOwned(RequestContext ctx, String profileOrScopeId) {
        if (ctx.inGroup("sangam-admin")) {
            return;
        }
        String t = ctx.tenantId();
        if (profileOrScopeId == null || !(profileOrScopeId.equals(t) || profileOrScopeId.startsWith(t + ".")
                || profileOrScopeId.startsWith(t + "/"))) {
            throw ApiProblem.forbidden("SCOPE_NOT_OWNED", "profile " + profileOrScopeId
                    + " is outside the scope of tenant " + t);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v.toString());
        }
    }
}
