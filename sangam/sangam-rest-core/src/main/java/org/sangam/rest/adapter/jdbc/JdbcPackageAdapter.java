// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.sangam.rest.adapter.jdbc.JdbcSupport.invalid;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.payload;
import static org.sangam.rest.adapter.jdbc.JdbcSupport.required;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.PackageSigning;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Executable packages (R43 / X.2.4, FLOWABLE-INTO-OPERATON FC-41 app bundles) persisted in the product table {@code PACKAGE_VERSION}:
 * one row per REST package, addressed by {@code package_id} (changeset 0016-05, indexed). Columns:
 * {@code package_key}; {@code version} = the per-key sequence number assigned at creation (the REST {@code revision}
 * counts the edits of that package); {@code package_hash} = the frozen content hash (NULL until submit);
 * {@code state}; {@code author}, {@code reviewer}, {@code deployer}; {@code engine_deployment_id};
 * {@code created_at} / {@code state_changed_at}; {@code manifest_json} = name, revision, approver, environment,
 * activateAt, resource hashes, the resources themselves (base64), the transition history and the author's seat.
 * The legacy {@code signature} column stays null: signatures live in {@code PACKAGE_SIGNATURE} (M-074, below).
 *
 * <p>M-074 (X.2.4.5, T70, owner Q-04): the content hash frozen at submit is {@code SHA-256(JCS(manifest))}
 * ({@link PackageManifests}). With a package signing key ({@link PackageSigning}), submit signs the hash as
 * {@code author} and approve as {@code approver}. Deploy recomputes the hash and verifies the approver signature
 * against the trusted keys; an unsigned package is refused ({@code 422 PACKAGE_UNSIGNED}) unless this is a development
 * environment that allows it. Activation needs an APPROVED {@code ENV_BINDING} in the package's environment for every
 * name in the manifest's {@code envBindings} ({@code 409 ENV_BINDING_MISSING}). Export, verify and import move the same
 * bytes between environments, with the source's signatures; the target verifies them the same way (air-gapped too).
 *
 * <p>Lifecycle (X.2.4.4): DRAFT → submit → IN_REVIEW → approve → APPROVED → deploy → DEPLOYED → activate → ACTIVE;
 * IN_REVIEW → reject → DRAFT (new revision); DRAFT → withdraw → WITHDRAWN; ACTIVE ⇄ SUSPENDED (suspend/resume);
 * ACTIVE → RETIRED when a newer version of the key is activated or on {@code retire}; RETIRED → archive → ARCHIVED.
 * The content hash is frozen at submit. Reviewer/approver and deployer must differ from the author by person and by
 * seat (422 SEGREGATION_OF_DUTIES); the relaxed {@code sod} mode is not offered here.
 * An {@code activateAt} in the future leaves the package DEPLOYED with {@code activateAt} recorded; the timed
 * activation itself is done by the engine-side scheduler (out of scope for this adapter): call {@code activate}
 * again at or after that time.
 */
public final class JdbcPackageAdapter implements PackagePort {

    private static final class Entry {
        Package pkg;
        Map<String, byte[]> resources;
        String authorSeat;
    }

    private final TxSupport tx;
    private final DeploymentPort deployments;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final RestProperties props;
    private final PackageSigning signing;
    private final JdbcEnvBindings bindings;

    /** Without package signing configuration: unsigned packages are refused at deploy ({@link PackageSigning#strict}). */
    public JdbcPackageAdapter(TxSupport tx, DeploymentPort deployments, PermissionPort permissions, AuditPort audit,
                              RestProperties props) {
        this(tx, deployments, permissions, audit, props, PackageSigning.strict());
    }

    /** @param signing this environment's package signing key, trusted keys and unsigned-package rule (M-074) */
    public JdbcPackageAdapter(TxSupport tx, DeploymentPort deployments, PermissionPort permissions, AuditPort audit,
                              RestProperties props, PackageSigning signing) {
        this.tx = tx;
        this.deployments = deployments;
        this.permissions = permissions;
        this.audit = audit;
        this.props = props;
        this.signing = signing == null ? PackageSigning.strict() : signing;
        this.bindings = new JdbcEnvBindings(tx, permissions, audit, this.signing, this::deployersUsing);
    }

    @Override
    public synchronized Package create(RequestContext ctx, CreatePackageRequest request) {
        return tx.inTx(() -> doCreate(ctx, request));
    }

    private Package doCreate(RequestContext ctx, CreatePackageRequest request) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        required(request.key(), "key");
        if (!request.key().matches("[A-Za-z0-9._-]{1,128}")) {
            throw invalid("key", "package key must be 1..128 of [A-Za-z0-9._-]");
        }
        Map<String, byte[]> res = decode(request.resources());
        Instant now = Instant.now();
        Entry e = new Entry();
        e.resources = res;
        e.authorSeat = ctx.seatId();
        e.pkg = new Package(JdbcSupport.newId(), request.key(), request.name() == null ? request.key() : request.name(),
                1, "DRAFT", null, hashes(res), ctx.principalId(), null, null, null, null, null, null, now, now,
                List.of(event("create", null, "DRAFT", ctx, null, now)), null);
        insert(JdbcSupport.tenant(ctx), e);
        audit.record(ctx, chain(e), "PACKAGE_DRAFT_SAVED",
                payload("packageId", e.pkg.id(), "key", e.pkg.key(), "revision", 1, "resources", e.pkg.resourceHashes(),
                        "actor", ctx.actor()));
        return view(e);
    }

    @Override
    public Page<Package> list(RequestContext ctx, String key, String state, Page.PageRequest page) {
        List<Package> all = new ArrayList<>(load(JdbcSupport.tenant(ctx), key).stream().map(JdbcPackageAdapter::view)
                .filter(p -> key == null || key.equals(p.key()))
                .filter(p -> state == null || state.equals(p.state()))
                .sorted(Comparator.comparing(Package::key).thenComparing(Package::createdAt))
                .toList());
        return Page.slice(all, page, props.getCountCap());
    }

    @Override
    public Package get(RequestContext ctx, String packageId) {
        return view(entry(ctx, packageId));
    }

    @Override
    public synchronized Package update(RequestContext ctx, String packageId, long expectedRevision,
                                       UpdatePackageRequest request) {
        return tx.inTx(() -> doUpdate(ctx, packageId, expectedRevision, request));
    }

    private Package doUpdate(RequestContext ctx, String packageId, long expectedRevision, UpdatePackageRequest request) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        Entry e = entry(ctx, packageId);
        Package p = e.pkg;
        if (!"DRAFT".equals(p.state())) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "Only a DRAFT package can be edited; it is " + p.state());
        }
        JdbcSupport.checkRevision(expectedRevision, p.revision(), "Package", packageId);
        if (request.resources() != null) {
            e.resources = decode(request.resources());
        }
        Instant now = Instant.now();
        e.pkg = copy(p, p.revision() + 1, "DRAFT", null, hashes(e.resources), request.name() == null ? p.name() : request.name(),
                p.reviewer(), p.approver(), p.deployer(), p.environment(), p.activateAt(), p.deploymentId(), now,
                append(p.history(), event("update", "DRAFT", "DRAFT", ctx, null, now)));
        save(JdbcSupport.tenant(ctx), e);
        audit.record(ctx, chain(e), "PACKAGE_DRAFT_SAVED",
                payload("packageId", packageId, "key", p.key(), "revision", e.pkg.revision(),
                        "resources", e.pkg.resourceHashes(), "actor", ctx.actor()));
        return view(e);
    }

    @Override
    public synchronized Package transition(RequestContext ctx, String packageId, TransitionRequest request) {
        return tx.inTx(() -> {
            Entry e = entry(ctx, packageId);
            Package result = doTransition(ctx, e, request);
            save(JdbcSupport.tenant(ctx), e);
            return result;
        });
    }

    private Package doTransition(RequestContext ctx, Entry e, TransitionRequest request) {
        String packageId = e.pkg.id();
        Package p = e.pkg;
        String action = required(request.action(), "action").toLowerCase(java.util.Locale.ROOT);
        Instant now = Instant.now();
        String from = p.state();
        switch (action) {
            case "submit" -> {
                permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
                expect(p, action, "DRAFT");
                if (e.resources.isEmpty()) {
                    throw ApiProblem.unprocessable("PACKAGE_EMPTY", "A package needs at least one resource");
                }
                String hash = packageHash(p.key(), p.name(), e.resources);
                e.pkg = copy(p, p.revision(), "IN_REVIEW", hash, hashes(e.resources), p.name(), null, null, null,
                        p.environment(), p.activateAt(), p.deploymentId(), now, append(p.history(),
                                event(action, from, "IN_REVIEW", ctx, request.reason(), now)));
                sign(ctx, hash, PackageManifests.ROLE_AUTHOR, now);
                audit(ctx, e, "PACKAGE_SUBMITTED", request, from);
            }
            case "approve" -> {
                requireAny(ctx, Permissions.PACKAGE_REVIEW, Permissions.PACKAGE_APPROVE);
                expect(p, action, "IN_REVIEW");
                separate(ctx, e, "approver");
                e.pkg = copy(p, p.revision(), "APPROVED", p.packageHash(), p.resourceHashes(), p.name(),
                        ctx.principalId(), ctx.principalId(), null, p.environment(), p.activateAt(), p.deploymentId(), now,
                        append(p.history(), event(action, from, "APPROVED", ctx, request.reason(), now)));
                sign(ctx, p.packageHash(), PackageManifests.ROLE_APPROVER, now);
                audit(ctx, e, "PACKAGE_APPROVED", request, from);
            }
            case "reject" -> {
                requireAny(ctx, Permissions.PACKAGE_REVIEW, Permissions.PACKAGE_APPROVE);
                expect(p, action, "IN_REVIEW");
                separate(ctx, e, "reviewer");
                requireReason(request);
                e.pkg = copy(p, p.revision() + 1, "DRAFT", null, p.resourceHashes(), p.name(), ctx.principalId(), null,
                        null, p.environment(), p.activateAt(), p.deploymentId(), now,
                        append(p.history(), event(action, from, "DRAFT", ctx, request.reason(), now)));
                audit(ctx, e, "PACKAGE_REJECTED", request, from);
            }
            case "deploy" -> {
                permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
                expect(p, action, "APPROVED");
                separate(ctx, e, "deployer");
                if (!p.packageHash().equals(packageHash(p.key(), p.name(), e.resources))) {
                    throw ApiProblem.conflict("PACKAGE_HASH_MISMATCH", "The package content no longer matches its approved hash");
                }
                String tenant = JdbcSupport.tenant(ctx);
                requireSigned(tx.withConnection(c -> JdbcPackageSignatures.load(c, tenant, p.packageHash())),
                        p.packageHash());
                // M-068: the package's own deploy (pkg:<key>:<hash>, sangam-package) carries the engine's proof
                DeploymentPort.Deployment d = deployments.deployPackage(ctx, p.key(), p.packageHash(),
                        ScriptTaskDeployRewrite.rewrite(e.resources));
                String env = request.environment() == null || request.environment().isBlank() ? defaultEnvironment()
                        : request.environment();
                e.pkg = copy(p, p.revision(), "DEPLOYED", p.packageHash(), p.resourceHashes(), p.name(), p.reviewer(),
                        p.approver(), ctx.principalId(), env, p.activateAt(), d.id(), now,
                        append(p.history(), event(action, from, "DEPLOYED", ctx, request.reason(), now)));
                audit(ctx, e, "PACKAGE_DEPLOYED", request, from);
            }
            case "activate" -> {
                permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
                expect(p, action, "DEPLOYED");
                separate(ctx, e, "deployer");
                requireBindings(ctx, e);
                if (request.activateAt() != null && request.activateAt().isAfter(now)) {
                    e.pkg = copy(p, p.revision(), "DEPLOYED", p.packageHash(), p.resourceHashes(), p.name(), p.reviewer(),
                            p.approver(), p.deployer(), p.environment(), request.activateAt(), p.deploymentId(), now,
                            append(p.history(), event("schedule-activation", from, "DEPLOYED", ctx, request.reason(), now)));
                    audit(ctx, e, "PACKAGE_ACTIVATION_SCHEDULED", request, from);
                    return view(e);
                }
                for (Entry other : load(JdbcSupport.tenant(ctx), p.key())) {
                    if (!other.pkg.id().equals(e.pkg.id()) && "ACTIVE".equals(other.pkg.state())) {
                        Package o = other.pkg;
                        other.pkg = copy(o, o.revision(), "RETIRED", o.packageHash(), o.resourceHashes(), o.name(),
                                o.reviewer(), o.approver(), o.deployer(), o.environment(), o.activateAt(), o.deploymentId(),
                                now, append(o.history(), event("retire", "ACTIVE", "RETIRED", ctx,
                                        "superseded by " + p.id(), now)));
                        save(JdbcSupport.tenant(ctx), other);
                        audit(ctx, other, "PACKAGE_RETIRED", request, "ACTIVE");
                    }
                }
                e.pkg = copy(p, p.revision(), "ACTIVE", p.packageHash(), p.resourceHashes(), p.name(), p.reviewer(),
                        p.approver(), p.deployer(), p.environment(), request.activateAt() == null ? now : request.activateAt(),
                        p.deploymentId(), now, append(p.history(), event(action, from, "ACTIVE", ctx, request.reason(), now)));
                audit(ctx, e, "PACKAGE_ACTIVATED", request, from);
            }
            case "suspend" -> {
                permissions.require(ctx, Permissions.PACKAGE_OPERATE);
                expect(p, action, "ACTIVE");
                requireReason(request);
                e.pkg = simple(p, "SUSPENDED", ctx, action, request, now);
                audit(ctx, e, "PACKAGE_SUSPENDED", request, from);
            }
            case "resume" -> {
                permissions.require(ctx, Permissions.PACKAGE_OPERATE);
                expect(p, action, "SUSPENDED");
                requireReason(request);
                e.pkg = simple(p, "ACTIVE", ctx, action, request, now);
                audit(ctx, e, "PACKAGE_RESUMED", request, from);
            }
            case "withdraw" -> {
                permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
                expect(p, action, "DRAFT");
                e.pkg = simple(p, "WITHDRAWN", ctx, action, request, now);
                audit(ctx, e, "PACKAGE_WITHDRAWN", request, from);
            }
            case "retire" -> {
                permissions.require(ctx, Permissions.PACKAGE_OPERATE);
                expect(p, action, "ACTIVE", "SUSPENDED");
                requireReason(request);
                e.pkg = simple(p, "RETIRED", ctx, action, request, now);
                audit(ctx, e, "PACKAGE_RETIRED", request, from);
            }
            case "archive" -> {
                permissions.require(ctx, Permissions.PACKAGE_OPERATE);
                expect(p, action, "RETIRED");
                e.pkg = simple(p, "ARCHIVED", ctx, action, request, now);
                audit(ctx, e, "PACKAGE_ARCHIVED", request, from);
            }
            default -> throw invalid("action", "unknown package transition " + request.action());
        }
        return view(e);
    }

    @Override
    public List<DiffEntry> diff(RequestContext ctx, String packageId) {
        Entry e = entry(ctx, packageId);
        Map<String, String> mine = e.pkg.resourceHashes();
        Map<String, String> active = load(JdbcSupport.tenant(ctx), e.pkg.key()).stream()
                .filter(o -> !o.pkg.id().equals(e.pkg.id()) && "ACTIVE".equals(o.pkg.state()))
                .map(o -> o.pkg.resourceHashes()).findFirst().orElse(Map.of());
        List<DiffEntry> out = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(mine.keySet());
        names.addAll(active.keySet());
        for (String n : names) {
            String a = active.get(n);
            String b = mine.get(n);
            if (a == null) {
                out.add(new DiffEntry(n, "ADDED", null, b));
            } else if (b == null) {
                out.add(new DiffEntry(n, "REMOVED", a, null));
            } else if (!a.equals(b)) {
                out.add(new DiffEntry(n, "CHANGED", a, b));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ M-074 signatures, promotion, ENV_BINDING

    private static final java.util.Set<String> EXPORTABLE = java.util.Set.of("APPROVED", "DEPLOYED", "ACTIVE",
            "SUSPENDED", "RETIRED", "ARCHIVED");

    @Override
    public List<PackageSignature> signatures(RequestContext ctx, String packageId) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        Package p = entry(ctx, packageId).pkg;
        return p.packageHash() == null ? List.of()
                : tx.withReadConnection(c -> JdbcPackageSignatures.load(c, JdbcSupport.tenant(ctx), p.packageHash()));
    }

    @Override
    public PackageBundle export(RequestContext ctx, String packageId) {
        permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
        Entry e = entry(ctx, packageId);
        Package p = e.pkg;
        if (!EXPORTABLE.contains(p.state())) {
            throw ApiProblem.conflict("INVALID_TRANSITION", "Only an APPROVED (or later) package can be exported; it is "
                    + p.state()).with("state", p.state());
        }
        if (signing.environment() == null) {
            throw ApiProblem.conflict("PACKAGE_ENVIRONMENT_NOT_SET", "This environment has no label "
                    + "(sangam.onboarding.environment); a bundle names the environment it comes from");
        }
        Map<String, Object> manifest = PackageManifests.manifest(p.key(), p.name(), e.resources);
        if (!p.packageHash().equals(PackageManifests.hash(manifest))) {
            throw ApiProblem.conflict("PACKAGE_HASH_MISMATCH", "The package content no longer matches its approved hash");
        }
        Map<String, String> artifacts = new TreeMap<>();
        e.resources.forEach((k, v) -> artifacts.put(k, PackageManifests.encode(k, v)));
        String tenant = JdbcSupport.tenant(ctx);
        List<PackageSignature> sigs = tx.withReadConnection(c -> JdbcPackageSignatures.load(c, tenant, p.packageHash()));
        Instant now = Instant.now();
        audit.record(ctx, chain(e), "PACKAGE_EXPORTED", payload("packageId", p.id(), "key", p.key(),
                "package_hash", p.packageHash(), "sourceEnvironment", signing.environment(), "signatures", sigs.size(),
                "actor", ctx.actor()));
        return new PackageBundle(PackageManifests.BUNDLE_FORMAT, p.packageHash(), manifest, Map.copyOf(artifacts),
                sigs, signing.environment(), now);
    }

    @Override
    public PackageVerification verify(RequestContext ctx, PackageBundle bundle) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return tx.withConnection(c -> verification(c, JdbcSupport.tenant(ctx), bundle).result());
    }

    /** The verification and the artifact bytes it decoded (for the import). */
    private record Verified(PackageVerification result, Map<String, byte[]> bytes, String key, String name) {
    }

    private Verified verification(java.sql.Connection c, String tenant, PackageBundle bundle) {
        if (bundle == null || !Integer.valueOf(PackageManifests.BUNDLE_FORMAT).equals(bundle.format())
                || bundle.manifest() == null || bundle.packageHash() == null || bundle.artifacts() == null) {
            throw ApiProblem.badRequest("PACKAGE_BUNDLE_INVALID", "not a package bundle of format "
                    + PackageManifests.BUNDLE_FORMAT + " (format, packageHash, manifest and artifacts are required)");
        }
        String key = PackageManifests.packageField(bundle.manifest(), "key");
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,128}")) {
            throw ApiProblem.badRequest("PACKAGE_BUNDLE_INVALID", "the manifest names no valid package key");
        }
        String name = PackageManifests.packageField(bundle.manifest(), "name");
        PackageManifests.ArtifactCheck art = PackageManifests.artifacts(bundle.manifest(), bundle.artifacts());
        Map<String, Object> rebuilt = PackageManifests.manifest(key, name, art.bytes());
        String recomputed = PackageManifests.hash(rebuilt);
        boolean hashMatches = recomputed.equalsIgnoreCase(bundle.packageHash()) && art.mismatches().isEmpty()
                && recomputed.equals(PackageManifests.hash(bundle.manifest()));
        List<PackageSignature> sigs = bundle.signatures() == null ? List.of() : bundle.signatures();
        List<SignatureCheck> checks = PackageManifests.check(bundle.packageHash(), sigs, signing.trusted(),
                List.of(PackageManifests.ROLE_APPROVER));
        String failure = signatureFailure(checks, sigs);
        boolean signaturesOk = failure == null
                || (signing.allowUnsigned() && !"PACKAGE_SIGNATURE_INVALID".equals(failure));
        List<String> required = PackageManifests.envBindings(rebuilt);
        java.util.Set<String> approved = JdbcEnvBindings.approvedNames(c, tenant, defaultEnvironment());
        List<String> missing = required.stream().filter(n -> !approved.contains(n)).toList();
        PackageVerification v = new PackageVerification(bundle.packageHash(), recomputed, hashMatches, !sigs.isEmpty(),
                checks, art.mismatches(), required, missing, hashMatches && signaturesOk);
        return new Verified(v, art.bytes(), key, name);
    }

    @Override
    public synchronized Package importBundle(RequestContext ctx, PackageImportRequest request) {
        permissions.require(ctx, Permissions.PACKAGE_DEPLOY);
        PackageBundle bundle = request == null ? null : request.bundle();
        String tenant = JdbcSupport.tenant(ctx);
        return tx.inTx(() -> {
            Verified v = tx.withConnection(c -> verification(c, tenant, bundle));
            if (!v.result().hashMatches()) {
                throw ApiProblem.unprocessable("PACKAGE_HASH_MISMATCH", "The bundle's bytes do not match its package hash")
                        .with("artifactMismatches", v.result().artifactMismatches());
            }
            List<PackageSignature> sigs = bundle.signatures() == null ? List.of() : bundle.signatures();
            requireSigned(sigs, bundle.packageHash());
            String hash = v.result().recomputedHash();
            if (tx.withConnection(c -> Jdbc.one(c, "SELECT package_id FROM PACKAGE_VERSION WHERE tenant_id=? AND"
                    + " package_hash=?", rs -> rs.getString(1), tenant, hashBytes(hash))).isPresent()) {
                throw ApiProblem.conflict("PACKAGE_EXISTS", "A package with this hash already exists in this tenant");
            }
            List<String> valid = v.result().signatures().stream().filter(SignatureCheck::valid)
                    .map(SignatureCheck::role).toList();
            PackageSignature author = role(sigs, PackageManifests.ROLE_AUTHOR, valid);
            PackageSignature approver = role(sigs, PackageManifests.ROLE_APPROVER, valid);
            Instant now = Instant.now();
            Entry e = new Entry();
            e.resources = new LinkedHashMap<>(v.bytes());
            // Without a verified author signature (development: unsigned bundle) the importer answers for the content:
            // they are recorded as its author, so separation of duties keeps them from deploying it.
            boolean signedAuthor = author != null && author.principalId() != null;
            e.authorSeat = signedAuthor ? author.seatId() : ctx.seatId();
            String authoredBy = signedAuthor ? author.principalId() : ctx.principalId();
            String approvedBy = approver == null ? null : approver.principalId();
            String note = "from " + bundle.sourceEnvironment() + (request.reason() == null ? "" : ": " + request.reason());
            e.pkg = new Package(JdbcSupport.newId(), v.key(), v.name() == null ? v.key() : v.name(), 1, "APPROVED", hash,
                    hashes(e.resources), authoredBy, approvedBy, approvedBy, null, null, null, null, now, now,
                    List.of(event("import", null, "APPROVED", ctx, note, now)), null);
            insert(tenant, e);
            tx.withConnection(c -> {
                for (PackageSignature s : sigs) {
                    if (s != null && valid.contains(s.role())) {
                        JdbcPackageSignatures.put(c, tenant, new PackageSignature(hash, s.role(), s.principalId(),
                                s.seatId(), s.alg(), s.keyId(), s.signature(), s.signedAt() == null ? now : s.signedAt()));
                    }
                }
                return null;
            });
            audit.record(ctx, chain(e), "PACKAGE_IMPORTED", payload("packageId", e.pkg.id(), "key", e.pkg.key(),
                    "package_hash", hash, "sourceEnvironment", bundle.sourceEnvironment(),
                    "envBindingsMissing", v.result().envBindingsMissing(), "reason", request.reason(),
                    "actor", ctx.actor()));
            return view(e);
        });
    }

    @Override
    public List<EnvBinding> envBindings(RequestContext ctx, String env, String name, String state) {
        return bindings.list(ctx, env, name, state);
    }

    @Override
    public synchronized EnvBinding setEnvBinding(RequestContext ctx, String name, EnvBindingRequest request) {
        return bindings.set(ctx, name, request);
    }

    @Override
    public synchronized EnvBinding approveEnvBinding(RequestContext ctx, String name, EnvBindingApproval approval) {
        return bindings.approve(ctx, name, approval);
    }

    private static PackageSignature role(List<PackageSignature> sigs, String role, List<String> validRoles) {
        return !validRoles.contains(role) ? null : sigs.stream().filter(s -> s != null && role.equals(s.role()))
                .findFirst().orElse(null);
    }

    /** Signs the frozen hash for {@code role} when this environment has a package signing key. */
    private void sign(RequestContext ctx, String hash, String role, Instant now) {
        if (signing.canSign()) {
            PackageSignature s = PackageManifests.sign(signing.signer(), hash, role, ctx.principalId(), ctx.seatId(), now);
            tx.withConnection(c -> {
                JdbcPackageSignatures.put(c, JdbcSupport.tenant(ctx), s);
                return null;
            });
        }
    }

    /**
     * Deploy and import (X.2.4.5): a valid approver signature by a trusted key. A bad signature is refused everywhere;
     * a missing or untrusted one only where unsigned packages are not allowed (outside development).
     */
    private void requireSigned(List<PackageSignature> sigs, String hash) {
        List<SignatureCheck> checks = PackageManifests.check(hash, sigs, signing.trusted(),
                List.of(PackageManifests.ROLE_APPROVER));
        if (checks.stream().anyMatch(c -> "BAD_SIGNATURE".equals(c.reason()))) {
            throw ApiProblem.unprocessable("PACKAGE_SIGNATURE_INVALID", "A package signature does not verify")
                    .with("signatures", checks);
        }
        String failure = signatureFailure(checks, sigs);
        if (failure != null && !signing.allowUnsigned()) {
            throw ApiProblem.unprocessable(failure, "PACKAGE_KEY_UNKNOWN".equals(failure)
                    ? "The package is signed by a key this environment does not trust"
                    : "The package has no valid approver signature").with("signatures", checks);
        }
    }

    /** Null when an approver signature verifies; otherwise the refusal code. */
    private static String signatureFailure(List<SignatureCheck> checks, List<PackageSignature> sigs) {
        if (checks.stream().anyMatch(c -> "BAD_SIGNATURE".equals(c.reason()))) {
            return "PACKAGE_SIGNATURE_INVALID";
        }
        if (checks.stream().noneMatch(c -> "ROLE_MISSING".equals(c.reason()))) {
            return null;
        }
        return checks.stream().anyMatch(c -> "UNKNOWN_KEY".equals(c.reason())) ? "PACKAGE_KEY_UNKNOWN"
                : "PACKAGE_UNSIGNED";
    }

    /** Activation gate (X.2.4.5): every binding the manifest names is APPROVED in the package's environment. */
    private void requireBindings(RequestContext ctx, Entry e) {
        List<String> required = PackageManifests.envBindings(PackageManifests.authored(e.resources));
        if (required.isEmpty()) {
            return;
        }
        String env = e.pkg.environment() == null ? defaultEnvironment() : e.pkg.environment();
        java.util.Set<String> approved = tx.withConnection(c -> JdbcEnvBindings.approvedNames(c, JdbcSupport.tenant(ctx), env));
        List<String> missing = required.stream().filter(n -> !approved.contains(n)).toList();
        if (!missing.isEmpty()) {
            throw ApiProblem.conflict("ENV_BINDING_MISSING", "Activation needs an APPROVED environment binding in "
                    + env + " for " + missing).with("env", env).with("missing", missing);
        }
    }

    /** The deploy environment when the request names none: this environment's label, else {@code default}. */
    private String defaultEnvironment() {
        return signing.environment() == null ? "default" : signing.environment();
    }

    /** Deployers of the tenant's packages deployed in {@code env} whose manifest names {@code binding}. */
    private java.util.Set<String> deployersUsing(String tenant, String env, String binding) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Entry o : load(tenant, null)) {
            Package p = o.pkg;
            if (p.deployer() != null && env.equals(p.environment())
                    && PackageManifests.envBindings(PackageManifests.authored(o.resources)).contains(binding)) {
                out.add(p.deployer());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Entry entry(RequestContext ctx, String id) {
        Entry e = id == null ? null : find(JdbcSupport.tenant(ctx), id);
        if (e == null) {
            throw ApiProblem.notFound("Package", id);
        }
        return e;
    }

    private static String chain(Entry e) {
        return "pkg:" + e.pkg.key();
    }

    private void audit(RequestContext ctx, Entry e, String type, TransitionRequest r, String from) {
        Package p = e.pkg;
        audit.record(ctx, chain(e), type, payload("packageId", p.id(), "key", p.key(), "revision", p.revision(),
                "package_hash", p.packageHash(), "fromState", from, "toState", p.state(), "environment", p.environment(),
                "deploymentId", p.deploymentId(), "activateAt", p.activateAt() == null ? null : p.activateAt().toString(),
                "reason", r.reason(), "actor", ctx.actor()));
    }

    private void requireAny(RequestContext ctx, String... perms) {
        for (String p : perms) {
            if (permissions.has(ctx, p)) {
                return;
            }
        }
        throw ApiProblem.notPermitted(String.join(" or ", perms));
    }

    private static void expect(Package p, String action, String... states) {
        for (String s : states) {
            if (s.equals(p.state())) {
                return;
            }
        }
        throw ApiProblem.conflict("INVALID_TRANSITION",
                "Transition '" + action + "' is not allowed from state " + p.state()).with("state", p.state());
    }

    /** X.2.4.4: reviewer, approver and deployer differ from the author by person and by seat. */
    private static void separate(RequestContext ctx, Entry e, String role) {
        boolean samePerson = Objects.equals(ctx.principalId(), e.pkg.author());
        boolean sameSeat = ctx.seatId() != null && Objects.equals(ctx.seatId(), e.authorSeat);
        if (samePerson || sameSeat) {
            throw ApiProblem.unprocessable("SEGREGATION_OF_DUTIES",
                    "The " + role + " must differ from the author by person and by seat").with("role", role);
        }
    }

    private static void requireReason(TransitionRequest r) {
        if (r.reason() == null || r.reason().isBlank()) {
            throw invalid("reason", "a reason is required for this transition");
        }
    }

    private static Package simple(Package p, String to, RequestContext ctx, String action, TransitionRequest r, Instant now) {
        return copy(p, p.revision(), to, p.packageHash(), p.resourceHashes(), p.name(), p.reviewer(), p.approver(),
                p.deployer(), p.environment(), p.activateAt(), p.deploymentId(), now,
                append(p.history(), event(action, p.state(), to, ctx, r.reason(), now)));
    }

    private static Package copy(Package p, long revision, String state, String hash, Map<String, String> resourceHashes,
                                String name, String reviewer, String approver, String deployer, String environment,
                                Instant activateAt, String deploymentId, Instant updatedAt, List<PackageEvent> history) {
        return new Package(p.id(), p.key(), name, revision, state, hash, resourceHashes, p.author(), reviewer, approver,
                deployer, environment, activateAt, deploymentId, p.createdAt(), updatedAt, history, null);
    }

    private static PackageEvent event(String transition, String from, String to, RequestContext ctx, String reason, Instant at) {
        return new PackageEvent(transition, from, to, ctx.principalId(), ctx.seatId(), reason, at);
    }

    private static List<PackageEvent> append(List<PackageEvent> history, PackageEvent e) {
        List<PackageEvent> l = new ArrayList<>(history == null ? List.of() : history);
        l.add(e);
        return List.copyOf(l);
    }

    /** Text resources (by extension) travel as UTF-8 text; everything else as base64 (PackagePort contract). */
    static boolean isText(String name) {
        return PackageManifests.isText(name);
    }

    /** The stored package with its resource content attached ({@link Package#resources()}). */
    private static Package view(Entry e) {
        Package p = e.pkg;
        Map<String, String> content = new TreeMap<>();
        e.resources.forEach((k, v) -> content.put(k, isText(k) ? new String(v, StandardCharsets.UTF_8)
                : Base64.getEncoder().encodeToString(v)));
        return new Package(p.id(), p.key(), p.name(), p.revision(), p.state(), p.packageHash(), p.resourceHashes(),
                p.author(), p.reviewer(), p.approver(), p.deployer(), p.environment(), p.activateAt(), p.deploymentId(),
                p.createdAt(), p.updatedAt(), p.history(), Map.copyOf(content));
    }

    private static Map<String, byte[]> decode(Map<String, String> encoded) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (encoded == null) {
            return out;
        }
        for (Map.Entry<String, String> en : encoded.entrySet()) {
            if (en.getKey() == null || en.getKey().isBlank() || en.getKey().contains("..")) {
                throw invalid("resources", "invalid resource name " + en.getKey());
            }
            String v = en.getValue() == null ? "" : en.getValue();
            String trimmed = v.stripLeading();
            if (isText(en.getKey()) || trimmed.startsWith("<") || trimmed.startsWith("{")) {
                out.put(en.getKey(), v.getBytes(StandardCharsets.UTF_8));
                continue;
            }
            try {
                out.put(en.getKey(), Base64.getDecoder().decode(v));
            } catch (IllegalArgumentException ex) {
                throw invalid("resources", "resource " + en.getKey() + " is neither text nor valid base64");
            }
        }
        return out;
    }

    private static Map<String, String> hashes(Map<String, byte[]> res) {
        Map<String, String> m = new TreeMap<>();
        res.forEach((k, v) -> m.put(k, Hashes.hex(Hashes.sha256(v))));
        return Map.copyOf(m);
    }

    // ------------------------------------------------------------------ PACKAGE_VERSION access

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String COLS = "package_id, package_key, version, package_hash, state, manifest_json, author,"
            + " reviewer, deployer, engine_deployment_id, created_at, state_changed_at";

    /** The part of a package not held in its own columns. */
    record Manifest(String name, long revision, String approver, String environment, Instant activateAt,
                    Map<String, String> resourceHashes, Map<String, String> resources, List<PackageEvent> history,
                    String authorSeat, String packageHash) {
    }

    private Entry find(String tenant, String id) {
        return tx.withReadConnection(c -> Jdbc.one(c, "SELECT " + COLS + " FROM PACKAGE_VERSION WHERE tenant_id=?"
                + " AND package_id=?", JdbcPackageAdapter::row, tenant, id)).orElse(null);
    }

    /** Every package of the tenant, or of one key. */
    private List<Entry> load(String tenant, String key) {
        return tx.withReadConnection(c -> Jdbc.query(c, "SELECT " + COLS + " FROM PACKAGE_VERSION WHERE tenant_id=?"
                        + " AND package_id IS NOT NULL" + (key == null ? "" : " AND package_key=?")
                        + " ORDER BY package_key, version", JdbcPackageAdapter::row,
                key == null ? new Object[]{tenant} : new Object[]{tenant, key}));
    }

    private void insert(String tenant, Entry e) {
        Package p = e.pkg;
        tx.withConnection(c -> {
            int version = Jdbc.one(c, "SELECT MAX(version) FROM PACKAGE_VERSION WHERE tenant_id=? AND package_key=?",
                    rs -> rs.getInt(1), tenant, p.key()).orElse(0) + 1;
            Instant now = Jdbc.dbNow(c, tx.dialect());
            Jdbc.update(c, "INSERT INTO PACKAGE_VERSION(tenant_id, package_key, version, package_hash, state, manifest_json,"
                            + " author, reviewer, deployer, engine_deployment_id, created_at, state_changed_at, package_id)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenant, p.key(), version, hashBytes(p.packageHash()), p.state(), manifest(e), p.author(),
                    p.reviewer(), p.deployer(), p.deploymentId(), now, now, p.id());
            return null;
        });
    }

    private void save(String tenant, Entry e) {
        Package p = e.pkg;
        int n = tx.withConnection(c -> Jdbc.update(c, "UPDATE PACKAGE_VERSION SET package_hash=?, state=?, manifest_json=?,"
                        + " reviewer=?, deployer=?, engine_deployment_id=?, state_changed_at=" + tx.dialect().now()
                        + " WHERE tenant_id=? AND package_id=?",
                hashBytes(p.packageHash()), p.state(), manifest(e), p.reviewer(), p.deployer(), p.deploymentId(),
                tenant, p.id()));
        if (n == 0) {
            throw ApiProblem.notFound("Package", p.id());
        }
    }

    private static String manifest(Entry e) {
        Package p = e.pkg;
        Map<String, String> res = new TreeMap<>();
        e.resources.forEach((k, v) -> res.put(k, Base64.getEncoder().encodeToString(v)));
        Manifest m = new Manifest(p.name(), p.revision(), p.approver(), p.environment(), p.activateAt(),
                p.resourceHashes(), res, p.history(), e.authorSeat, p.packageHash());
        try {
            return JSON.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("package manifest is not serialisable", ex);
        }
    }

    private static Entry row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Manifest m;
        try {
            m = JSON.readValue(rs.getString("manifest_json"), Manifest.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("PACKAGE_VERSION.manifest_json of " + rs.getString("package_id")
                    + " is not valid JSON", ex);
        }
        Entry e = new Entry();
        e.authorSeat = m.authorSeat();
        e.resources = new LinkedHashMap<>();
        if (m.resources() != null) {
            m.resources().forEach((k, v) -> e.resources.put(k, Base64.getDecoder().decode(v)));
        }
        e.pkg = new Package(rs.getString("package_id"), rs.getString("package_key"), m.name(), m.revision(),
                rs.getString("state"), m.packageHash(), m.resourceHashes() == null ? Map.of() : m.resourceHashes(),
                rs.getString("author"), rs.getString("reviewer"), m.approver(), rs.getString("deployer"),
                m.environment(), m.activateAt(), rs.getString("engine_deployment_id"), Jdbc.instant(rs, "created_at"),
                Jdbc.instant(rs, "state_changed_at"), m.history() == null ? List.of() : m.history(), null);
        return e;
    }

    /**
     * The frozen hash as the 32 bytes of the unique PACKAGE_VERSION_HASH; NULL until submit freezes it, so any number
     * of drafts share a tenant (decision 83, changeset 0043-10: the index covers frozen hashes only).
     */
    static byte[] hashBytes(String hex) {
        if (hex == null) {
            return null;
        }
        byte[] b = PackageManifests.hashBytes(hex);
        if (b == null) {
            throw new IllegalStateException("package hash is not 64 hex characters: " + hex);
        }
        return b;
    }

    /** {@code SHA-256(JCS(manifest))} of the package (X.2.4.5 hash addressing, {@link PackageManifests}). */
    static String packageHash(String key, String name, Map<String, byte[]> res) {
        return PackageManifests.hash(PackageManifests.manifest(key, name, res));
    }
}
