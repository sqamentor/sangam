// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Executable packages (R43 / X.2.4; FLOWABLE-INTO-OPERATON FC-41 app bundles): a hash-pinned bundle of BPMN, DMN, CMMN, forms, event and
 * channel models, worker contracts and connector configuration, with the lifecycle
 * DRAFT → IN_REVIEW → APPROVED → DEPLOYED → ACTIVE → RETIRED → ARCHIVED (plus REJECTED→DRAFT, WITHDRAWN, SUSPENDED).
 * Reviewer, approver and deployer must differ from the author, by person and seat.
 */
public interface PackagePort {

    record PackageEvent(String transition, String fromState, String toState, String actor, String seatId,
                        String reason, Instant at) {
    }

    record Package(String id, String key, String name, long revision, String state, String packageHash,
                   Map<String, String> resourceHashes, String author, String reviewer, String approver,
                   String deployer, String environment, Instant activateAt, String deploymentId, Instant createdAt,
                   Instant updatedAt, List<PackageEvent> history, Map<String, String> resources) {
    }

    /**
     * {@code resources}: resource name → content. Text resources (.bpmn, .dmn, .cmmn, .form, .json, .xml, .yaml) are
     * sent and returned as UTF-8 text; any other resource as base64. In {@link Package#resources()} the same rule
     * applies.
     */
    record CreatePackageRequest(String key, String name, Map<String, String> resources) {
    }

    record UpdatePackageRequest(String name, Map<String, String> resources) {
    }

    /** action: submit | approve | reject | deploy | activate | suspend | resume | withdraw | retire. */
    record TransitionRequest(String action, String reason, String environment, Instant activateAt) {
    }

    record DiffEntry(String resource, String change, String oldHash, String newHash) {
    }

    Package create(RequestContext ctx, CreatePackageRequest request);

    Page<Package> list(RequestContext ctx, String key, String state, Page.PageRequest page);

    Package get(RequestContext ctx, String packageId);

    /** DRAFT only; {@code expectedRevision} from {@code If-Match} ({@code 409 REVISION_CONFLICT} when stale). */
    Package update(RequestContext ctx, String packageId, long expectedRevision, UpdatePackageRequest request);

    Package transition(RequestContext ctx, String packageId, TransitionRequest request);

    /** Semantic diff against the current ACTIVE version of the same key (viewed before approval). */
    List<DiffEntry> diff(RequestContext ctx, String packageId);

    // ------------------------------------------------------------------ M-074: signatures, promotion, ENV_BINDING

    /**
     * A signature over the package hash by one lifecycle role (PACKAGE_SIGNATURE, X.2.4.5). {@code signature} is the
     * base64 Ed25519 signature over the raw 32-byte package hash; {@code keyId} names the package signing key of the
     * environment that signed (Q-04), never the key itself.
     */
    record PackageSignature(String packageHash, String role, String principalId, String seatId, String alg,
                            String keyId, String signature, Instant signedAt) {
    }

    /**
     * A package exported for another environment or an air-gapped install (T50). {@code manifest} is the X.2.4.5
     * manifest the package hash is taken over ({@code package_hash = SHA-256(JCS(manifest))}); {@code artifacts} maps
     * each path to its content, as {@link Package#resources()}.
     */
    record PackageBundle(Integer format, String packageHash, Map<String, Object> manifest, Map<String, String> artifacts,
                         List<PackageSignature> signatures, String sourceEnvironment, Instant exportedAt) {
    }

    /** reason: OK | BAD_SIGNATURE | UNKNOWN_KEY | KEY_REVOKED | ROLE_MISSING. */
    record SignatureCheck(String role, String keyId, boolean valid, String reason) {
    }

    /** Verification of a bundle against this environment, without importing it (T70). */
    record PackageVerification(String packageHash, String recomputedHash, boolean hashMatches, boolean signed,
                               List<SignatureCheck> signatures, List<String> artifactMismatches,
                               List<String> envBindingsRequired, List<String> envBindingsMissing, boolean ok) {
    }

    record PackageImportRequest(PackageBundle bundle, String reason) {
    }

    /** An environment value a package references by name: an endpoint or a secret reference, never a secret value. */
    record EnvBinding(String tenantId, String env, String name, int version, String valueRef, String state,
                      String createdBy, Instant createdAt, String approvedBy, Instant approvedAt, String reason) {
    }

    record EnvBindingRequest(String env, String valueRef, String reason) {
    }

    record EnvBindingApproval(String env, Integer version, String reason) {
    }

    /** The signatures over the package's frozen hash, one per role that signed. */
    List<PackageSignature> signatures(RequestContext ctx, String packageId);

    /** The signed bundle of an APPROVED (or later) package, labelled with this environment. */
    PackageBundle export(RequestContext ctx, String packageId);

    /** Recomputes every hash and checks the signatures and ENV_BINDINGs here; changes nothing. */
    PackageVerification verify(RequestContext ctx, PackageBundle bundle);

    /** Imports the same bytes as an APPROVED package of this tenant after {@link #verify}. */
    Package importBundle(RequestContext ctx, PackageImportRequest request);

    /** Latest version per name and environment; each filter may be null. */
    List<EnvBinding> envBindings(RequestContext ctx, String env, String name, String state);

    /** A new PENDING version of the binding; the APPROVED one stays in force until this one is approved. */
    EnvBinding setEnvBinding(RequestContext ctx, String name, EnvBindingRequest request);

    /** Four-eyes approval of a PENDING version; the previous APPROVED version is RETIRED. */
    EnvBinding approveEnvBinding(RequestContext ctx, String name, EnvBindingApproval approval);
}
