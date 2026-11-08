// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.engine.DecisionService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.dmn.DecisionsEvaluationBuilder;
import org.operaton.bpm.engine.repository.DecisionDefinitionQuery;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DecisionPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.support.TypedVariables;

/**
 * DMN evaluation on the Operaton {@link DecisionService}. The definition is resolved first (tenant definition, else a
 * shared one), so the result names the exact definition id and version that decided. ROUTING / AUTHORITY evaluations
 * with a business key are class-B evidence ({@code ROUTING_DECISION} / {@code AUTHORITY_DECISION}, X.2.3.2) carrying
 * the input digest and output digest.
 */
public class OperatonDecisionAdapter implements DecisionPort {

    private static final long DEFINITION_CAP = 10_000;

    private final DecisionService decisions;
    private final RepositoryService repository;
    private final TxSupport tx;
    private final AuditPort audit;

    public OperatonDecisionAdapter(DecisionService decisions, RepositoryService repository, TxSupport tx,
                                   AuditPort audit) {
        this.decisions = decisions;
        this.repository = repository;
        this.tx = tx;
        this.audit = audit;
    }

    @Override
    public EvaluateResult evaluate(RequestContext ctx, String decisionKey, EvaluateRequest r) {
        EvaluateRequest req = r == null ? new EvaluateRequest(Map.of(), null, null, null) : r;
        org.operaton.bpm.engine.repository.DecisionDefinition def = resolve(ctx, decisionKey, req.version());
        Map<String, Object> vars = TypedVariables.fromJson(req.variables());
        DecisionsEvaluationBuilder b = decisions.evaluateDecisionByKey(def.getKey());
        b = def.getTenantId() == null ? b.decisionDefinitionWithoutTenantId() : b.decisionDefinitionTenantId(def.getTenantId());
        DecisionsEvaluationBuilder fb = b.version(def.getVersion()).variables(vars);
        DmnDecisionResult result = OperatonSupport.call(fb::evaluate);
        List<Map<String, Object>> rows = result.getResultList().stream().map(TypedVariables::toJson).toList();
        String outputDigest = Hashes.hex(Hashes.sha256(PayloadHash.canonical(rows)));
        String purpose = req.purpose() == null ? "OTHER" : req.purpose().toUpperCase(java.util.Locale.ROOT);
        String eventId = null;
        if (req.businessKey() != null && ("ROUTING".equals(purpose) || "AUTHORITY".equals(purpose))) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("decision_definition_id", def.getId());
            p.put("decision_key", def.getKey());
            p.put("decision_version", def.getVersion());
            p.put("deployment_id", def.getDeploymentId());
            p.put("inputs_digest", Hashes.hex(Hashes.sha256(PayloadHash.canonical(vars))));
            p.put("output_digest", outputDigest);
            p.put("output", rows);
            p.put("actor", ctx.actor());
            p.values().removeIf(Objects::isNull);
            String type = purpose + "_DECISION";
            eventId = tx.inTx(() -> audit.record(ctx, req.businessKey(), type, p));
        }
        return new EvaluateResult(def.getId(), def.getKey(), def.getVersion(), rows, outputDigest, eventId);
    }

    private org.operaton.bpm.engine.repository.DecisionDefinition resolve(RequestContext ctx, String key, Integer version) {
        if (key == null || key.isBlank()) {
            throw ApiProblem.badRequest("KEY_REQUIRED", "the decision key is required");
        }
        DecisionDefinitionQuery q = repository.createDecisionDefinitionQuery().decisionDefinitionKey(key);
        if (version != null) {
            q.decisionDefinitionVersion(version);
        } else {
            q.latestVersion();
        }
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId()).includeDecisionDefinitionsWithoutTenantId();
        } else {
            q.withoutTenantId();
        }
        List<org.operaton.bpm.engine.repository.DecisionDefinition> defs = OperatonSupport.call(q::list);
        if (defs.isEmpty()) {
            throw ApiProblem.notFound("decision definition", version == null ? key : key + ":" + version);
        }
        // A tenant's own definition wins over a shared one (FLOWABLE-INTO-OPERATON FC-33 fallback to shared definitions).
        return defs.stream().filter(d -> Objects.equals(d.getTenantId(), ctx.tenantId())).findFirst()
                .orElse(defs.get(0));
    }

    @Override
    public Page<DecisionDefinition> definitions(RequestContext ctx, String key, Boolean latest, Page.PageRequest page) {
        DecisionDefinitionQuery q = repository.createDecisionDefinitionQuery();
        if (ctx.tenantId() != null) {
            q.tenantIdIn(ctx.tenantId()).includeDecisionDefinitionsWithoutTenantId();
        }
        if (key != null) {
            q.decisionDefinitionKey(key);
        }
        if (Boolean.TRUE.equals(latest)) {
            q.latestVersion();
        }
        q.orderByDecisionDefinitionKey().asc().orderByDecisionDefinitionVersion().desc();
        return OperatonSupport.page(q, page, DEFINITION_CAP, d -> new DecisionDefinition(d.getId(), d.getKey(),
                d.getName(), d.getVersion(), d.getDeploymentId(), d.getTenantId(),
                d.getDecisionRequirementsDefinitionKey(), d.getVersionTag()));
    }

    @Override
    public String definitionXml(RequestContext ctx, String definitionId) {
        org.operaton.bpm.engine.repository.DecisionDefinition d = OperatonSupport.call(() ->
                repository.createDecisionDefinitionQuery().decisionDefinitionId(definitionId).singleResult());
        if (d == null) {
            throw ApiProblem.notFound("decision definition", definitionId);
        }
        if (d.getTenantId() != null) {
            OperatonSupport.checkTenant(ctx, d.getTenantId(), "decision definition", definitionId);
        }
        try (InputStream in = OperatonSupport.call(() -> repository.getDecisionModel(definitionId))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiProblem(500, "RESOURCE_READ_FAILED", e.getMessage());
        }
    }
}
