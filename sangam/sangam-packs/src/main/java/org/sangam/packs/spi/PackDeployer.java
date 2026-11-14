// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.repository.DeploymentWithDefinitions;
import org.operaton.bpm.engine.repository.DeploymentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.InMemorySeatDirectory;
import org.sangam.packs.spi.PackDescriptor.PackResource;

/**
 * Deploys packs to an Operaton engine through the public repository API (ADR-D-16), dependencies first.
 *
 * <ul>
 *   <li>one engine deployment per pack and tenant, named {@code pack:<id>@<version>}, with duplicate filtering, so a
 *       restart redeploys nothing and a new pack version creates new definition versions while running cases keep theirs
 *       (GP-19, GP-20, R43); a pack version is immutable, so changed content under an already deployed
 *       {@code id@version} is refused with {@code PACK_VERSION_IMMUTABLE} (SL-54);</li>
 *   <li>{@code tenantId} null deploys the shared definitions; a tenant id deploys that department's overrides
 *       (GP-37), which {@link org.sangam.packs.core.Decisions} prefers at evaluation time;</li>
 *   <li>the manifest's tenant overrides for {@code tenantId} (DMN tables and pack configuration such as the works
 *       order of sanctions) are added to that tenant's deployment;</li>
 *   <li>forms and seed files go into the same deployment as resources, so the form version is bound with the model
 *       version (R47);</li>
 *   <li>every BPMN file passes {@link ModelGuard} first; a violation rejects the whole pack with a clear message;</li>
 *   <li>with the in-memory seat directory (developer profile), the pack seeds are loaded into it.</li>
 * </ul>
 */
public final class PackDeployer {

    private static final Logger LOG = LoggerFactory.getLogger(PackDeployer.class);

    private final ProcessEngine engine;
    private final PackRegistry registry;
    private final PackOperationLock lock;

    /** A deployer without serialisation ({@link PackOperationLock#NONE}): bare engines in tests and tools. */
    public PackDeployer(ProcessEngine engine, PackRegistry registry) {
        this(engine, registry, PackOperationLock.NONE);
    }

    /** A deployer whose deploy and rollback of one pack in one tenant scope run inside {@code lock} (SL-116). */
    public PackDeployer(ProcessEngine engine, PackRegistry registry, PackOperationLock lock) {
        this.engine = engine;
        this.registry = registry;
        this.lock = java.util.Objects.requireNonNull(lock, "lock");
    }

    /** The lock deploy and rollback run in (SL-116). */
    public PackOperationLock operationLock() {
        return lock;
    }

    /** Result of deploying one pack. */
    public record Result(String packId, String version, String tenantId, String deploymentId, int resources,
                         boolean newDeployment) {
    }

    /** Thrown when a pack fails the deploy-time checks. */
    public static final class PackRejectedException extends RuntimeException {
        private final List<String> violations;

        public PackRejectedException(String packId, List<String> violations) {
            super("pack " + packId + " rejected: " + String.join("; ", violations));
            this.violations = List.copyOf(violations);
        }

        public List<String> violations() {
            return violations;
        }
    }

    /**
     * Deploys the given packs and their dependencies. For a tenant this is the method to use: Operaton resolves a call
     * activity of a tenant's process only within that tenant (no fallback to shared definitions), so the core blocks
     * and every called pack must be deployed for the same tenant.
     *
     * <p>Each pack is deployed in its own {@link PackOperationLock} section. Call this inside one caller transaction
     * only if no concurrent rollback (or deploy or uninstall) of a pack of the list is possible: Operaton takes its
     * engine-wide deployment lock (the {@code deployment.lock} property row) at the first pack's deploy and holds it to
     * the end of the outer transaction, while the next pack's section waits for that pack's lock; a concurrent rollback
     * holding that pack's lock and waiting for the deployment lock is then a deadlock (SL-116). Without an outer
     * transaction each pack commits, and both locks are released, before the next one.
     */
    public List<Result> deploy(Collection<String> packIds, String tenantId) {
        List<Result> out = new ArrayList<>();
        for (SangamPack p : registry.deploymentOrder(packIds)) {
            out.add(deploy(p, tenantId));
        }
        return out;
    }

    /** Deploys every pack on the class path. */
    public List<Result> deployAll(String tenantId) {
        List<Result> out = new ArrayList<>();
        for (SangamPack p : registry.deploymentOrder()) {
            out.add(deploy(p, tenantId));
        }
        return out;
    }

    /**
     * Deploys one pack (without its dependencies) for the tenant scope, inside the pack's {@link PackOperationLock}
     * (SL-116): the immutability check reads the scope's deployments and the deploy follows under the same lock.
     */
    public Result deploy(SangamPack pack, String tenantId) {
        return lock.exclusive(tenantId, pack.descriptor().id(), () -> deployLocked(pack, tenantId));
    }

    private Result deployLocked(SangamPack pack, String tenantId) {
        PackDescriptor d = pack.descriptor();
        List<String> violations = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        for (PackResource r : d.resources()) {
            byte[] bytes = read(pack, r.path());
            contents.add(bytes);
            if (r.kind() == PackResource.Kind.BPMN) {
                violations.addAll(ModelGuard.check(r.path(), bytes));
            }
        }
        if (!violations.isEmpty()) {
            throw new PackRejectedException(d.id(), violations);
        }
        DeploymentBuilder b = engine.getRepositoryService().createDeployment()
                .name("pack:" + d.coordinates()).source("sangam-packs").enableDuplicateFiltering(true);
        if (tenantId != null) {
            b = b.tenantId(tenantId);
        }
        java.util.Set<String> replaced = new java.util.HashSet<>();
        for (String override : d.overridesFor(tenantId)) {
            replaced.add(override.substring(override.lastIndexOf('/') + 1));
        }
        Map<String, byte[]> planned = new java.util.LinkedHashMap<>(); // exactly what this deployment carries (SL-54)
        for (int i = 0; i < d.resources().size(); i++) {
            String path = d.resources().get(i).path();
            if (d.resources().get(i).kind() == PackResource.Kind.DMN
                    && replaced.contains(path.substring(path.lastIndexOf('/') + 1))) {
                continue; // the tenant's own table replaces the shared one of the same key in this deployment
            }
            planned.put(path, contents.get(i));
        }
        for (String override : d.overridesFor(tenantId)) {
            // department-specific DMN deployed for this tenant only; Decisions prefers it (GP-37)
            planned.put(override, read(pack, override));
        }
        String depId;
        boolean fresh;
        String existing = requireImmutableVersion(d.id(), d.version(), tenantId, planned);
        if (existing != null) {
            // this version is already deployed with the same canonical content: reuse its definitions. Deploying the
            // bytes again would let a line-ending-only difference create a second definition per versionTag (SL-54)
            depId = existing;
            fresh = false;
        } else {
            for (Map.Entry<String, byte[]> e : planned.entrySet()) {
                b = b.addInputStream(e.getKey(), new ByteArrayInputStream(e.getValue()));
            }
            DeploymentWithDefinitions dep = b.deployWithResult();
            depId = dep.getId();
            fresh = notEmpty(dep.getDeployedProcessDefinitions()) || notEmpty(dep.getDeployedDecisionDefinitions())
                    || notEmpty(dep.getDeployedCaseDefinitions());
        }
        if (PackRuntime.seats() instanceof InMemorySeatDirectory mem) {
            for (PackResource r : d.resources(PackResource.Kind.SEED)) {
                try (InputStream in = pack.open(r.path())) {
                    mem.load(tenantId, in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        LOG.info("deployed pack {} (tenant {}): deployment {}, {} resources", d.coordinates(), tenantId, depId,
                d.resources().size());
        return new Result(d.id(), d.version(), tenantId, depId, d.resources().size(), fresh);
    }

    // ------------------------------------------------------------------ pack-version immutability (SL-54)

    /** Violation code of a pack version deployed again with different content. */
    public static final String PACK_VERSION_IMMUTABLE = "PACK_VERSION_IMMUTABLE";

    /**
     * SL-54: a pack version is immutable within a tenant scope. Cross-pack calls bind by {@code versionTag} (the callee
     * pack's version, written by packgen {@code pin_cross_pack_calls}), and Operaton refuses such a call when one
     * (key, versionTag, tenant) matches more than one definition. Deploying changed content under an existing
     * {@code pack:<id>@<version>} would make duplicate filtering create exactly that second definition. So when the scope
     * already holds that deployment, the resources about to be deployed must equal those of its latest deployment in
     * {@linkplain #canonical canonical form} (same names, same canonical bytes, so line endings never change a pack's
     * identity); the caller then reuses that deployment instead of deploying. Anything else is refused with
     * {@link #PACK_VERSION_IMMUTABLE}, the changed resources and both content checksums, so the change has to ship as a
     * new pack version.
     *
     * @return the id of the deployment holding this version with the same canonical content, or null when the scope
     *         does not hold this version yet
     */
    private String requireImmutableVersion(String packId, String version, String tenantId, Map<String, byte[]> planned) {
        String coordinates = packId + "@" + version;
        var repo = engine.getRepositoryService();
        var q = repo.createDeploymentQuery().deploymentName(NAME_PREFIX + coordinates).deploymentSource(SOURCE);
        q = tenantId == null ? q.withoutTenantId() : q.tenantIdIn(tenantId);
        List<org.operaton.bpm.engine.repository.Deployment> same = q.orderByDeploymentTime().desc().list();
        if (same.isEmpty()) {
            return null;
        }
        String existingId = same.get(0).getId();
        Map<String, byte[]> existing = new java.util.TreeMap<>();
        for (String n : repo.getDeploymentResourceNames(existingId)) {
            try (InputStream in = repo.getResourceAsStream(existingId, n)) {
                existing.put(n, in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        java.util.Set<String> names = new java.util.TreeSet<>(existing.keySet());
        names.addAll(planned.keySet());
        List<String> changed = new ArrayList<>();
        for (String n : names) {
            byte[] was = existing.get(n);
            byte[] now = planned.get(n);
            if (was == null) {
                changed.add(n + " (added)");
            } else if (now == null) {
                changed.add(n + " (removed)");
            } else if (!java.util.Arrays.equals(canonical(n, was), canonical(n, now))) {
                changed.add(n + " (changed)");
            }
        }
        if (!changed.isEmpty()) {
            throw new PackRejectedException(packId, List.of(PACK_VERSION_IMMUTABLE + ": pack " + coordinates
                    + " is already deployed in " + scope(tenantId) + " (deployment " + existingId + ", content sha256 "
                    + contentSha256(existing) + ") and the content to deploy differs (sha256 " + contentSha256(planned)
                    + "): " + String.join(", ", changed) + "; a changed pack must carry a new version"));
        }
        return existingId;
    }

    /** File extensions of text resources, compared and checksummed with normalised line endings (SL-54). */
    private static final List<String> TEXT_EXTENSIONS =
            List.of(".bpmn", ".dmn", ".cmmn", ".json", ".form", ".xml", ".md", ".txt");

    /** True when {@code name} is a text resource by its extension (case-insensitive); everything else is binary. */
    public static boolean isTextResource(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return TEXT_EXTENSIONS.stream().anyMatch(n::endsWith);
    }

    /**
     * The canonical bytes of a resource, over which pack identity is defined: a text resource has every CRLF and every
     * lone CR replaced by LF; a binary resource is byte-exact.
     */
    public static byte[] canonical(String name, byte[] bytes) {
        if (!isTextResource(name)) {
            return bytes;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r') {
                out.write('\n');
                if (i + 1 < bytes.length && bytes[i + 1] == '\n') {
                    i++;
                }
            } else {
                out.write(bytes[i]);
            }
        }
        return out.toByteArray();
    }

    /** SHA-256 over the canonical resources in name order: name, NUL, length, canonical bytes for each. */
    public static String contentSha256(Map<String, byte[]> resources) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> e : new java.util.TreeMap<>(resources).entrySet()) {
                byte[] c = canonical(e.getKey(), e.getValue());
                md.update(e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(c.length).array());
                md.update(c);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ------------------------------------------------------------------ lifecycle: installed, uninstall, rollback (SL-41)

    /** Deployment name prefix of every pack deployment. */
    static final String NAME_PREFIX = "pack:";
    /** Deployment source of every pack deployment; a tenant table deployed with another source is its own (decision 202). */
    public static final String SOURCE = "sangam-packs";

    /** One engine deployment of a pack version for a tenant scope (null = shared definitions). */
    public record Installed(String packId, String version, String tenantId, String deploymentId,
                            java.util.Date deployedAt) {
    }

    /** Result of an uninstall: the deployments removed (definitions retired; history kept). */
    public record Uninstalled(String packId, String tenantId, List<String> deploymentIds) {
    }

    /** A refused lifecycle operation with a stable code and the blocking items. */
    public static final class PackLifecycleException extends RuntimeException {
        private final String code;
        private final List<String> blockers;

        public PackLifecycleException(String code, String message, List<String> blockers) {
            super(code + ": " + message + (blockers.isEmpty() ? "" : " " + blockers));
            this.code = code;
            this.blockers = List.copyOf(blockers);
        }

        public String code() {
            return code;
        }

        public List<String> blockers() {
            return blockers;
        }
    }

    /** Pack deployments in this tenant scope, oldest first. */
    public List<Installed> installed(String tenantId) {
        var q = engine.getRepositoryService().createDeploymentQuery().deploymentSource(SOURCE);
        q = tenantId == null ? q.withoutTenantId() : q.tenantIdIn(tenantId);
        List<Installed> out = new ArrayList<>();
        for (var d : q.orderByDeploymentTime().asc().list()) {
            String name = d.getName();
            int at = name == null ? -1 : name.lastIndexOf('@');
            if (name == null || !name.startsWith(NAME_PREFIX) || at <= NAME_PREFIX.length()) {
                continue;
            }
            out.add(new Installed(name.substring(NAME_PREFIX.length(), at), name.substring(at + 1), d.getTenantId(),
                    d.getId(), d.getDeploymentTime()));
        }
        return out;
    }

    /**
     * Uninstalls every version of a pack from one tenant scope. Refused with {@code PACK_HAS_DEPENDANTS} while another
     * installed pack of the scope depends on it, and with {@code PACK_HAS_LIVE_INSTANCES} while any process or case
     * instance of any of its versions is running. Otherwise the definitions are retired: every process definition is
     * suspended first (no new instance can start while the uninstall runs), then each deployment is removed without
     * cascade, so the engine itself refuses if an instance appeared meanwhile and history stays for audit.
     * The whole uninstall runs inside the pack's {@link PackOperationLock} (SL-116), so it is serialised with a deploy
     * or rollback of the same pack in the same scope; inside a transactional lock (the server's) a refused removal rolls
     * the whole uninstall back, suspensions included. The dependants check reads other packs, which the per-pack lock
     * does not hold: a dependant deployed concurrently is not excluded by it.
     */
    public Uninstalled uninstall(String packId, String tenantId) {
        return lock.exclusive(tenantId, packId, () -> uninstallLocked(packId, tenantId));
    }

    private Uninstalled uninstallLocked(String packId, String tenantId) {
        List<Installed> all = installed(tenantId);
        List<Installed> mine = all.stream().filter(i -> i.packId().equals(packId)).toList();
        if (mine.isEmpty()) {
            throw new PackLifecycleException("PACK_NOT_INSTALLED", "pack " + packId + " is not installed for "
                    + scope(tenantId), List.of());
        }
        java.util.Set<String> dependants = new java.util.TreeSet<>();
        for (Installed other : all) {
            if (!other.packId().equals(packId) && registry.find(other.packId())
                    .map(p -> p.descriptor().dependsOn().contains(packId)).orElse(false)) {
                dependants.add(other.packId());
            }
        }
        if (!dependants.isEmpty()) {
            throw new PackLifecycleException("PACK_HAS_DEPENDANTS", "uninstall " + packId + " from " + scope(tenantId)
                    + " after its dependants", List.copyOf(dependants));
        }
        List<String> live = liveInstances(mine);
        if (!live.isEmpty()) {
            throw new PackLifecycleException("PACK_HAS_LIVE_INSTANCES", "pack " + packId + " has running instances in "
                    + scope(tenantId) + "; complete or migrate them first", live);
        }
        var repo = engine.getRepositoryService();
        Map<String, List<String>> suspendedByUs = new java.util.HashMap<>();
        for (Installed i : mine) {
            for (var pd : repo.createProcessDefinitionQuery().deploymentId(i.deploymentId()).active().list()) {
                repo.suspendProcessDefinitionById(pd.getId(), false, null);
                suspendedByUs.computeIfAbsent(i.deploymentId(), k -> new ArrayList<>()).add(pd.getId());
            }
        }
        List<String> removed = new ArrayList<>();
        try {
            for (Installed i : mine) {
                repo.deleteDeployment(i.deploymentId(), false); // no cascade: refuses running instances, keeps history
                removed.add(i.deploymentId());
            }
        } catch (RuntimeException e) {
            // an instance started in between: put the remaining versions back in service, then report
            for (Installed i : mine) {
                if (!removed.contains(i.deploymentId())) {
                    for (String pd : suspendedByUs.getOrDefault(i.deploymentId(), List.of())) {
                        repo.activateProcessDefinitionById(pd, false, null);
                    }
                }
            }
            throw new PackLifecycleException("PACK_HAS_LIVE_INSTANCES", "pack " + packId + " got a running instance during"
                    + " uninstall; " + removed.size() + " of " + mine.size() + " versions removed", List.of(e.getMessage()));
        }
        LOG.info("uninstalled pack {} from {}: deployments {}", packId, scope(tenantId), removed);
        return new Uninstalled(packId, tenantId, removed);
    }

    /** A pack version the roll-forward can count from: {@code MAJOR.MINOR.PATCH}, digits only. */
    private static final java.util.regex.Pattern SEMVER = java.util.regex.Pattern.compile("(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})");

    /**
     * Rolls a pack back to its previous content in one tenant scope, as a ROLL-FORWARD (SL-54): the resources of the
     * most recent deployment of a different version are read back from the engine (not from the class path, which only
     * has the current version) and deployed as a NEW version, the next patch above the highest version of the pack in
     * the scope, with every version-bearing part restated for it ({@link PackVersionRewriter}). No existing
     * {@code id@version} is reused or changed, so one {@code id@version} keeps exactly one content and every
     * {@code versionTag} pin keeps resolving to exactly one definition. New instances started by key then start on the
     * previous models (the new deployment is the latest definition version); running instances keep the definitions
     * they started on (deployment binding). A second rollback rolls forward again (the most recent different version is
     * then the one rolled back from), each time to a new version: an older definition can never again become the latest
     * one, so reusing an earlier roll-forward could not change what new instances start on.
     * Refused with {@code PACK_NO_PREVIOUS_VERSION}, and with {@code PACK_VERSION_NOT_SEMVER} when an installed version
     * of the pack is not {@code MAJOR.MINOR.PATCH}, so the next version cannot be determined. Reading the installed
     * versions, computing the next one and deploying it run inside the pack's {@link PackOperationLock} (SL-116), so a
     * concurrent rollback or deploy of the same pack in the same scope computes from this one's committed result.
     */
    public Result rollback(String packId, String tenantId) {
        return lock.exclusive(tenantId, packId, () -> rollbackLocked(packId, tenantId));
    }

    private Result rollbackLocked(String packId, String tenantId) {
        List<Installed> mine = installed(tenantId).stream().filter(i -> i.packId().equals(packId)).toList();
        if (mine.isEmpty()) {
            throw new PackLifecycleException("PACK_NOT_INSTALLED", "pack " + packId + " is not installed for "
                    + scope(tenantId), List.of());
        }
        Installed current = mine.get(mine.size() - 1);
        Installed previous = null;
        for (int i = mine.size() - 2; i >= 0 && previous == null; i--) {
            if (!mine.get(i).version().equals(current.version())) {
                previous = mine.get(i);
            }
        }
        if (previous == null) {
            throw new PackLifecycleException("PACK_NO_PREVIOUS_VERSION", "pack " + packId + " has only version "
                    + current.version() + " in " + scope(tenantId), List.of());
        }
        String next = nextPatchVersion(packId, tenantId, mine);
        var repo = engine.getRepositoryService();
        List<String> names = repo.getDeploymentResourceNames(previous.deploymentId());
        Map<String, byte[]> content = new java.util.LinkedHashMap<>();
        for (String n : names) {
            try (InputStream in = repo.getResourceAsStream(previous.deploymentId(), n)) {
                content.put(n, in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, byte[]> restated = PackVersionRewriter.rewrite(content, previous.version(), next);
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : restated.entrySet()) {
            String n = e.getKey();
            if (n.endsWith(".bpmn") || n.endsWith(".bpmn20.xml")) {
                violations.addAll(ModelGuard.check(n, e.getValue())); // the guard may be stricter now than at first deploy
            }
        }
        if (!violations.isEmpty()) {
            throw new PackRejectedException(packId, violations);
        }
        // the new version is above every version of the scope, so it holds no deployment yet; the check is kept so the
        // one-content-per-version rule has a single enforcement point
        String existing = requireImmutableVersion(packId, next, tenantId, restated);
        DeploymentBuilder b = repo.createDeployment().name(NAME_PREFIX + packId + "@" + next).source(SOURCE)
                .enableDuplicateFiltering(true);
        if (tenantId != null) {
            b = b.tenantId(tenantId);
        }
        for (Map.Entry<String, byte[]> e : restated.entrySet()) {
            b = b.addInputStream(e.getKey(), new ByteArrayInputStream(e.getValue()));
        }
        String depId = existing != null ? existing : b.deployWithResult().getId();
        LOG.info("rolled back pack {} in {} from {} to the content of {}, rolled forward as {}: deployment {}", packId,
                scope(tenantId), current.version(), previous.version(), next, depId);
        return new Result(packId, next, tenantId, depId, names.size(), existing == null);
    }

    /** The next patch version above the highest installed version of the pack in the scope (SL-54 roll-forward). */
    private static String nextPatchVersion(String packId, String tenantId, List<Installed> mine) {
        int[] highest = null;
        List<String> notSemver = new ArrayList<>();
        for (Installed i : mine) {
            java.util.regex.Matcher m = SEMVER.matcher(i.version());
            if (!m.matches()) {
                notSemver.add(i.packId() + "@" + i.version());
                continue;
            }
            int[] v = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            if (highest == null || java.util.Arrays.compare(v, highest) > 0) {
                highest = v;
            }
        }
        if (!notSemver.isEmpty() || highest == null) {
            throw new PackLifecycleException("PACK_VERSION_NOT_SEMVER", "pack " + packId + " in " + scope(tenantId)
                    + " has versions that are not MAJOR.MINOR.PATCH, so the roll-forward version cannot be determined",
                    notSemver);
        }
        return highest[0] + "." + highest[1] + "." + (highest[2] + 1);
    }

    private List<String> liveInstances(List<Installed> deployments) {
        List<String> out = new ArrayList<>();
        for (Installed i : deployments) {
            long processes = engine.getRuntimeService().createProcessInstanceQuery().deploymentId(i.deploymentId()).count();
            if (processes > 0) {
                out.add(i.packId() + "@" + i.version() + ": " + processes + " process instance(s)");
            }
            long cases = 0;
            for (var cd : engine.getRepositoryService().createCaseDefinitionQuery().deploymentId(i.deploymentId()).list()) {
                cases += engine.getCaseService().createCaseInstanceQuery().caseDefinitionId(cd.getId()).count();
            }
            if (cases > 0) {
                out.add(i.packId() + "@" + i.version() + ": " + cases + " case instance(s)");
            }
        }
        return out;
    }

    private static String scope(String tenantId) {
        return tenantId == null ? "the shared scope" : "tenant " + tenantId;
    }

    private static boolean notEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }

    private static byte[] read(SangamPack pack, String path) {
        try (InputStream in = pack.open(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
