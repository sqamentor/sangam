// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.arazzo;

import static org.sangam.modeller.arazzo.ArazzoSyntax.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.modeller.arazzo.ArazzoBpmnWriter.Next;
import org.sangam.modeller.arazzo.ArazzoBpmnWriter.NextEnd;
import org.sangam.modeller.arazzo.ArazzoBpmnWriter.NextStep;
import org.sangam.modeller.arazzo.ArazzoBpmnWriter.OperationTask;
import org.sangam.modeller.arazzo.ArazzoBpmnWriter.WorkflowCall;
import org.sangam.modeller.arazzo.ArazzoImport.ContractRef;
import org.sangam.modeller.arazzo.ArazzoImport.Diagnostic;
import org.sangam.modeller.arazzo.ArazzoImport.Severity;
import org.sangam.modeller.arazzo.ArazzoImport.Status;

/**
 * M-069 (owner decisions 159 and 204): imports an Arazzo workflow document (JSON or YAML, the YAML form read safely by
 * {@link ArazzoYaml}; {@code arazzo} 1.0.x or 1.1.x) as a BPMN
 * draft whose operation steps are service tasks bound with {@code sangam:operationRef} (M-068) and run by the
 * operationRef runtime step in sangam-integration. No conformance with the Arazzo specification is claimed: the 1.1.0
 * changes were not available when this was written, so any field this importer does not know is an ERROR, never
 * silently dropped.
 *
 * <p>Supported subset:
 * <ul>
 *   <li>each workflow becomes an executable process (id = workflowId), steps in document order between a start and an
 *       end event;</li>
 *   <li>a step with {@code operationId} ({@code $sourceDescriptions.<name>.<operationId>} when the document has more
 *       than one OpenAPI source) or {@code operationPath} (resolved only against a supplied OpenAPI document) becomes
 *       a service task, {@code operaton:type="external"}, topic {@value #TOPIC},
 *       {@code sangam:operationRef="<contractId>#<operationId>"} (unpinned; the package deploy pins it);</li>
 *   <li>the step's parameters (workflow parameters merged in), request body, success criteria and outputs are kept
 *       verbatim, runtime expressions included, as static {@code operaton:properties} {@value #PROP_REQUEST},
 *       {@value #PROP_CRITERIA}, {@value #PROP_OUTPUTS} and {@value #PROP_VERSION}, for the runtime step to read;</li>
 *   <li>a step with {@code workflowId} of the same document becomes a call activity (all variables in and out);</li>
 *   <li>a single unconditional success action {@code end} or {@code goto} a step of the same workflow becomes the
 *       step's outgoing sequence flow; an unconditional failure action {@code end} is the default (the step fails,
 *       the workflow stops).</li>
 * </ul>
 * Not converted (ERROR): conditional or multiple actions, {@code retry}, failure {@code goto}, {@code goto}
 * another workflow, workflows of another Arazzo source, parameters, criteria or outputs on a workflow step, and
 * every unknown field. A document is read from bytes only: no URL is fetched.
 */
public final class ArazzoImporter {

    /** The external task topic of the operationRef runtime step (decision 159, W4-H). */
    public static final String TOPIC = "sangam.operation";
    /** JSON object {parameters: [{name, in, value}], requestBody?: {contentType?, payload?, replacements?}}. */
    public static final String PROP_REQUEST = "arazzo.request";
    /** JSON array of the Arazzo criterion objects, verbatim. */
    public static final String PROP_CRITERIA = "arazzo.successCriteria";
    /** JSON object of output name to runtime expression, verbatim. */
    public static final String PROP_OUTPUTS = "arazzo.outputs";
    /** The {@code arazzo} version string of the source document. */
    public static final String PROP_VERSION = "arazzo.version";

    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_WORKFLOWS = 100;
    static final int MAX_STEPS = 500;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(200).maxStringLength(MAX_BYTES).maxNumberLength(100).build());
    }

    /**
     * Import settings.
     *
     * @param contractIds       source name to the contract id the package stores its API document under (a source
     *                          not listed keeps its name)
     * @param openApiDocuments  source name to the OpenAPI document (JSON), to check operation ids and resolve
     *                          {@code operationPath}; never fetched from the source URL
     * @param historyTimeToLive {@code operaton:historyTimeToLive} of every process (ISO-8601 or days), or null
     */
    public record Options(Map<String, String> contractIds, Map<String, byte[]> openApiDocuments,
                          String historyTimeToLive) {
        public Options {
            contractIds = contractIds == null ? Map.of() : Map.copyOf(contractIds);
            openApiDocuments = openApiDocuments == null ? Map.of() : Map.copyOf(openApiDocuments);
        }

        public static Options defaults() {
            return new Options(Map.of(), Map.of(), null);
        }
    }

    private ArazzoImporter() {
    }

    /** {@link #importDocument(String, byte[], Options)} with {@link Options#defaults()}. */
    public static ArazzoImport importDocument(String resource, byte[] arazzo) {
        return importDocument(resource, arazzo, Options.defaults());
    }

    /** Imports {@code arazzo}; never throws for a document problem (it becomes a diagnostic). */
    public static ArazzoImport importDocument(String resource, byte[] arazzo, Options options) {
        return new Run(resource, Objects.requireNonNullElse(options, Options.defaults())).run(arazzo);
    }

    /** One import: the diagnostics and state of a single document. */
    private static final class Run {
        final String resource;
        final Options options;
        final List<Diagnostic> diag = new ArrayList<>();
        final List<ContractRef> contracts = new ArrayList<>();
        final Map<String, ContractRef> sources = new LinkedHashMap<>();
        final Map<String, JsonNode> openApi = new HashMap<>();
        final Set<String> uncheckedReported = new HashSet<>();
        final Set<String> ids = new HashSet<>();
        /** workflowId to BPMN process id, in document order. */
        final Map<String, String> workflowMap = new LinkedHashMap<>();
        JsonNode components = JSON.createObjectNode();
        String version;

        Run(String resource, Options options) {
            this.resource = resource;
            this.options = options;
        }

        ArazzoImport run(byte[] bytes) {
            JsonNode root = read(bytes);
            if (root == null) {
                return manual();
            }
            known(root, ROOT_KEYS, "");
            JsonNode v = root.get("arazzo");
            if (v == null || !v.isTextual() || !VERSION.matcher(v.asText()).matches()) {
                error("ARAZZO-VERSION", "/arazzo", "only arazzo 1.0.x and 1.1.x documents are imported; found "
                        + (v == null ? "none" : v.toString()));
                return manual();
            }
            version = v.asText();
            if (version.startsWith("1.1.")) {
                info("ARAZZO-1-1-UNVERIFIED", "/arazzo", "Arazzo 1.1 constructs are not known to this importer; any "
                        + "field it does not know is an error, so a converted draft uses only 1.0 constructs");
            }
            sources(root.get("sourceDescriptions"));
            if (root.has("components")) {
                components = root.get("components");
                if (!components.isObject()) {
                    error("ARAZZO-SHAPE", "/components", "components must be an object");
                    components = JSON.createObjectNode();
                } else {
                    known(components, COMPONENT_KEYS, "/components");
                }
            }
            List<ArazzoBpmnWriter.Process> processes = workflows(root.get("workflows"));
            if (diag.stream().anyMatch(d -> d.severity() == Severity.ERROR)) {
                return manual();
            }
            if (options.historyTimeToLive() == null) {
                warn("ARAZZO-NO-HISTORY-TTL", "", "no history time to live given; set operaton:historyTimeToLive on "
                        + "every process before deployment");
            }
            info("ARAZZO-UNPINNED", "", "sangam:operationRef values carry no contract version; the package deploy "
                    + "pins each to the stored contract (M-068), so every contract above must be stored in the package");
            info("ARAZZO-FAILURE-DEFAULT", "", "a failed step stops the workflow as Arazzo's default: the step "
                    + "fails and the process instance stops there (incident); model any recovery by hand");
            byte[] bpmn = ArazzoBpmnWriter.write(fresh("arazzo_definitions"), "urn:sangam:arazzo", processes);
            return new ArazzoImport(resource, Status.REVIEW_REQUIRED, bpmn, diag, contracts, workflowMap);
        }

        ArazzoImport manual() {
            return new ArazzoImport(resource, Status.MANUAL, null, diag, contracts, Map.of());
        }

        JsonNode read(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                error("ARAZZO-EMPTY", "", "the document is empty");
                return null;
            }
            if (bytes.length > MAX_BYTES) {
                error("ARAZZO-TOO-LARGE", "", "the document is larger than " + MAX_BYTES + " bytes");
                return null;
            }
            int i = 0;
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                i = 3;
            }
            while (i < bytes.length && Character.isWhitespace(bytes[i])) {
                i++;
            }
            if (i >= bytes.length || bytes[i] != '{') {
                try {
                    JsonNode root = ArazzoYaml.read(bytes, MAX_BYTES);
                    if (!root.isObject()) {
                        error("ARAZZO-UNREADABLE", "", "the YAML document is not a mapping");
                        return null;
                    }
                    info("ARAZZO-YAML", "", "read from YAML (decision 204): one document, no anchors, aliases or custom"
                            + " tags; numbers kept exact");
                    return root;
                } catch (ArazzoYaml.YamlProblem e) {
                    error("ARAZZO-YAML-UNREADABLE", e.line == 0 ? "" : "line " + e.line, e.getMessage());
                    return null;
                }
            }
            try {
                JsonNode root = JSON.readTree(bytes);
                if (root == null || !root.isObject()) {
                    error("ARAZZO-UNREADABLE", "", "the document is not a JSON object");
                    return null;
                }
                return root;
            } catch (IOException e) {
                error("ARAZZO-UNREADABLE", "", "not a readable JSON document: " + e.getMessage());
                return null;
            }
        }

        /** True when the first character after an optional BOM and white space is an opening brace (JSON). */
        static boolean json(byte[] bytes) {
            int i = 0;
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                i = 3;
            }
            while (i < bytes.length && Character.isWhitespace(bytes[i])) {
                i++;
            }
            return i < bytes.length && bytes[i] == '{';
        }

        // ------------------------------------------------------------------ source descriptions

        void sources(JsonNode list) {
            if (list == null || !list.isArray() || list.isEmpty()) {
                error("ARAZZO-NO-SOURCE", "/sourceDescriptions", "at least one source description is required");
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                String at = "/sourceDescriptions/" + i;
                JsonNode s = list.get(i);
                if (!s.isObject()) {
                    error("ARAZZO-SHAPE", at, "a source description must be an object");
                    continue;
                }
                known(s, SOURCE_KEYS, at);
                String name = text(s, "name");
                String type = s.has("type") ? text(s, "type") : "openapi";
                if (name == null || !ID.matcher(name).matches()) {
                    error("ARAZZO-INVALID-ID", at + "/name", "invalid source name " + name);
                    continue;
                }
                if (!"openapi".equals(type) && !"arazzo".equals(type)) {
                    error("ARAZZO-SOURCE-TYPE", at + "/type", "source type must be openapi or arazzo: " + type);
                    continue;
                }
                if (sources.containsKey(name)) {
                    error("ARAZZO-DUPLICATE-ID", at + "/name", "source name " + name + " is used twice");
                    continue;
                }
                String contractId = options.contractIds().getOrDefault(name, name);
                if (!CONTRACT_ID.matcher(contractId).matches()) {
                    error("ARAZZO-CONTRACT-ID", at, "contract id " + contractId + " is not a valid operationRef "
                            + "contract id");
                    continue;
                }
                ContractRef ref = new ContractRef(name, contractId, text(s, "url"), type);
                sources.put(name, ref);
                contracts.add(ref);
                if ("openapi".equals(type)) {
                    info("ARAZZO-CONTRACT", at, "store the OpenAPI document " + ref.url() + " in the package as "
                            + "contract " + contractId);
                }
            }
        }

        /** The supplied OpenAPI document of a source, parsed once; null when none was supplied or it is unreadable. */
        JsonNode openApi(String source, String at) {
            if (openApi.containsKey(source)) {
                return openApi.get(source);
            }
            byte[] doc = options.openApiDocuments().get(source);
            JsonNode node = null;
            if (doc != null) {
                try {
                    node = doc.length > MAX_BYTES * 4 ? null : json(doc) ? JSON.readTree(doc)
                            : ArazzoYaml.read(doc, MAX_BYTES * 4);
                    if (node == null || !node.isObject()) {
                        error("ARAZZO-OPENAPI-UNREADABLE", at, "the OpenAPI document of source " + source
                                + " is not a JSON or YAML object (or larger than " + MAX_BYTES * 4 + " bytes)");
                        node = null;
                    }
                } catch (IOException | ArazzoYaml.YamlProblem e) {
                    error("ARAZZO-OPENAPI-UNREADABLE", at, "the OpenAPI document of source " + source
                            + " cannot be read: " + e.getMessage());
                }
            }
            openApi.put(source, node);
            return node;
        }

        // ------------------------------------------------------------------ workflows

        List<ArazzoBpmnWriter.Process> workflows(JsonNode list) {
            List<ArazzoBpmnWriter.Process> out = new ArrayList<>();
            if (list == null || !list.isArray() || list.isEmpty()) {
                error("ARAZZO-NO-WORKFLOW", "/workflows", "at least one workflow is required");
                return out;
            }
            if (list.size() > MAX_WORKFLOWS) {
                error("ARAZZO-TOO-LARGE", "/workflows", "more than " + MAX_WORKFLOWS + " workflows");
                return out;
            }
            // first pass: process ids, so a step may call a workflow declared later
            for (int i = 0; i < list.size(); i++) {
                JsonNode w = list.get(i);
                String wid = w.isObject() ? text(w, "workflowId") : null;
                if (wid == null || !ID.matcher(wid).matches()) {
                    error("ARAZZO-INVALID-ID", "/workflows/" + i + "/workflowId", "invalid workflowId " + wid);
                } else if (workflowMap.containsKey(wid)) {
                    error("ARAZZO-DUPLICATE-ID", "/workflows/" + i + "/workflowId", "workflowId " + wid + " is used twice");
                } else {
                    workflowMap.put(wid, fresh(ncName(wid)));
                }
            }
            for (int i = 0; i < list.size(); i++) {
                JsonNode w = list.get(i);
                String wid = w.isObject() ? text(w, "workflowId") : null;
                if (wid != null && workflowMap.containsKey(wid)) {
                    ArazzoBpmnWriter.Process p = workflow((ObjectNode) w, "/workflows/" + i, wid);
                    if (p != null) {
                        out.add(p);
                    }
                }
            }
            return out;
        }

        ArazzoBpmnWriter.Process workflow(ObjectNode w, String at, String wid) {
            known(w, WORKFLOW_KEYS, at);
            String pid = workflowMap.get(wid);
            if (w.has("dependsOn") && !w.get("dependsOn").isEmpty()) {
                warn("ARAZZO-DEPENDS-ON", at + "/dependsOn", "dependsOn is not enforced: start the workflows it names "
                        + "and complete them before this process");
            }
            if (w.has("outputs") && !w.get("outputs").isEmpty()) {
                warn("ARAZZO-WORKFLOW-OUTPUTS", at + "/outputs", "workflow outputs are not mapped; the step outputs "
                        + "written by the runtime step are the process result");
            }
            if (w.has("inputs")) {
                info("ARAZZO-INPUTS", at + "/inputs", "start the process with the workflow inputs as variables; "
                        + "$inputs expressions stay in the step properties for the runtime step");
            }
            List<ObjectNode> wfParams = parameters(w.get("parameters"), at + "/parameters", true);
            JsonNode steps = w.get("steps");
            if (steps == null || !steps.isArray() || steps.isEmpty()) {
                error("ARAZZO-NO-STEP", at + "/steps", "a workflow needs at least one step");
                return null;
            }
            if (steps.size() > MAX_STEPS) {
                error("ARAZZO-TOO-LARGE", at + "/steps", "more than " + MAX_STEPS + " steps");
                return null;
            }
            Map<String, String> stepIds = new LinkedHashMap<>();
            for (int i = 0; i < steps.size(); i++) {
                JsonNode s = steps.get(i);
                String sid = s.isObject() ? text(s, "stepId") : null;
                if (sid == null || !ID.matcher(sid).matches()) {
                    error("ARAZZO-INVALID-ID", at + "/steps/" + i + "/stepId", "invalid stepId " + sid);
                } else if (stepIds.containsKey(sid)) {
                    error("ARAZZO-DUPLICATE-ID", at + "/steps/" + i + "/stepId", "stepId " + sid + " is used twice");
                } else {
                    stepIds.put(sid, fresh(pid + "." + ncName(sid)));
                }
            }
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                JsonNode s = steps.get(i);
                String sid = s.isObject() ? text(s, "stepId") : null;
                if (sid != null && stepIds.containsKey(sid)) {
                    Object step = step((ObjectNode) s, at + "/steps/" + i, w, at, wfParams, stepIds);
                    if (step != null) {
                        out.add(step);
                    }
                }
            }
            String name = text(w, "summary");
            return new ArazzoBpmnWriter.Process(pid, name == null ? wid : name, options.historyTimeToLive(), out);
        }

        Object step(ObjectNode s, String at, JsonNode w, String wAt, List<ObjectNode> wfParams,
                    Map<String, String> stepIds) {
            known(s, STEP_KEYS, at);
            String sid = text(s, "stepId");
            String id = stepIds.get(sid);
            String name = s.has("description") ? text(s, "description") : sid;
            int targets = (s.has("operationId") ? 1 : 0) + (s.has("operationPath") ? 1 : 0) + (s.has("workflowId") ? 1 : 0);
            if (targets != 1) {
                error("ARAZZO-STEP-TARGET", at, "a step names exactly one of operationId, operationPath, workflowId");
                return null;
            }
            List<ObjectNode> params = merge(wfParams, parameters(s.get("parameters"), at + "/parameters", false), at);
            Next next = onSuccess(s.has("onSuccess") ? s.get("onSuccess") : w.get("successActions"),
                    s.has("onSuccess") ? at + "/onSuccess" : wAt + "/successActions", stepIds);
            onFailure(s.has("onFailure") ? s.get("onFailure") : w.get("failureActions"),
                    s.has("onFailure") ? at + "/onFailure" : wAt + "/failureActions");
            if (s.has("workflowId")) {
                return workflowStep(s, at, id, name, params, next);
            }
            for (ObjectNode p : params) {
                if (text(p, "in") == null) {
                    error("ARAZZO-PARAMETER", at + "/parameters", "parameter " + text(p, "name") + " of an operation "
                            + "step needs its location (in: path, query, header or cookie)");
                }
            }
            String ref = s.has("operationId") ? operationId(s.get("operationId"), at + "/operationId")
                    : operationPath(s.get("operationPath"), at + "/operationPath");
            Map<String, String> props = new LinkedHashMap<>();
            ObjectNode request = JSON.createObjectNode();
            ArrayNode p = request.putArray("parameters");
            params.forEach(p::add);
            if (s.has("requestBody")) {
                JsonNode body = s.get("requestBody");
                if (!body.isObject()) {
                    error("ARAZZO-SHAPE", at + "/requestBody", "requestBody must be an object");
                } else {
                    known(body, BODY_KEYS, at + "/requestBody");
                    request.set("requestBody", body);
                }
            }
            props.put(PROP_REQUEST, request.toString());
            if (s.has("successCriteria")) {
                criteria(s.get("successCriteria"), at + "/successCriteria");
                props.put(PROP_CRITERIA, s.get("successCriteria").toString());
            }
            if (s.has("outputs")) {
                outputs(s.get("outputs"), at + "/outputs");
                props.put(PROP_OUTPUTS, s.get("outputs").toString());
            }
            props.put(PROP_VERSION, version);
            return ref == null ? null : new OperationTask(id, name, ref, props, next);
        }

        Object workflowStep(ObjectNode s, String at, String id, String name, List<ObjectNode> params, Next next) {
            String target = text(s, "workflowId");
            if (target == null || target.startsWith("$sourceDescriptions.")) {
                error("ARAZZO-EXTERNAL-WORKFLOW", at + "/workflowId", "a workflow of another Arazzo source is not "
                        + "imported; import that document and call its process by hand");
                return null;
            }
            if (!workflowMap.containsKey(target)) {
                error("ARAZZO-UNKNOWN-WORKFLOW", at + "/workflowId", "workflow " + target + " is not in the document");
                return null;
            }
            if (!params.isEmpty()) {
                error("ARAZZO-WORKFLOW-STEP-PARAMETERS", at, "parameters of a workflow step (or workflow parameters "
                        + "applied to it) are not converted; the call activity passes all variables");
            }
            for (String k : List.of("requestBody", "successCriteria", "outputs")) {
                if (s.has(k)) {
                    error("ARAZZO-WORKFLOW-STEP-" + k.toUpperCase(java.util.Locale.ROOT), at + "/" + k, k
                            + " on a workflow step is not converted");
                }
            }
            return new WorkflowCall(id, name, workflowMap.get(target), next);
        }

        // ------------------------------------------------------------------ operations

        String operationId(JsonNode node, String at) {
            String value = node.isTextual() ? node.asText() : null;
            if (value == null) {
                error("ARAZZO-SHAPE", at, "operationId must be a string");
                return null;
            }
            String source;
            String op;
            Matcher m = SOURCE_OPERATION.matcher(value);
            if (m.matches()) {
                source = m.group(1);
                op = m.group(2);
                ContractRef ref = sources.get(source);
                if (ref == null || !"openapi".equals(ref.type())) {
                    error("ARAZZO-UNKNOWN-SOURCE", at, "source " + source + " is not an OpenAPI source of the document");
                    return null;
                }
            } else {
                op = value;
                List<String> candidates = sources.values().stream().filter(r -> "openapi".equals(r.type()))
                        .map(ContractRef::sourceName).toList();
                if (candidates.size() > 1) {
                    List<String> holding = candidates.stream()
                            .filter(c -> openApi(c, at) != null && operationExists(openApi(c, at), op)).toList();
                    if (holding.size() != 1) {
                        error("ARAZZO-AMBIGUOUS-OPERATION", at, "the document has " + candidates.size() + " OpenAPI "
                                + "sources; write $sourceDescriptions.<name>." + op + " or supply the OpenAPI documents");
                        return null;
                    }
                    candidates = holding;
                }
                if (candidates.isEmpty()) {
                    error("ARAZZO-NO-SOURCE", at, "no OpenAPI source description for operation " + op);
                    return null;
                }
                source = candidates.get(0);
            }
            if (!OPERATION_ID.matcher(op).matches()) {
                error("ARAZZO-OPERATION-ID", at, "operation id " + op + " is not a valid operationRef operation id");
                return null;
            }
            JsonNode doc = openApi(source, at);
            if (doc != null && !operationExists(doc, op)) {
                error("ARAZZO-OPERATION-NOT-FOUND", at, "operation " + op + " is not in the OpenAPI document of " + source);
                return null;
            }
            if (doc == null && uncheckedReported.add(source)) {
                warn("ARAZZO-OPERATION-UNCHECKED", at, "operation ids of source " + source + " are not checked (no "
                        + "OpenAPI document supplied); the package deploy refuses a ref the stored contract lacks");
            }
            return sources.get(source).contractId() + "#" + op;
        }

        String operationPath(JsonNode node, String at) {
            Matcher m = node.isTextual() ? OPERATION_PATH.matcher(node.asText()) : null;
            if (m == null || !m.matches()) {
                error("ARAZZO-OPERATION-PATH", at, "operationPath must be {$sourceDescriptions.<name>.url}#<JSON pointer>");
                return null;
            }
            String source = m.group(1);
            ContractRef ref = sources.get(source);
            if (ref == null || !"openapi".equals(ref.type())) {
                error("ARAZZO-UNKNOWN-SOURCE", at, "source " + source + " is not an OpenAPI source of the document");
                return null;
            }
            JsonNode doc = openApi(source, at);
            if (doc == null) {
                error("ARAZZO-OPERATION-PATH-UNRESOLVED", at, "operationPath needs the OpenAPI document of source "
                        + source + " (supplied with the import; it is never fetched)");
                return null;
            }
            JsonNode operation;
            try {
                operation = doc.at(m.group(2));
            } catch (IllegalArgumentException e) {
                operation = null;
            }
            String op = operation != null && operation.isObject() ? text(operation, "operationId") : null;
            if (op == null || !OPERATION_ID.matcher(op).matches()) {
                error("ARAZZO-OPERATION-PATH-UNRESOLVED", at, "operationPath " + node.asText() + " does not name an "
                        + "operation with a valid operationId; operationRef binds operations by id");
                return null;
            }
            return ref.contractId() + "#" + op;
        }

        // ------------------------------------------------------------------ parameters, criteria, outputs

        List<ObjectNode> parameters(JsonNode list, String at, boolean workflowLevel) {
            List<ObjectNode> out = new ArrayList<>();
            if (list == null) {
                return out;
            }
            if (!list.isArray()) {
                error("ARAZZO-SHAPE", at, "parameters must be an array");
                return out;
            }
            for (int i = 0; i < list.size(); i++) {
                String pat = at + "/" + i;
                JsonNode p = resolve(list.get(i), "parameters", pat);
                if (p == null) {
                    continue;
                }
                known(p, PARAMETER_KEYS, pat);
                String name = text(p, "name");
                String in = text(p, "in");
                if (name == null || name.isEmpty() || !p.has("value")) {
                    error("ARAZZO-PARAMETER", pat, "a parameter needs a name and a value");
                    continue;
                }
                if (in != null && !IN.contains(in)) {
                    error("ARAZZO-PARAMETER", pat + "/in", "parameter location must be path, query, header or cookie: " + in);
                    continue;
                }
                out.add((ObjectNode) p);
            }
            if (workflowLevel && !out.isEmpty()) {
                info("ARAZZO-WORKFLOW-PARAMETERS", at, "workflow parameters are applied to every step; a step "
                        + "parameter with the same name and location replaces it");
            }
            return out;
        }

        List<ObjectNode> merge(List<ObjectNode> workflow, List<ObjectNode> step, String at) {
            Map<String, ObjectNode> byKey = new LinkedHashMap<>();
            for (ObjectNode p : workflow) {
                byKey.put(text(p, "name") + "\u0000" + text(p, "in"), p);
            }
            Set<String> seen = new HashSet<>();
            for (ObjectNode p : step) {
                String key = text(p, "name") + "\u0000" + text(p, "in");
                if (!seen.add(key)) {
                    error("ARAZZO-DUPLICATE-PARAMETER", at + "/parameters", "parameter " + text(p, "name")
                            + " in " + text(p, "in") + " is given twice");
                }
                byKey.put(key, p);
            }
            return new ArrayList<>(byKey.values());
        }

        void criteria(JsonNode list, String at) {
            if (!list.isArray()) {
                error("ARAZZO-SHAPE", at, "successCriteria must be an array");
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                JsonNode c = list.get(i);
                if (!c.isObject() || text(c, "condition") == null) {
                    error("ARAZZO-CRITERION", at + "/" + i, "a criterion needs a condition string");
                    continue;
                }
                known(c, CRITERION_KEYS, at + "/" + i);
                JsonNode type = c.get("type");
                if (type != null && !(type.isTextual() && "simple".equals(type.asText()))) {
                    warn("ARAZZO-CRITERION-TYPE", at + "/" + i + "/type", "criterion type " + type + " is evaluated "
                            + "by the runtime step; check that the runtime supports it before deployment");
                }
            }
        }

        void outputs(JsonNode map, String at) {
            if (!map.isObject()) {
                error("ARAZZO-SHAPE", at, "outputs must be an object");
                return;
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = map.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!OUTPUT_NAME.matcher(e.getKey()).matches() || !e.getValue().isTextual()) {
                    error("ARAZZO-OUTPUT", at + "/" + e.getKey(), "an output is a name and a runtime expression string");
                }
            }
        }

        // ------------------------------------------------------------------ actions

        Next onSuccess(JsonNode list, String at, Map<String, String> stepIds) {
            List<JsonNode> actions = actions(list, "successActions", at);
            if (actions.isEmpty()) {
                return null;
            }
            if (actions.size() > 1) {
                error("ARAZZO-UNSUPPORTED-MULTIPLE-ACTIONS", at, "more than one success action (chosen by criteria at "
                        + "run time) is not converted; model the branches with a gateway by hand");
                return null;
            }
            JsonNode a = actions.get(0);
            if (a.has("criteria") && !a.get("criteria").isEmpty()) {
                error("ARAZZO-UNSUPPORTED-CONDITIONAL-ACTION", at, "a success action with criteria is not converted");
                return null;
            }
            String type = text(a, "type");
            if ("end".equals(type)) {
                return new NextEnd();
            }
            if (!"goto".equals(type)) {
                error("ARAZZO-ACTION-TYPE", at, "a success action type is end or goto: " + type);
                return null;
            }
            if (a.has("workflowId")) {
                error("ARAZZO-UNSUPPORTED-GOTO-WORKFLOW", at, "goto another workflow is not converted");
                return null;
            }
            String target = text(a, "stepId");
            if (target == null || !stepIds.containsKey(target)) {
                error("ARAZZO-UNKNOWN-STEP", at, "goto step " + target + " is not a step of this workflow");
                return null;
            }
            return new NextStep(stepIds.get(target));
        }

        void onFailure(JsonNode list, String at) {
            for (JsonNode a : actions(list, "failureActions", at)) {
                String type = text(a, "type");
                boolean conditional = a.has("criteria") && !a.get("criteria").isEmpty();
                if ("end".equals(type) && !conditional) {
                    continue; // Arazzo's default: the workflow stops on a failed step
                }
                if ("retry".equals(type)) {
                    error("ARAZZO-UNSUPPORTED-RETRY", at, "a retry action is not converted; set the external task "
                            + "retries of the runtime step by hand");
                } else if (conditional) {
                    error("ARAZZO-UNSUPPORTED-CONDITIONAL-ACTION", at, "a failure action with criteria is not converted");
                } else {
                    error("ARAZZO-UNSUPPORTED-FAILURE-ACTION", at, "failure action " + type + " is not converted; "
                            + "model it with a boundary error event by hand");
                }
            }
        }

        List<JsonNode> actions(JsonNode list, String component, String at) {
            List<JsonNode> out = new ArrayList<>();
            if (list == null) {
                return out;
            }
            if (!list.isArray()) {
                error("ARAZZO-SHAPE", at, "actions must be an array");
                return out;
            }
            for (int i = 0; i < list.size(); i++) {
                JsonNode a = resolve(list.get(i), component, at + "/" + i);
                if (a != null) {
                    known(a, ACTION_KEYS, at + "/" + i);
                    out.add(a);
                }
            }
            return out;
        }

        /** A list entry, with a reusable {@code {reference: "$components.<kind>.<name>"}} resolved. */
        JsonNode resolve(JsonNode entry, String kind, String at) {
            if (!entry.isObject()) {
                error("ARAZZO-SHAPE", at, "an entry must be an object");
                return null;
            }
            if (!entry.has("reference")) {
                return entry;
            }
            known(entry, REUSABLE_KEYS, at);
            String ref = text(entry, "reference");
            String prefix = "$components." + kind + ".";
            JsonNode target = ref != null && ref.startsWith(prefix) ? components.path(kind).get(ref.substring(prefix.length()))
                    : null;
            if (target == null || !target.isObject()) {
                error("ARAZZO-UNKNOWN-REFERENCE", at, "reference " + ref + " does not name a " + kind + " component");
                return null;
            }
            if (entry.has("value")) {
                ObjectNode copy = ((ObjectNode) target).deepCopy();
                copy.set("value", entry.get("value"));
                return copy;
            }
            return target;
        }

        // ------------------------------------------------------------------ helpers

        void known(JsonNode node, Set<String> keys, String at) {
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                if (!keys.contains(k) && !k.startsWith("x-")) {
                    error("ARAZZO-UNKNOWN-FIELD", at + "/" + k, "field " + k + " is not known to this importer "
                            + "(an Arazzo 1.1 construct?) and is not converted");
                }
            }
        }

        String fresh(String wanted) {
            String id = wanted;
            for (int n = 2; ids.contains(id); n++) {
                id = wanted + "_" + n;
            }
            ids.add(id);
            return id;
        }

        void error(String code, String at, String message) {
            diag.add(new Diagnostic(Severity.ERROR, code, at, message));
        }

        void warn(String code, String at, String message) {
            diag.add(new Diagnostic(Severity.WARNING, code, at, message));
        }

        void info(String code, String at, String message) {
            diag.add(new Diagnostic(Severity.INFO, code, at, message));
        }
    }
}
