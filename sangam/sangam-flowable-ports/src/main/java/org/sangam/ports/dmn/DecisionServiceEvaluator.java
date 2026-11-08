// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.dmn;

import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.repository.DecisionDefinition;
import org.operaton.bpm.engine.repository.DecisionRequirementsDefinition;
import org.sangam.ports.support.SecureXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DMN decision services (FLOWABLE-INTO-OPERATON FC-53; Flowable {@code DmnDecisionService.executeDecisionService}). Operaton's DMN engine
 * has no decision-service element (grep 0) and its model API does not parse it, so the service definition is read
 * from the deployed DMN XML (hardened parser) and evaluated on Operaton's engine: each <em>output decision</em> is
 * evaluated with the given inputs; required (encapsulated) decisions are evaluated by Operaton's DRG evaluation. The
 * result maps each output decision id to its result list. Decision-service-level audit is one entry per output
 * decision in Operaton's DMN history.
 *
 * <p>Gap list route was R8 (a second DMN engine via SPI); this native route avoids a second engine for the common
 * case. Input-data validation against {@code inputData} types is not enforced (the DMN engine coerces types).
 */
public class DecisionServiceEvaluator {

    public record DecisionServiceDefinition(String id, String name, List<String> outputDecisions, List<String> encapsulatedDecisions,
                                            List<String> inputDecisions, List<String> inputData) {
    }

    private final ProcessEngine engine;
    private final Map<String, List<DecisionServiceDefinition>> cache = new ConcurrentHashMap<>();

    public DecisionServiceEvaluator(ProcessEngine engine) {
        this.engine = engine;
    }

    /** Decision services in the DRD with the given key (latest version, tenant or shared). */
    public List<DecisionServiceDefinition> services(String drdKey, String tenantId) {
        DecisionRequirementsDefinition drd = latestDrd(drdKey, tenantId);
        return cache.computeIfAbsent(drd.getId(), id -> parse(drd));
    }

    public Map<String, List<Map<String, Object>>> evaluate(String drdKey, String serviceId, String tenantId, Map<String, Object> inputs) {
        DecisionRequirementsDefinition drd = latestDrd(drdKey, tenantId);
        DecisionServiceDefinition svc = services(drdKey, tenantId).stream().filter(s -> s.id().equals(serviceId)).findFirst()
                .orElseThrow(() -> new ProcessEngineException("decision service " + serviceId + " not found in " + drdKey));
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (String decisionId : svc.outputDecisions()) {
            DecisionDefinition dd = engine.getRepositoryService().createDecisionDefinitionQuery()
                    .decisionRequirementsDefinitionId(drd.getId()).decisionDefinitionKey(decisionId).singleResult();
            if (dd == null) {
                throw new ProcessEngineException("output decision " + decisionId + " of service " + serviceId + " is not deployed");
            }
            DmnDecisionResult r = engine.getDecisionService().evaluateDecisionById(dd.getId()).variables(inputs).evaluate();
            List<Map<String, Object>> rows = new ArrayList<>();
            r.forEach(row -> rows.add(new LinkedHashMap<>(row.getEntryMap())));
            out.put(decisionId, rows);
        }
        return out;
    }

    private DecisionRequirementsDefinition latestDrd(String key, String tenantId) {
        var q = engine.getRepositoryService().createDecisionRequirementsDefinitionQuery().decisionRequirementsDefinitionKey(key).latestVersion();
        if (tenantId != null) {
            q.tenantIdIn(tenantId);
        } else {
            q.withoutTenantId();
        }
        DecisionRequirementsDefinition d = q.singleResult();
        if (d == null) {
            throw new ProcessEngineException("decision requirements definition " + key + " not found");
        }
        return d;
    }

    private List<DecisionServiceDefinition> parse(DecisionRequirementsDefinition drd) {
        try (InputStream in = engine.getRepositoryService().getResourceAsStream(drd.getDeploymentId(), drd.getResourceName())) {
            Document doc = SecureXml.parse(in.readAllBytes());
            List<DecisionServiceDefinition> out = new ArrayList<>();
            NodeList services = doc.getElementsByTagNameNS("*", "decisionService");
            for (int i = 0; i < services.getLength(); i++) {
                Element s = (Element) services.item(i);
                out.add(new DecisionServiceDefinition(s.getAttribute("id"), s.getAttribute("name"), refs(s, "outputDecision"),
                        refs(s, "encapsulatedDecision"), refs(s, "inputDecision"), refs(s, "inputData")));
            }
            return out;
        } catch (java.io.IOException e) {
            throw new ProcessEngineException("cannot read DMN resource of " + drd.getId(), e);
        }
    }

    private static List<String> refs(Element s, String tag) {
        List<String> out = new ArrayList<>();
        NodeList l = s.getElementsByTagNameNS("*", tag);
        for (int i = 0; i < l.getLength(); i++) {
            String href = ((Element) l.item(i)).getAttribute("href");
            out.add(href.startsWith("#") ? href.substring(1) : href);
        }
        return out;
    }
}
