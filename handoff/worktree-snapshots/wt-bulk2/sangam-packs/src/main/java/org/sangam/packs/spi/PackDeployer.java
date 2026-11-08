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
 *       restart redeploys nothing and a changed pack creates new definition versions while running cases keep theirs
 *       (GP-19, GP-20, R43);</li>
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

    public PackDeployer(ProcessEngine engine, PackRegistry registry) {
        this.engine = engine;
        this.registry = registry;
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

    public Result deploy(SangamPack pack, String tenantId) {
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
        for (int i = 0; i < d.resources().size(); i++) {
            String path = d.resources().get(i).path();
            if (d.resources().get(i).kind() == PackResource.Kind.DMN
                    && replaced.contains(path.substring(path.lastIndexOf('/') + 1))) {
                continue; // the tenant's own table replaces the shared one of the same key in this deployment
            }
            b = b.addInputStream(path, new ByteArrayInputStream(contents.get(i)));
        }
        for (String override : d.overridesFor(tenantId)) {
            // department-specific DMN deployed for this tenant only; Decisions prefers it (GP-37)
            b = b.addInputStream(override, new ByteArrayInputStream(read(pack, override)));
        }
        DeploymentWithDefinitions dep = b.deployWithResult();
        boolean fresh = notEmpty(dep.getDeployedProcessDefinitions()) || notEmpty(dep.getDeployedDecisionDefinitions())
                || notEmpty(dep.getDeployedCaseDefinitions());
        if (PackRuntime.seats() instanceof InMemorySeatDirectory mem) {
            for (PackResource r : d.resources(PackResource.Kind.SEED)) {
                try (InputStream in = pack.open(r.path())) {
                    mem.load(tenantId, in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        LOG.info("deployed pack {} (tenant {}): deployment {}, {} resources", d.coordinates(), tenantId, dep.getId(),
                d.resources().size());
        return new Result(d.id(), d.version(), tenantId, dep.getId(), d.resources().size(), fresh);
    }

    // ------------------------------------------------------------------ lifecycle: installed, uninstall, rollback (SL-41)

    /** Deployment name prefix of every pack deployment. */
    static final String NAME_PREFIX = "pack:";
    static final String SOURCE = "sangam-packs";

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
     */
    public Uninstalled uninstall(String packId, String tenantId) {
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

    /**
     * Rolls a pack back to its previous version in one tenant scope: the resources of the most recent deployment of a
     * different version are read back from the engine (not from the class path, which only has the current version) and
     * deployed again as {@code pack:<id>@<previous>}. New instances then start on the previous models; running
     * instances keep the definitions they started on (deployment binding). Refused with {@code PACK_NO_PREVIOUS_VERSION}.
     */
    public Result rollback(String packId, String tenantId) {
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
        var repo = engine.getRepositoryService();
        DeploymentBuilder b = repo.createDeployment().name(NAME_PREFIX + packId + "@" + previous.version())
                .source(SOURCE); // no duplicate filtering: enableDuplicateFiltering(x) always filters (x = changed only), and the
        // last deployment of this name is the previous version itself, so a filtered rollback would deploy nothing
        if (tenantId != null) {
            b = b.tenantId(tenantId);
        }
        List<String> names = repo.getDeploymentResourceNames(previous.deploymentId());
        List<String> violations = new ArrayList<>();
        for (String n : names) {
            byte[] bytes;
            try (InputStream in = repo.getResourceAsStream(previous.deploymentId(), n)) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (n.endsWith(".bpmn") || n.endsWith(".bpmn20.xml")) {
                violations.addAll(ModelGuard.check(n, bytes)); // the guard may be stricter now than at first deploy
            }
            b = b.addInputStream(n, new ByteArrayInputStream(bytes));
        }
        if (!violations.isEmpty()) {
            throw new PackRejectedException(packId, violations);
        }
        DeploymentWithDefinitions dep = b.deployWithResult();
        LOG.info("rolled back pack {} in {} from {} to {}: deployment {}", packId, scope(tenantId), current.version(),
                previous.version(), dep.getId());
        return new Result(packId, previous.version(), tenantId, dep.getId(), names.size(), true);
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
