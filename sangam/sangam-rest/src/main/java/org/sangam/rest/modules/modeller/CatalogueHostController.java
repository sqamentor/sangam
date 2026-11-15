// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.modeller.catalogue.CatalogueIndex;
import org.sangam.modeller.catalogue.ExtensionCatalogue;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-128 / NG-17 step 2, owner decision 380: the hosting side of the platform-wide extension catalogue.
 *
 * <ul>
 *   <li>Submit ({@value #PUBLISH}): a signed package bundle with its catalogue id, version, licence and minimum platform.
 *       Only kind {@code package} is hosted (P2 (a); code jars are never staged). The bundle must pass the package
 *       verification of this environment (T70: its hash recomputes, every artefact matches, it carries valid
 *       signatures); the stored artefact is the exact bundle bytes and the entry's SHA-256 is taken over them.</li>
 *   <li>Approve or reject ({@value #REVIEW}): only a SUBMITTED entry, and never by its publisher (422
 *       SEGREGATION_OF_DUTIES).</li>
 *   <li>Index, signature and artefacts ({@code GET /catalogue/hosted/...}): the approved entries only, the index signed
 *       with the host's own catalogue key, given as two {@code secret://} references
 *       ({@code sangam.modeller.catalogue.host.signing-key-ref}, PKCS#8, and {@code .host.public-key-ref}, X.509; decision
 *       37, P3). These are the URLs a consumer's {@code sangam.modeller.catalogue.url} names.</li>
 * </ul>
 * Off unless {@code sangam.modeller.catalogue.host.enabled=true}; then the key references must resolve to one key pair
 * and the document store must be present, or start-up is refused. Entries are platform-wide (P4 (i)): they are kept
 * under the document store's global tenant, and the two permissions are platform-only (the server maps them to
 * platform-issuer identities; no tenant persona group holds them).
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogueHostController {

    /** Platform-only: submit an entry to the platform-wide catalogue. */
    public static final String PUBLISH = "platform.catalogue.publish";
    /** Platform-only: approve or reject a submitted entry. */
    public static final String REVIEW = "platform.catalogue.review";

    static final String KIND = "catalogue:entry";
    static final String P = ExtensionCatalogueController.P + "host.";
    static final String SUBMITTED = "SUBMITTED";
    static final String APPROVED = "APPROVED";
    static final String REJECTED = "REJECTED";

    private static final ObjectMapper BUNDLES = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final PackagePort packages;
    private final PermissionPort permissions;
    private final DocumentStore store;
    private final AuditCheckpoints.Signer signer;

    public CatalogueHostController(PackagePort packages, PermissionPort permissions, Environment env,
                                   ObjectProvider<DocumentStore> store, ObjectProvider<SecretResolver> secrets) {
        this(packages, permissions, store.getIfAvailable(), signer(env, secrets.getIfAvailable(SecretResolver::fromEnvironment)));
    }

    CatalogueHostController(PackagePort packages, PermissionPort permissions, DocumentStore store, AuditCheckpoints.Signer signer) {
        if (signer != null && store == null) {
            throw new IllegalStateException(P + "enabled=true needs the document store (REST_DOCUMENT)");
        }
        this.packages = packages;
        this.permissions = permissions;
        this.store = store;
        this.signer = signer;
    }

    /** A submission: the catalogue fields and the signed package bundle. */
    public record CatalogueSubmissionRequest(String id, String version, String licence, String minPlatform,
                                             PackagePort.PackageBundle bundle, String reason) {
    }

    public record CatalogueReviewRequest(String reason) {
    }

    /** A hosted entry and its review state; the artefact itself is served at {@code artifactPath}. */
    public record CatalogueHostedEntry(String id, String version, String kind, String publisher, String licence,
                                       String sha256, String minPlatform, String artifactPath, String state,
                                       String submittedBy, String reviewedBy, String reason, String reviewReason,
                                       java.time.Instant submittedAt, java.time.Instant updatedAt) {
    }

    /** The stored body; {@code artifact} is the base64 of the exact bundle bytes. */
    public record Stored(String id, String version, String publisher, String licence, String sha256, String minPlatform,
                  String artifact, String submittedBy, String reviewedBy, String reason, String reviewReason) {
    }

    @PostMapping("/catalogue/entries")
    public ResponseEntity<CatalogueHostedEntry> submit(RequestContext ctx, @RequestBody CatalogueSubmissionRequest b) {
        permissions.require(ctx, PUBLISH);
        host();
        if (b == null || b.bundle() == null) {
            throw ApiProblem.badRequest("CATALOGUE_BUNDLE_REQUIRED", "bundle is required");
        }
        String id = CatalogueIndex.token(b.id(), "id");
        String version = CatalogueIndex.token(b.version(), "version");
        String licence = required(b.licence(), "licence");
        String minPlatform = required(b.minPlatform(), "minPlatform");
        PackagePort.PackageVerification v = packages.verify(ctx, b.bundle());
        List<String> refused = new ArrayList<>();
        if (!v.hashMatches()) {
            refused.add("the package hash does not recompute");
        }
        if (v.artifactMismatches() != null && !v.artifactMismatches().isEmpty()) {
            refused.add("artefacts do not match the manifest: " + v.artifactMismatches());
        }
        if (!v.signed() || v.signatures() == null || v.signatures().isEmpty()
                || v.signatures().stream().anyMatch(s -> !s.valid())) {
            refused.add("the bundle does not carry valid signatures");
        }
        if (!refused.isEmpty()) {
            throw ApiProblem.unprocessable("CATALOGUE_BUNDLE_UNVERIFIED", String.join("; ", refused));
        }
        byte[] artifact;
        try {
            artifact = BUNDLES.writeValueAsBytes(b.bundle());
        } catch (JsonProcessingException e) {
            throw ApiProblem.unprocessable("CATALOGUE_ARTIFACT_INVALID", "the bundle cannot be written");
        }
        String key = id + "@" + version;
        if (store.get(DocumentStore.GLOBAL, KIND, docId(key)).isPresent()) {
            throw ApiProblem.conflict("CATALOGUE_ENTRY_EXISTS", key + " is already in the catalogue; publish a new version");
        }
        Stored s = new Stored(id, version, ctx.tenantId() + "/" + ctx.principalId(), licence,
                Hashes.hex(Hashes.sha256(artifact)), minPlatform, Base64.getEncoder().encodeToString(artifact),
                ctx.principalId(), null, blankToNull(b.reason()), null);
        // the document id is derived from id@version, so a concurrent second submission hits the primary key (409)
        DocumentStore.Doc d = store.insert(DocumentStore.GLOBAL, KIND, docId(key), key, SUBMITTED, ctx.principalId(), ctx.tenantId(),
                s, ctx.principalId());
        return org.sangam.rest.web.Lists.created("/api/v1/catalogue/entries?state=" + SUBMITTED, key, view(d));
    }

    @GetMapping("/catalogue/entries")
    public ResponseEntity<List<CatalogueHostedEntry>> entries(RequestContext ctx, @RequestParam(required = false) String state) {
        if (!permissions.has(ctx, PUBLISH) && !permissions.has(ctx, REVIEW)) {
            permissions.require(ctx, REVIEW);
        }
        host();
        DocumentStore.Filter f = state == null || state.isBlank() ? DocumentStore.Filter.ALL : DocumentStore.Filter.state(state);
        return org.sangam.rest.web.Lists.ok(store.all(DocumentStore.GLOBAL, KIND, f).stream().map(CatalogueHostController::view).toList());
    }

    @PostMapping("/catalogue/entries/{id}/{version}/approve")
    public CatalogueHostedEntry approve(RequestContext ctx, @PathVariable String id, @PathVariable String version,
                                        @RequestBody(required = false) CatalogueReviewRequest b) {
        return review(ctx, id, version, APPROVED, b);
    }

    @PostMapping("/catalogue/entries/{id}/{version}/reject")
    public CatalogueHostedEntry reject(RequestContext ctx, @PathVariable String id, @PathVariable String version,
                                       @RequestBody(required = false) CatalogueReviewRequest b) {
        if (b == null || b.reason() == null || b.reason().isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "reason is required to reject");
        }
        return review(ctx, id, version, REJECTED, b);
    }

    private CatalogueHostedEntry review(RequestContext ctx, String id, String version, String to, CatalogueReviewRequest b) {
        permissions.require(ctx, REVIEW);
        host();
        String key = id + "@" + version;
        DocumentStore.Doc d = store.get(DocumentStore.GLOBAL, KIND, docId(key))
                .orElseThrow(() -> ApiProblem.notFound("catalogue entry", key));
        if (!SUBMITTED.equals(d.state())) {
            throw ApiProblem.conflict("CATALOGUE_ENTRY_NOT_SUBMITTED", key + " is " + d.state() + "; only a SUBMITTED entry is reviewed");
        }
        Stored s = d.as(Stored.class);
        if (Objects.equals(ctx.principalId(), s.submittedBy())) {
            throw ApiProblem.unprocessable("SEGREGATION_OF_DUTIES", "the reviewer of a catalogue entry must differ from its publisher");
        }
        Stored next = new Stored(s.id(), s.version(), s.publisher(), s.licence(), s.sha256(), s.minPlatform(), s.artifact(),
                s.submittedBy(), ctx.principalId(), s.reason(), b == null ? null : blankToNull(b.reason()));
        return view(store.update(DocumentStore.GLOBAL, KIND, d.id(), d.revision(), key, to, d.ownerId(), d.refId(), next));
    }

    /** The signed index of the approved entries (the hosted catalogue a consumer reads). */
    @GetMapping(value = "/catalogue/hosted/index.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> index() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(indexBytes());
    }

    @GetMapping(value = "/catalogue/hosted/index.json.sig", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> signature() {
        byte[] idx = indexBytes();
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(CatalogueIndex.sign(idx, signer));
    }

    /** The exact bundle bytes of an approved entry. */
    @GetMapping(value = "/catalogue/hosted/artifacts/{id}/{version}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> artifact(@PathVariable String id, @PathVariable String version) {
        host();
        String key = id + "@" + version;
        DocumentStore.Doc d = store.get(DocumentStore.GLOBAL, KIND, docId(key)).filter(x -> APPROVED.equals(x.state()))
                .orElseThrow(() -> ApiProblem.notFound("catalogue artefact", key));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Base64.getDecoder().decode(d.as(Stored.class).artifact()));
    }

    @ExceptionHandler(ExtensionCatalogue.CatalogueException.class)
    public ResponseEntity<Map<String, Object>> refused(ExtensionCatalogue.CatalogueException e, jakarta.servlet.http.HttpServletRequest req) {
        return org.sangam.rest.modules.config.ModuleProblems.respond(ExtensionCatalogueController.problem(e), req);
    }

    private byte[] indexBytes() {
        host();
        List<ExtensionCatalogue.Entry> approved = store.all(DocumentStore.GLOBAL, KIND, DocumentStore.Filter.state(APPROVED)).stream()
                .map(d -> d.as(Stored.class))
                .map(s -> new ExtensionCatalogue.Entry(s.id(), s.version(), ExtensionCatalogue.KIND_PACKAGE, s.publisher(), s.licence(),
                        s.sha256(), s.minPlatform(), CatalogueIndex.artifactPath(s.id(), s.version())))
                .toList();
        return CatalogueIndex.write(approved);
    }

    /** The document id of {@code id@version}: the hex SHA-256 of the key (64 characters, the column size). */
    static String docId(String key) {
        return Hashes.hex(Hashes.sha256(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void host() {
        if (signer == null) {
            throw ApiProblem.notFound("catalogue host", "not enabled");
        }
    }

    static CatalogueHostedEntry view(DocumentStore.Doc d) {
        Stored s = d.as(Stored.class);
        return new CatalogueHostedEntry(s.id(), s.version(), ExtensionCatalogue.KIND_PACKAGE, s.publisher(), s.licence(), s.sha256(),
                s.minPlatform(), CatalogueIndex.artifactPath(s.id(), s.version()), d.state(), s.submittedBy(), s.reviewedBy(),
                s.reason(), s.reviewReason(), d.createdAt(), d.updatedAt());
    }

    /** The host's signer, or null when hosting is off. */
    static AuditCheckpoints.Signer signer(Environment env, SecretResolver secrets) {
        if (!env.getProperty(P + "enabled", Boolean.class, false)) {
            return null;
        }
        String priv = env.getProperty(P + "signing-key-ref");
        String pub = env.getProperty(P + "public-key-ref");
        if (priv == null || pub == null) {
            throw new IllegalStateException(P + "enabled=true needs " + P + "signing-key-ref and " + P + "public-key-ref (secret:// references)");
        }
        try {
            return CatalogueIndex.signer(priv, pub, secrets);
        } catch (ExtensionCatalogue.CatalogueException e) {
            throw new IllegalStateException("the catalogue host key is refused: " + e.getMessage(), e);
        }
    }

    private static String required(String v, String name) {
        if (v == null || v.isBlank()) {
            throw ApiProblem.badRequest("FIELD_REQUIRED", name + " is required");
        }
        return v.strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
