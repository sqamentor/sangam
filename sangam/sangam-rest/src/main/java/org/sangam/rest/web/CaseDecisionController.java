// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;
import java.util.Map;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.CasePort;
import org.sangam.rest.port.DecisionPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TypedVariables;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CMMN cases (FC-55..59 behind the port) and DMN decisions (ARCHITECTURE §4). */
@RestController
@RequestMapping("/api/v1")
public class CaseDecisionController {

    private final CasePort cases;
    private final DecisionPort decisions;
    private final PermissionPort permissions;

    public CaseDecisionController(CasePort cases, DecisionPort decisions, PermissionPort permissions) {
        this.cases = cases;
        this.decisions = decisions;
        this.permissions = permissions;
    }

    @PostMapping("/case-instances")
    public ResponseEntity<CasePort.CaseInstance> startCase(RequestContext ctx, @RequestBody CasePort.StartCaseRequest body) {
        permissions.require(ctx, Permissions.CASE_WRITE);
        if (body.key() == null || body.key().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "key is required");
        }
        Telemetry.businessKey(body.businessKey());
        CasePort.CaseInstance ci = cases.start(ctx, new CasePort.StartCaseRequest(body.key(), body.businessKey(),
                TypedVariables.fromJson(body.variables())));
        return Lists.created("/api/v1/case-instances/" + ci.id(), ci.id(), ci);
    }

    @GetMapping("/case-instances")
    public ResponseEntity<List<CasePort.CaseInstance>> listCases(RequestContext ctx,
            @RequestParam(required = false) String key, @RequestParam(required = false) String businessKey,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.CASE_READ);
        return Lists.ok(cases.list(ctx, key, businessKey, active, Lists.page(page, size)));
    }

    @GetMapping("/case-instances/{id}")
    public CasePort.CaseInstance getCase(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.CASE_READ);
        return cases.get(ctx, id);
    }

    @GetMapping("/case-instances/{id}/variables")
    public Map<String, Object> caseVariables(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.CASE_READ);
        return cases.variables(ctx, id);
    }

    @PostMapping("/case-instances/{id}/commands")
    public ResponseEntity<CasePort.CaseInstance> caseCommand(RequestContext ctx, @PathVariable String id,
                                                             @RequestBody CasePort.InstanceCommand body) {
        permissions.require(ctx, Permissions.CASE_WRITE);
        requireAction(body.action());
        return Lists.okRef(id, cases.instanceCommand(ctx, id,
                new CasePort.InstanceCommand(body.action(), TypedVariables.fromJson(body.variables()), body.reason())));
    }

    @PostMapping("/case-instances/{id}/executions/{executionId}/commands")
    public ResponseEntity<CasePort.CaseInstance> executionCommand(RequestContext ctx, @PathVariable String id,
            @PathVariable String executionId, @RequestBody CasePort.ExecutionCommand body) {
        permissions.require(ctx, Permissions.CASE_WRITE);
        requireAction(body.action());
        return Lists.okRef(id, cases.executionCommand(ctx, id, executionId,
                new CasePort.ExecutionCommand(body.action(), TypedVariables.fromJson(body.variables()), body.reason())));
    }

    @GetMapping("/case-definitions")
    public ResponseEntity<List<CasePort.CaseDefinition>> caseDefinitions(RequestContext ctx,
            @RequestParam(required = false) String key, @RequestParam(required = false) Boolean latest,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.CASE_READ);
        return Lists.ok(cases.definitions(ctx, key, latest, Lists.page(page, size)));
    }

    @PostMapping("/decisions/{key}/evaluate")
    public DecisionPort.EvaluateResult evaluate(RequestContext ctx, @PathVariable String key,
                                                @RequestBody DecisionPort.EvaluateRequest body) {
        permissions.require(ctx, Permissions.DECISION_EVALUATE);
        Telemetry.businessKey(body.businessKey());
        return decisions.evaluate(ctx, key, new DecisionPort.EvaluateRequest(TypedVariables.fromJson(body.variables()),
                body.version(), body.purpose(), body.businessKey()));
    }

    @GetMapping("/decision-definitions")
    public ResponseEntity<List<DecisionPort.DecisionDefinition>> decisionDefinitions(RequestContext ctx,
            @RequestParam(required = false) String key, @RequestParam(required = false) Boolean latest,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return Lists.ok(decisions.definitions(ctx, key, latest, Lists.page(page, size)));
    }

    @GetMapping(value = "/decision-definitions/{id}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String decisionXml(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PROCESS_READ);
        return decisions.definitionXml(ctx, id);
    }

    private static void requireAction(String action) {
        if (action == null || action.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "action is required");
        }
    }
}
