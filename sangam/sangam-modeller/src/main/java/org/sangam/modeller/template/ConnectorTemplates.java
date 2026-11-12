// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Built-in ELEMENT templates for the connector families of M-067 (serverless functions over the generic HTTP connector),
 * M-072 (DigiLocker requester) and M-068 (API by contract reference). Every template binds a BPMN service task as an
 * external task ({@code operaton:type=external}) on a topic; the topic is mapped to {@code connector:operation} by
 * {@code sangam.integration.topics}, and the {@code input:*} fields become the connector request input
 * ({@code path}, {@code query}, {@code headers}, {@code body} of the HTTP connector). The provider auth (SigV4, function
 * key, GCP ID token) and the async callback are connector configuration ({@code sangam.connectors.http.*}), never template
 * values, so no credential is ever written into a model.
 * <p>
 * {@link #install} publishes a template only when its stored latest version differs from the built-in document, so a
 * repeated start never creates a new version.
 */
public final class ConnectorTemplates {

    public static final String AWS_LAMBDA = "sangam.connector.aws-lambda";
    public static final String AZURE_FUNCTION = "sangam.connector.azure-function";
    public static final String GCP_CLOUD_RUN = "sangam.connector.gcp-cloud-run";
    public static final String ASYNC_SERVERLESS = "sangam.connector.async-serverless";
    public static final String DIGILOCKER_REQUESTER = "sangam.connector.digilocker-requester";
    public static final String OPERATION_REF = "sangam.connector.operation-ref";

    private static final String SERVICE_TASK = "bpmn:ServiceTask";
    private static final String TOPIC_PATTERN = "[A-Za-z0-9._:-]{1,200}";

    private ConnectorTemplates() {
    }

    /** The built-in template documents, in palette order (fresh copies). */
    public static List<Map<String, Object>> documents() {
        List<Map<String, Object>> d = new ArrayList<>();
        d.add(doc(AWS_LAMBDA, "AWS Lambda function",
                "Invokes a Lambda function through the HTTP connector (auth.type=aws-sigv4, service lambda). "
                        + "Path /2015-03-31/functions/{function}/invocations; header X-Amz-Invocation-Type selects "
                        + "RequestResponse or Event. A function error (X-Amz-Function-Error) is a business failure "
                        + "FUNCTION_ERROR when response.failure-header is set.",
                List.of(input("path", false, "/2015-03-31/functions/[A-Za-z0-9_:.-]{1,170}/invocations", null),
                        input("headers", false, null, null), input("body", false, null, null))));
        d.add(doc(AZURE_FUNCTION, "Azure Function (HTTP trigger)",
                "Calls an HTTP-triggered Azure Function through the HTTP connector (auth.type=function-key; the key is a "
                        + "secret:// reference in the connector configuration).",
                List.of(input("path", false, "/api/[A-Za-z0-9_./-]{1,200}", null), input("query", false, null, null),
                        input("body", false, null, null))));
        d.add(doc(GCP_CLOUD_RUN, "Google Cloud Run / Cloud Functions",
                "Calls a Cloud Run service or a 2nd-gen function through the HTTP connector (auth.type=gcp-id-token; the "
                        + "audience is the service URL).",
                List.of(input("path", false, "/[A-Za-z0-9_./-]{0,200}", null), input("query", false, null, null),
                        input("body", false, null, null))));
        d.add(doc(ASYNC_SERVERLESS, "Serverless function, asynchronous",
                "Starts a function and continues on its callback: the connector adds the callback (endpoint.callback.url, "
                        + "a /events/{channel} inbound webhook) and the correlation key (the operation id) to the request. "
                        + "Model a message catch event after this task correlated on the correlation key.",
                List.of(input("path", false, "/[A-Za-z0-9_./-]{0,200}", null), input("headers", false, null, null),
                        input("body", false, null, null))));
        d.add(doc(DIGILOCKER_REQUESTER, "DigiLocker: verify adult / pull document",
                "Pull with consent from DigiLocker. verify-adult answers identifiable, adult, issuer and a token reference "
                        + "(never the date of birth); pull lists the issued documents and fetches those named in uris.The consent code and "
                        + "PKCE verifier come from the consent step of the case.",
                List.of(input("code", true, null, null), input("codeVerifier", true, null, null),
                        input("uris", false, null, null))));
        d.add(doc(OPERATION_REF, "API operation (contract reference)",
                "Calls an operation of an OpenAPI or AsyncAPI document stored in the package: sangam:operationRef names it "
                        + "as contractId#operationId; the request body is validated against the operation schema.",
                List.of(field("operationRef", "sangam:operationRef", true,
                                "[A-Za-z0-9_.-]{1,120}(@[0-9]{1,9})?#[A-Za-z0-9_.-]{1,200}", null, null),
                        input("body", false, null, null))));
        return d;
    }

    /**
     * Publishes each built-in template for {@code tenantId} unless its latest stored version already carries the same
     * fields, name and description. Returns the ids published by this call.
     */
    public static List<String> install(ElementTemplates templates, String tenantId, String actor) {
        List<String> published = new ArrayList<>();
        for (Map<String, Object> doc : documents()) {
            String id = String.valueOf(doc.get("id"));
            Optional<ElementTemplates.Template> current = templates.find(tenantId, id, null);
            if (current.isPresent() && same(current.get().document(), doc)) {
                continue;
            }
            templates.publish(tenantId, ElementTemplates.Kind.ELEMENT, doc, actor);
            published.add(id);
        }
        return published;
    }

    static boolean same(Map<String, Object> stored, Map<String, Object> builtIn) {
        for (String k : List.of("name", "description", "appliesTo", "fields")) {
            if (!Objects.equals(String.valueOf(stored.get(k)), String.valueOf(builtIn.get(k)))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> doc(String id, String name, String description, List<Map<String, Object>> extra) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("type", "operaton:type", true, "external", "external", List.of("external")));
        fields.add(field("topic", "operaton:topic", true, TOPIC_PATTERN, null, null));
        fields.addAll(extra);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("appliesTo", List.of(SERVICE_TASK));
        m.put("fields", fields);
        return m;
    }

    private static Map<String, Object> input(String name, boolean required, String pattern, Object def) {
        return field(name, "input:" + name, required, pattern, def, null);
    }

    private static Map<String, Object> field(String name, String binding, boolean required, String pattern, Object def,
                                             List<String> choices) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("binding", binding);
        f.put("required", required);
        if (pattern != null) {
            f.put("pattern", pattern);
        }
        if (def != null) {
            f.put("default", def);
        }
        if (choices != null) {
            f.put("choices", choices);
        }
        return f;
    }
}
