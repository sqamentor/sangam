// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.arazzo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.contract.ApiContractStore;
import org.sangam.integration.connector.contract.ContractOperations;
import org.sangam.integration.connector.contract.OperationRef;

/**
 * Owner decision 159 (M-069): the runtime of an Arazzo operation step, as the importer emits it (a service task on topic
 * {@value #TOPIC} with {@code sangam:operationRef} and the {@code arazzo.*} extension properties). It runs one pinned
 * OpenAPI operation of the package's API contract store through the HTTP connector configured for that contract, then
 * checks the success criteria and evaluates the outputs. No Arazzo conformance claim (see {@link ArazzoExpressions}
 * for the supported subset).
 *
 * <p>Input (filled by the external-task glue from the task and the process): {@code operationRef}
 * ({@code contractId@version#operationId}; an unpinned ref is refused), {@code packageId}, {@code stepId},
 * {@code arazzo.request}, {@code arazzo.successCriteria}, {@code arazzo.outputs} (JSON text, as the importer writes
 * them) and {@code variables} (the process variables, for {@code $inputs.*} and {@code $steps.*}).
 *
 * <p>Request: the operation's method and path; {@code path} parameters fill {@code {name}} (URL-encoded), {@code query}
 * and {@code header} parameters go to the HTTP connector, {@code cookie} parameters become one {@code Cookie} header;
 * the request body is the payload with its expressions resolved and its {@code replacements} applied (JSON pointer
 * targets). AsyncAPI operations are refused.
 *
 * <p>Result: when the criteria hold (none = 2xx), success with {@code statusCode}, {@code outputs} and
 * {@code variables} ({@code steps.<stepId>.outputs.<name>} for each output, to be written as process variables);
 * otherwise a business failure {@code ARAZZO_CRITERIA_NOT_MET}. A criterion or expression this runtime cannot evaluate
 * is {@code ARAZZO_UNSUPPORTED}; transient and unknown outcomes of the HTTP call are passed on unchanged.
 */
public final class ArazzoOperationStep implements SangamConnector {

    public static final String TYPE = "arazzo";
    public static final String TOPIC = "sangam.operation";
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^{}]+)}");

    private final String id;
    private final ApiContractStore contracts;
    private final Function<String, SangamConnector> httpByContract;
    private final ConnectorDescriptor descriptor;

    /**
     * @param contracts      the API contract store
     * @param httpByContract the HTTP connector configured for a contract id (its base URL, credentials and egress);
     *                       null for a contract without one
     */
    public ArazzoOperationStep(String id, ApiContractStore contracts, Function<String, SangamConnector> httpByContract,
                               ConnectorProfile profile) {
        this.id = Objects.requireNonNull(id, "id");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.httpByContract = Objects.requireNonNull(httpByContract, "httpByContract");
        this.descriptor = new ConnectorDescriptor(id, TYPE, CapabilityClass.C_NEITHER, List.of(),
                profile == null ? ConnectorProfile.LIVE : profile);
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        return new LookupResult.NotSupported();
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        OperationRef ref;
        ContractOperations.Operation op;
        try {
            ref = OperationRef.parse(request.required("operationRef"));
            if (ref.version() == null) {
                return ConnectorResult.BusinessFailure.of("OPERATION_REF_NOT_PINNED",
                        "the operationRef must be pinned (contractId@version#operationId): " + ref);
            }
            op = ContractOperations.resolve(contracts, request.tenantId(), request.required("packageId"), ref);
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("OPERATION_REF_INVALID", e.getMessage());
        }
        if (op.format() != ApiContractStore.Format.OPENAPI) {
            return ConnectorResult.BusinessFailure.of("ARAZZO_UNSUPPORTED", "only OpenAPI operations run as Arazzo steps");
        }
        SangamConnector http = httpByContract.apply(ref.contractId());
        if (http == null) {
            return ConnectorResult.BusinessFailure.of("CONNECTOR_NOT_CONFIGURED",
                    "no HTTP connector is configured for contract " + ref.contractId());
        }
        Map<String, Object> variables = request.map("variables");
        Map<String, Object> callInput;
        List<Map<String, Object>> criteria;
        Map<String, Object> outputs;
        try {
            callInput = call(op, map(json(request.str("arazzo.request"), Map.class)),
                    new ArazzoExpressions.Scope(null, null, variables));
            criteria = list(json(request.str("arazzo.successCriteria"), List.class));
            outputs = map(json(request.str("arazzo.outputs"), Map.class));
        } catch (ArazzoExpressions.Unsupported e) {
            return ConnectorResult.BusinessFailure.of("ARAZZO_UNSUPPORTED", e.getMessage());
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
        }
        ConnectorResult r = http.invoke(request.withInput(callInput));
        Map<String, Object> response;
        if (r instanceof ConnectorResult.Success s) {
            response = s.output();
        } else if (r instanceof ConnectorResult.BusinessFailure f && f.output().get("status") instanceof Number) {
            response = f.output();   // an HTTP 4xx answer: the criteria decide (for example $statusCode == 404)
        } else {
            return r;
        }
        Integer status = response.get("status") instanceof Number n ? n.intValue() : null;
        ArazzoExpressions.Scope scope = new ArazzoExpressions.Scope(status, response.get("body"), variables);
        Map<String, Object> out = new LinkedHashMap<>();
        if (status != null) {
            out.put("statusCode", status);
        }
        try {
            if (!ArazzoExpressions.criteriaHold(criteria, scope)) {
                if (response.get("body") != null) {
                    out.put("body", response.get("body"));
                }
                return new ConnectorResult.BusinessFailure("ARAZZO_CRITERIA_NOT_MET",
                        "the success criteria of " + ref + " do not hold (HTTP " + status + ")", out);
            }
            Map<String, Object> values = new LinkedHashMap<>();
            Map<String, Object> vars = new LinkedHashMap<>();
            String stepId = request.str("stepId");
            for (Map.Entry<String, Object> o : outputs.entrySet()) {
                Object v = ArazzoExpressions.resolve(o.getValue(), scope);
                if (v != null) {
                    values.put(o.getKey(), v);
                    if (stepId != null && !stepId.isBlank()) {
                        vars.put("steps." + stepId + ".outputs." + o.getKey(), v);
                    }
                }
            }
            out.put("outputs", values);
            out.put("variables", vars);
        } catch (ArazzoExpressions.Unsupported e) {
            return ConnectorResult.BusinessFailure.of("ARAZZO_UNSUPPORTED", e.getMessage());
        }
        return new ConnectorResult.Success(out, r instanceof ConnectorResult.Success s ? s.providerRef() : null, false);
    }

    /** The HTTP connector input of one step: method, path with parameters filled, query, headers and body. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> call(ContractOperations.Operation op, Map<String, Object> request,
                                    ArazzoExpressions.Scope scope) {
        Map<String, Object> req = request == null ? Map.of() : request;
        Map<String, String> path = new LinkedHashMap<>();
        Map<String, Object> query = new LinkedHashMap<>();
        Map<String, Object> headers = new LinkedHashMap<>();
        List<String> cookies = new ArrayList<>();
        Object params = req.get("parameters");
        if (params != null && !(params instanceof List<?>)) {
            throw new IllegalArgumentException("arazzo.request parameters must be a list");
        }
        for (Object p : params == null ? List.of() : (List<?>) params) {
            if (!(p instanceof Map<?, ?> pm) || !(pm.get("name") instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("each parameter needs a name");
            }
            Object value = ArazzoExpressions.resolve(pm.get("value"), scope);
            String in = String.valueOf(pm.get("in"));
            switch (in) {
                case "path" -> path.put(name, value == null ? null : ArazzoExpressions.text(value));
                case "query" -> query.put(name, value);
                case "header" -> headers.put(name, value == null ? "" : ArazzoExpressions.text(value));
                case "cookie" -> cookies.add(name + "=" + (value == null ? "" : ArazzoExpressions.text(value)));
                default -> throw new IllegalArgumentException("parameter " + name + ": 'in' must be path, query, header "
                        + "or cookie");
            }
        }
        if (!cookies.isEmpty()) {
            headers.put("Cookie", String.join("; ", cookies));
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("method", op.method());
        in.put("path", fillPath(op.path(), path));
        if (!query.isEmpty()) {
            in.put("query", query);
        }
        Object body = req.get("requestBody");
        if (body instanceof Map<?, ?> rb) {
            Map<String, Object> b = (Map<String, Object>) rb;
            Object payload = ArazzoExpressions.resolve(b.get("payload"), scope);
            payload = replace(payload, b.get("replacements"), scope);
            if (b.get("contentType") instanceof String ct && !ct.isBlank()) {
                headers.put("Content-Type", ct);
            }
            if (payload != null) {
                in.put("body", payload);
            }
        } else if (body != null) {
            throw new IllegalArgumentException("arazzo.request requestBody must be an object");
        }
        if (!headers.isEmpty()) {
            in.put("headers", headers);
        }
        return in;
    }

    static String fillPath(String template, Map<String, String> values) {
        Matcher m = PATH_PARAM.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null) {
                throw new IllegalArgumentException("path parameter " + m.group(1) + " has no value");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(URLEncoder.encode(v, StandardCharsets.UTF_8)
                    .replace("+", "%20")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Applies {@code replacements} ({@code target} = JSON pointer, {@code value}) to a JSON payload. */
    private static Object replace(Object payload, Object replacements, ArazzoExpressions.Scope scope) {
        if (replacements == null) {
            return payload;
        }
        if (!(replacements instanceof List<?> list)) {
            throw new IllegalArgumentException("replacements must be a list");
        }
        if (list.isEmpty()) {
            return payload;
        }
        JsonNode root = Json.MAPPER.valueToTree(payload);
        if (root == null || !root.isContainerNode()) {
            throw new ArazzoExpressions.Unsupported("replacements need a JSON object or array payload");
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> r) || !(r.get("target") instanceof String target) || !target.startsWith("/")) {
                throw new ArazzoExpressions.Unsupported("a replacement target must be a JSON pointer");
            }
            JsonPointer p = JsonPointer.compile(target);
            JsonNode parent = root.at(p.head());
            JsonNode value = Json.MAPPER.valueToTree(ArazzoExpressions.resolve(r.get("value"), scope));
            String last = p.last().getMatchingProperty();
            if (parent instanceof ObjectNode obj) {
                obj.set(last, value);
            } else if (parent.isArray() && p.last().mayMatchElement()
                    && p.last().getMatchingIndex() < parent.size()) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) parent).set(p.last().getMatchingIndex(), value);
            } else {
                throw new IllegalArgumentException("replacement target " + target + " does not exist in the payload");
            }
        }
        return Json.MAPPER.convertValue(root, Object.class);
    }

    private static Object json(String text, Class<?> type) {
        if (text == null || text.isBlank()) {
            return null;
        }
        JsonNode n = Json.tree(text);
        if (n == null) {
            throw new IllegalArgumentException("not JSON: " + type.getSimpleName());
        }
        Object v = Json.MAPPER.convertValue(n, Object.class);
        if (!type.isInstance(v)) {
            throw new IllegalArgumentException("expected a JSON " + (type == Map.class ? "object" : "array"));
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object v) {
        if (v == null) {
            return List.of();
        }
        for (Object o : (List<?>) v) {
            if (!(o instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("each success criterion must be an object");
            }
        }
        return (List<Map<String, Object>>) v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return v == null ? Map.of() : (Map<String, Object>) v;
    }

    @Override
    public String toString() {
        return "ArazzoOperationStep[" + id + "]";
    }
}
