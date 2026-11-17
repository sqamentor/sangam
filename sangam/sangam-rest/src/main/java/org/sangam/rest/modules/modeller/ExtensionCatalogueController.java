// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.modeller.catalogue.CatalogueClient;
import org.sangam.modeller.catalogue.CatalogueKeys;
import org.sangam.modeller.catalogue.CatalogueSource;
import org.sangam.modeller.catalogue.ExtensionCatalogue;
import org.sangam.modeller.template.CatalogueConnectorTemplates;
import org.sangam.modeller.template.ElementTemplates;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.web.Lists;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-128 / NG-17, owner decision 380: the platform-wide extension catalogue and the per-tenant install, behind the
 * contract routes of {@code routes/catalogue.py}.
 *
 * <ul>
 *   <li>Source (one of): {@code sangam.modeller.catalogue.index} and {@code .signature} (files of a mirrored
 *       catalogue, the air-gapped form; artefacts are files under the index's directory), or
 *       {@code sangam.modeller.catalogue.url} (an {@code https://} index URL, signature at the URL plus {@code .sig})
 *       fetched through {@link HttpTransport} with the allow-list {@code sangam.modeller.catalogue.egress}
 *       ({@code scheme://host[:port][/path]} entries; none = every fetch is refused). Neither: the routes answer 404.</li>
 *   <li>{@code sangam.modeller.catalogue.trusted-public-key-refs}: {@code secret://} references to the base64 X.509
 *       Ed25519 public keys an index may be signed with (decision 380 P3). The former literal
 *       {@code sangam.modeller.catalogue.public-key} is refused at start-up.</li>
 *   <li>{@code sangam.modeller.catalogue.allowed-licences}: the licences an install accepts (none: no install passes).</li>
 *   <li>Install of kind {@code package} (P2 (a)): the catalogue checks run first, then {@link PackagePort#importBundle} in the
 *       caller's tenant (T70: the bundle's own signatures and hash, PACKAGE_IMPORTED audit; the package lands APPROVED
 *       and activation still needs a deployer and the ENV_BINDING values).</li>
 *   <li>Install of kind {@code connector-template} (P2 (b)): the same catalogue checks, then the artefact's connector
 *       element templates ({@link CatalogueConnectorTemplates}) are published in the caller's tenant, each changed one
 *       as a new version, in one transaction with the {@value #TEMPLATES_INSTALLED} audit event. Code kinds are never
 *       installed (P2 (c)).</li>
 * </ul>
 * Start-up refuses an inconsistent configuration (both sources, a literal key, a missing or unresolved key reference).
 */
@RestController
@RequestMapping("/api/v1")
public class ExtensionCatalogueController {

    static final String P = "sangam.modeller.catalogue.";

    /** Class-B audit event of a connector-template install (SANGAM.md X.4.0.3, Admin group; decision 380 P2 (b)). */
    public static final String TEMPLATES_INSTALLED = "CATALOGUE_TEMPLATES_INSTALLED";
    private static final ObjectMapper BUNDLES = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final PackagePort packages;
    private final PermissionPort permissions;
    private final CatalogueClient client;
    private final ElementTemplates templates;
    private final AuditPort audit;

    @Autowired
    public ExtensionCatalogueController(PackagePort packages, PermissionPort permissions, Environment env,
                                        ObjectProvider<SecretResolver> secrets, ObjectProvider<ElementTemplates> templates,
                                        ObjectProvider<AuditPort> audit) {
        this(packages, permissions, client(env, secrets.getIfAvailable(SecretResolver::fromEnvironment)),
                templates.getIfAvailable(), audit.getIfAvailable());
    }

    ExtensionCatalogueController(PackagePort packages, PermissionPort permissions, CatalogueClient client) {
        this(packages, permissions, client, null, null);
    }

    ExtensionCatalogueController(PackagePort packages, PermissionPort permissions, CatalogueClient client,
                                 ElementTemplates templates, AuditPort audit) {
        this.packages = packages;
        this.permissions = permissions;
        this.client = client;
        this.templates = templates;
        this.audit = audit;
    }

    /** What a connector-template install did: the templates published (id and new version) and those already current. */
    public record CatalogueTemplatesInstalled(String id, String version, List<CatalogueInstalledTemplate> published, List<String> unchanged) {
    }

    public record CatalogueInstalledTemplate(String id, int version) {
    }

    /** The install request: why the package is installed (kept in the import's audit note). */
    public record CatalogueInstallRequest(String reason) {
    }

    @GetMapping("/catalogue")
    public ResponseEntity<List<ExtensionCatalogue.Entry>> catalogue(RequestContext ctx) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return Lists.ok(configured().entries());
    }

    @PostMapping("/catalogue/by-id/by-version/install")
    public ResponseEntity<PackagePort.Package> install(RequestContext ctx, @RequestParam("id") String id, @RequestParam("version") String version,
                                                       @RequestBody(required = false) CatalogueInstallRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
        CatalogueClient.Verified v = configured().forInstall(id, version);
        PackagePort.PackageBundle bundle;
        try {
            bundle = BUNDLES.readValue(v.artifact(), PackagePort.PackageBundle.class);
        } catch (java.io.IOException | RuntimeException e) {
            throw ApiProblem.unprocessable("CATALOGUE_ARTIFACT_INVALID", "the artefact of " + id + "@" + version + " is not a package bundle");
        }
        if (bundle == null || bundle.packageHash() == null) {
            throw ApiProblem.unprocessable("CATALOGUE_ARTIFACT_INVALID", "the artefact of " + id + "@" + version + " is not a package bundle");
        }
        String note = "catalogue " + id + "@" + version + " (" + v.entry().publisher() + ")"
                + (b == null || b.reason() == null || b.reason().isBlank() ? "" : ": " + b.reason());
        PackagePort.Package p = packages.importBundle(ctx, new PackagePort.PackageImportRequest(bundle, note));
        return Lists.created("/api/v1/packages/" + p.id(), p.id(), p);
    }

    /**
     * Decision 380 P2 (b): publishes the connector element templates of entry {@code id}@{@code version} (kind
     * {@code connector-template}) in the caller's tenant, after the same catalogue checks as a package install. Only
     * changed templates get a new version, together with one {@value #TEMPLATES_INSTALLED} event in the same
     * transaction; a repeated install writes nothing.
     */
    @PostMapping("/catalogue/by-id/by-version/install-templates")
    public CatalogueTemplatesInstalled installTemplates(RequestContext ctx, @RequestParam("id") String id, @RequestParam("version") String version,
                                                        @RequestBody(required = false) CatalogueInstallRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        CatalogueClient c = configured();
        if (templates == null || audit == null) {
            throw ApiProblem.notInstalled("catalogue connector templates", "the modeller template store and the audit port");
        }
        CatalogueClient.Verified v = c.forInstall(id, version, ExtensionCatalogue.KIND_CONNECTOR_TEMPLATE);
        List<Map<String, Object>> docs = CatalogueConnectorTemplates.read(id, v.artifact());
        String reason = b == null || b.reason() == null || b.reason().isBlank() ? null : b.reason().strip();
        CatalogueConnectorTemplates.Installed done = CatalogueConnectorTemplates.install(templates, ctx.tenantId(), ctx.principalId(), docs,
                published -> {
                    Map<String, Object> p = new java.util.LinkedHashMap<>();
                    p.put("entryId", id);
                    p.put("entryVersion", version);
                    p.put("publisher", v.entry().publisher());
                    p.put("sha256", v.entry().sha256());
                    p.put("templates", published.stream().map(t -> t.id() + "@" + t.version()).toList());
                    if (reason != null) {
                        p.put("reason", reason);
                    }
                    audit.record(ctx, "catalogue-install:" + id + "@" + version, TEMPLATES_INSTALLED, p);
                });
        return new CatalogueTemplatesInstalled(id, version,
                done.published().stream().map(t -> new CatalogueInstalledTemplate(t.id(), t.version())).toList(), done.unchanged());
    }

    /** The problem of a catalogue refusal, for {@link CatalogueProblemAdvice} (both catalogue controllers). */
    static ApiProblem problem(ExtensionCatalogue.CatalogueException e) {
        return switch (e.code()) {
            case "CATALOGUE_ENTRY_NOT_FOUND" -> ApiProblem.notFound("catalogue entry", e.getMessage().substring(e.code().length()).replaceFirst("^: ", ""));
            case "CATALOGUE_UNREACHABLE", "CATALOGUE_INDEX_UNREADABLE" -> ApiProblem.unavailable(e.code(), e.getMessage(), 60);
            default -> ApiProblem.unprocessable(e.code(), e.getMessage());
        };
    }

    private CatalogueClient configured() {
        if (client == null) {
            throw ApiProblem.notFound("extension catalogue", "not configured");
        }
        return client;
    }

    /** The catalogue client of this configuration, or null when no source is configured. */
    static CatalogueClient client(Environment env, SecretResolver secrets) {
        if (env.getProperty(P + "public-key") != null) {
            throw new IllegalStateException(P + "public-key (a literal key) is no longer read: give "
                    + P + "trusted-public-key-refs as secret:// references (owner decision 380)");
        }
        String index = env.getProperty(P + "index");
        String url = env.getProperty(P + "url");
        if (index != null && url != null) {
            throw new IllegalStateException("configure either " + P + "index (a mirrored file) or " + P + "url (hosted), not both");
        }
        if (index == null && url == null) {
            return null;
        }
        try {
            CatalogueSource source;
            if (index != null) {
                source = new CatalogueSource.Offline(Path.of(index), Path.of(env.getRequiredProperty(P + "signature")));
            } else {
                HttpTransport transport = new HttpTransport(CatalogueSource.egress(list(env, "egress")), null,
                        Duration.ofSeconds(10), Duration.ofSeconds(60), null);
                source = new CatalogueSource.Hosted(URI.create(url), CatalogueSource.over(transport));
            }
            String version = env.getProperty("sangam.version", ExtensionCatalogueController.class.getPackage().getImplementationVersion() == null
                    ? "0.1.0-SNAPSHOT" : ExtensionCatalogueController.class.getPackage().getImplementationVersion());
            return new CatalogueClient(source, CatalogueKeys.trusted(list(env, "trusted-public-key-refs"), secrets),
                    new HashSet<>(list(env, "allowed-licences")), version);
        } catch (ExtensionCatalogue.CatalogueException | IllegalArgumentException e) {
            throw new IllegalStateException("the extension catalogue configuration is refused: " + e.getMessage(), e);
        }
    }

    private static List<String> list(Environment env, String name) {
        return Binder.get(env).bind(P + name, Bindable.listOf(String.class)).orElse(List.of()).stream()
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
