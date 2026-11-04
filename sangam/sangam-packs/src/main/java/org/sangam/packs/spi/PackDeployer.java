// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
