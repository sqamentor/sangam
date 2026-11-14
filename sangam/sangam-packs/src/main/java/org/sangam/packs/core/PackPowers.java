// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.repository.ProcessDefinition;

import org.sangam.core.power.ManifestOperationMetadata;
import org.sangam.packs.spi.PackDeployer;

/**
 * The power-limit metadata of a pack case read from the deployed pack manifest (owner decision 193).
 *
 * <p>Packgen ({@code tools/packgen/powers.py}) writes a {@code powers} block into the {@code pack.json} of every pack
 * that calls the core sanction, one declaration per call activity (the action), and lists the manifest as a resource of
 * the pack, so {@link PackDeployer} deploys it with the pack version. The case type of a sanction is the process
 * definition that called it (key and engine version); its deployment must be a pack deployment
 * ({@link PackDeployer#SOURCE}, name {@code pack:<id>@<version>}) of the same tenant, and the manifest it carries must
 * name that same id and version. Anything else answers empty, and the evaluator denies. Nothing is read from a request,
 * a process variable or the class path, so a case keeps the declarations of the pack version it runs.
 */
public final class PackPowers implements ManifestOperationMetadata.Deployments {

    /** The call activity that started a core sanction: its process definition (the case type) and its id (the action). */
    public record Caller(String processDefinitionId, String activityId) {
    }

    /** A deployed pack manifest: the declarations and the currency of the pack's money values and limit tables. */
    public record Manifest(ManifestOperationMetadata.Deployed deployed, String currency) {
    }

    private static final ObjectMapper JSON = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final String MANIFEST = "pack.json";

    private final RepositoryService repo;

    public PackPowers(RepositoryService repo) {
        this.repo = repo;
    }

    /**
     * The call activity of the parent process that started the process {@code ex} runs in, or null when it was not
     * started by a call activity (then no case type is known and the evaluator denies).
     */
    public static Caller caller(DelegateExecution ex) {
        DelegateExecution instance = ex.getProcessInstance();
        DelegateExecution sup = instance == null ? null : instance.getSuperExecution();
        if (sup == null || sup.getProcessDefinitionId() == null || sup.getCurrentActivityId() == null) {
            return null;
        }
        return new Caller(sup.getProcessDefinitionId(), sup.getCurrentActivityId());
    }

    /** The process definition of {@code caller}. */
    public ProcessDefinition definition(Caller caller) {
        return repo.getProcessDefinition(caller.processDefinitionId());
    }

    @Override
    public Optional<ManifestOperationMetadata.Deployed> deployed(String tenant, String caseType, int caseTypeVersion) {
        return manifest(tenant, caseType, caseTypeVersion).map(Manifest::deployed);
    }

    /** The deployed manifest of process {@code key} version {@code version} in {@code tenant}, if it is a pack's. */
    public Optional<Manifest> manifest(String tenant, String key, int version) {
        if (tenant == null || key == null) {
            return Optional.empty();
        }
        ProcessDefinition def = repo.createProcessDefinitionQuery().processDefinitionKey(key)
                .processDefinitionVersion(version).tenantIdIn(tenant).singleResult();
        if (def == null) {
            return Optional.empty();
        }
        Deployment dep = repo.createDeploymentQuery().deploymentId(def.getDeploymentId()).singleResult();
        if (dep == null || !PackDeployer.SOURCE.equals(dep.getSource()) || !tenant.equals(dep.getTenantId())
                || dep.getName() == null || !dep.getName().startsWith("pack:")) {
            return Optional.empty();
        }
        List<String> manifests = new ArrayList<>();
        for (String n : repo.getDeploymentResourceNames(dep.getId())) {
            if (n.equals(MANIFEST) || n.endsWith("/" + MANIFEST)) {
                manifests.add(n);
            }
        }
        if (manifests.size() != 1) {
            return Optional.empty(); // a pack version deployed before decision 193, or an ambiguous deployment
        }
        Map<String, Object> m;
        try (InputStream in = repo.getResourceAsStream(dep.getId(), manifests.get(0))) {
            m = JSON.readValue(in, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("deployed pack manifest " + dep.getName() + " is unreadable", e);
        }
        Object id = m.get("id");
        Object ver = m.get("version");
        if (!(id instanceof String packId) || !(ver instanceof String packVersion)
                || !dep.getName().equals("pack:" + packId + "@" + packVersion)) {
            return Optional.empty(); // the manifest is not the one of this deployment
        }
        Object currency = m.get("currency");
        return Optional.of(new Manifest(new ManifestOperationMetadata.Deployed(packId, packVersion,
                ManifestOperationMetadata.parse(m.get("powers"))), currency instanceof String c ? c : null));
    }
}
