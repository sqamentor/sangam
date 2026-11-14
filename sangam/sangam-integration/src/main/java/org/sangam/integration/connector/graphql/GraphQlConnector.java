// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.graphql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.http.HttpRestConnector;

/**
 * M-105 GraphQL connector (R71) through the connector SPI. It adds the GraphQL-over-HTTP request and answer shape to a
 * LIVE {@link HttpRestConnector} built by {@code LiveHttpConnectors} for the GraphQL endpoint, so the endpoint gets every
 * property of the HTTP connector unchanged: tenant egress policy on every hop, credentials by secret reference (Basic,
 * Bearer, OAuth2 client credentials, mTLS, SigV4), the operationId as idempotency header, timeouts, the L2 lock rule and
 * the no-effect/unknown classification. There is no GraphQL library: a GraphQL request is a JSON POST.
 *
 * <p><b>Request.</b> A {@code POST} of {@code {"query", "variables", "operationName"}} to the endpoint. When the
 * connector is configured with named {@code documents}, the input {@code document} names one of them and a free
 * {@code query} is refused, so only reviewed operations can run; without documents the input {@code query} is the
 * operation. {@code variables} is a map; {@code operationName} is optional; request {@code headers} follow the HTTP
 * connector's rules (credentials and Sangam headers never come from the process).
 *
 * <p><b>Answer.</b> A 2xx JSON body without {@code errors} is a success with {@code data} (and {@code extensions} when
 * present). A body with a non-empty {@code errors} list is a business failure {@code GRAPHQL_ERRORS}: the server
 * processed the request, so it is never retried blindly; the output keeps {@code data} (a partial result) and
 * {@code errors}. A 2xx body that is not a JSON object is {@code GRAPHQL_INVALID_RESPONSE}. Everything else (4xx, 5xx,
 * transport loss) is the HTTP connector's mapping, which also gives the GraphQL-over-HTTP 4xx answers their
 * {@code HTTP_<status>} code. The capability class is the endpoint's declared class (default (c): a mutation is not
 * repeated without proof); the lookup is the HTTP connector's {@code lookup-path} when one is configured.
 */
public final class GraphQlConnector implements SangamConnector {

    private final HttpRestConnector http;
    private final Map<String, String> documents;
    private final ConnectorDescriptor descriptor;

    /**
     * @param http      the HTTP connector of the GraphQL endpoint (its base URL is the endpoint URL)
     * @param documents reviewed operations by name; empty to accept the input {@code query}
     */
    public GraphQlConnector(HttpRestConnector http, Map<String, String> documents) {
        this.http = Objects.requireNonNull(http, "http");
        this.documents = documents == null ? Map.of() : Map.copyOf(documents);
        this.documents.forEach((name, doc) -> {
            if (doc == null || doc.isBlank()) {
                throw new IllegalArgumentException("GraphQL document '" + name + "' is empty");
            }
        });
        ConnectorDescriptor d = http.descriptor();
        this.descriptor = new ConnectorDescriptor(d.id(), "graphql", d.capabilityClass(),
                this.documents.isEmpty() ? List.of() : List.copyOf(this.documents.keySet()), d.profile());
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        Map<String, Object> body;
        try {
            body = requestBody(r);
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("method", "POST");
        in.put("path", "");
        in.put("body", body);
        if (!r.map("headers").isEmpty()) {
            in.put("headers", r.map("headers"));
        }
        return answer(http.invoke(r.withInput(in)));
    }

    @Override
    public LookupResult lookup(ConnectorRequest r) {
        return http.lookup(r);
    }

    @Override
    public boolean healthy() {
        return http.healthy();
    }

    /** The GraphQL request body of one call. */
    Map<String, Object> requestBody(ConnectorRequest r) {
        String query;
        if (!documents.isEmpty()) {
            if (r.input().get("query") != null) {
                throw new IllegalArgumentException("this GraphQL connector runs only its configured documents; "
                        + "name one with 'document' instead of sending 'query'");
            }
            String name = r.required("document");
            query = documents.get(name);
            if (query == null) {
                throw new IllegalArgumentException("unknown GraphQL document '" + name + "' (configured: "
                        + documents.keySet() + ")");
            }
        } else {
            query = r.required("query");
        }
        Object variables = r.input().get("variables");
        if (variables != null && !(variables instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("GraphQL variables must be a map");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("variables", variables == null ? Map.of() : variables);
        String op = r.str("operationName");
        if (op != null && !op.isBlank()) {
            body.put("operationName", op);
        }
        return body;
    }

    /** Maps the HTTP connector's result onto the GraphQL answer (errors in a 2xx body are a failure). */
    static ConnectorResult answer(ConnectorResult result) {
        if (!(result instanceof ConnectorResult.Success s)) {
            return result;
        }
        Object body = s.output().get("body");
        if (!(body instanceof Map<?, ?> m)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", s.output().get("status"));
            return new ConnectorResult.BusinessFailure("GRAPHQL_INVALID_RESPONSE",
                    "the GraphQL endpoint answered 2xx without a JSON object", out);
        }
        // connector outputs are copied with Map.copyOf, which refuses null values: absent fields stay absent
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", s.output().get("status"));
        if (m.get("data") != null) {
            out.put("data", m.get("data"));
        }
        if (m.get("extensions") != null) {
            out.put("extensions", m.get("extensions"));
        }
        if (s.output().get("mapped") != null) {
            out.put("mapped", s.output().get("mapped"));
        }
        Object errors = m.get("errors");
        if (errors instanceof List<?> l && !l.isEmpty()) {
            out.put("errors", l);
            return new ConnectorResult.BusinessFailure("GRAPHQL_ERRORS", firstMessage(l), out);
        }
        return new ConnectorResult.Success(out, s.providerRef(), s.duplicate());
    }

    private static String firstMessage(List<?> errors) {
        Object first = errors.get(0);
        String msg = first instanceof Map<?, ?> e && e.get("message") != null ? String.valueOf(e.get("message"))
                : String.valueOf(first);
        msg = msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
        return errors.size() == 1 ? msg : msg + " (and " + (errors.size() - 1) + " more)";
    }
}
