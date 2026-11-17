// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** M-067/M-068/M-072: the built-in connector element templates. */
class ConnectorTemplatesTest {

    @Test
    void everyBuiltInTemplateIsAValidElementTemplateWithAUniqueId() {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> d : ConnectorTemplates.documents()) {
            assertTrue(ElementTemplates.validate(ElementTemplates.Kind.ELEMENT, d).isEmpty(), String.valueOf(d.get("id")));
            assertTrue(ids.add(String.valueOf(d.get("id"))), "duplicate " + d.get("id"));
        }
        assertEquals(Set.of(ConnectorTemplates.AWS_LAMBDA, ConnectorTemplates.AZURE_FUNCTION, ConnectorTemplates.GCP_CLOUD_RUN,
                ConnectorTemplates.ASYNC_SERVERLESS, "sangam.connector.digilocker-requester",
                ConnectorTemplates.OPERATION_REF), ids);
    }

    @Test
    void applyingTheLambdaTemplateBindsAnExternalTaskOnATopic() {
        ElementTemplates.Template t = ElementTemplates.of(ElementTemplates.Kind.ELEMENT, ConnectorTemplates.documents().get(0));
        ElementTemplates.Applied a = ElementTemplates.apply(t, "bpmn:ServiceTask",
                Map.of("topic", "lambda.greet", "path", "/2015-03-31/functions/greet/invocations"));
        assertTrue(a.errors().isEmpty(), a.errors().toString());
        assertEquals("external", a.properties().get("operaton:type"));
        assertEquals("lambda.greet", a.properties().get("operaton:topic"));
        assertEquals("/2015-03-31/functions/greet/invocations", a.properties().get("input:path"));
        assertFalse(ElementTemplates.apply(t, "bpmn:ServiceTask", Map.of("topic", "x", "path", "/elsewhere"))
                .errors().isEmpty(), "the path pattern is enforced");
        assertFalse(ElementTemplates.apply(t, "bpmn:ServiceTask", Map.of()).errors().isEmpty(), "the topic is required");
    }

    @Test
    void theOperationRefTemplateWritesTheSangamAttribute() {
        Map<String, Object> doc = ConnectorTemplates.documents().stream()
                .filter(d -> ConnectorTemplates.OPERATION_REF.equals(d.get("id"))).findFirst().orElseThrow();
        ElementTemplates.Template t = ElementTemplates.of(ElementTemplates.Kind.ELEMENT, doc);
        ElementTemplates.Applied ok = ElementTemplates.apply(t, "bpmn:ServiceTask",
                Map.of("topic", "api.pets", "operationRef", "pets@2#getPet"));
        assertTrue(ok.errors().isEmpty(), ok.errors().toString());
        assertEquals("pets@2#getPet", ok.properties().get("sangam:operationRef"));
        assertFalse(ElementTemplates.apply(t, "bpmn:ServiceTask", Map.of("topic", "api.pets", "operationRef", "pets"))
                .errors().isEmpty());
    }

    @Test
    void noTemplateCarriesACredentialField() {
        for (Map<String, Object> d : ConnectorTemplates.documents()) {
            for (Object f : (List<?>) d.get("fields")) {
                String name = String.valueOf(((Map<?, ?>) f).get("name")).toLowerCase(java.util.Locale.ROOT);
                assertFalse(name.contains("secret") || name.contains("password") || name.contains("key"), name);
            }
        }
    }

    @Test
    void sameIgnoresTheStoredVersionNumber() {
        Map<String, Object> d = ConnectorTemplates.documents().get(1);
        Map<String, Object> stored = new java.util.LinkedHashMap<>(d);
        stored.put("version", 7);
        assertTrue(ConnectorTemplates.same(stored, d));
        stored.put("description", "changed");
        assertFalse(ConnectorTemplates.same(stored, d));
    }

    /**
     * SL-104: the templates moved from product Java to the data resource. The catalogue still serves exactly the
     * documents the Java builder produced (same ids, order, names, descriptions and fields), so {@code install} sees
     * no difference and publishes no new version on an existing installation.
     */
    @Test
    void theDataCatalogueServesExactlyTheFormerBuiltInDocuments() {
        List<Map<String, Object>> expected = formerBuiltIns();
        List<Map<String, Object>> actual = ConnectorTemplates.documents();
        assertEquals(expected, actual);
        for (int i = 0; i < expected.size(); i++) {
            assertTrue(ConnectorTemplates.same(expected.get(i), actual.get(i)), String.valueOf(expected.get(i).get("id")));
            assertEquals(String.valueOf(expected.get(i)), String.valueOf(actual.get(i)), "same key and field order");
        }
    }

    @Test
    void documentsAreFreshCopies() {
        ConnectorTemplates.documents().get(0).put("name", "changed by a caller");
        assertEquals("AWS Lambda function", ConnectorTemplates.documents().get(0).get("name"));
    }

    @Test
    void aMissingCatalogueIsRefusedByName() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConnectorTemplates.read("/org/sangam/modeller/template/no-such-catalogue.json"));
        assertTrue(e.getMessage().contains("no-such-catalogue.json"), e.getMessage());
    }

    /** The documents as {@code ConnectorTemplates} built them in Java before SL-104 moved them to data. */
    private static List<Map<String, Object>> formerBuiltIns() {
        List<Map<String, Object>> d = new ArrayList<>();
        d.add(doc("sangam.connector.aws-lambda", "AWS Lambda function",
                "Invokes a Lambda function through the HTTP connector (auth.type=aws-sigv4, service lambda). "
                        + "Path /2015-03-31/functions/{function}/invocations; header X-Amz-Invocation-Type selects "
                        + "RequestResponse or Event. A function error (X-Amz-Function-Error) is a business failure "
                        + "FUNCTION_ERROR when response.failure-header is set.",
                List.of(input("path", false, "/2015-03-31/functions/[A-Za-z0-9_:.-]{1,170}/invocations", null),
                        input("headers", false, null, null), input("body", false, null, null))));
        d.add(doc("sangam.connector.azure-function", "Azure Function (HTTP trigger)",
                "Calls an HTTP-triggered Azure Function through the HTTP connector (auth.type=function-key; the key is a "
                        + "secret:// reference in the connector configuration).",
                List.of(input("path", false, "/api/[A-Za-z0-9_./-]{1,200}", null), input("query", false, null, null),
                        input("body", false, null, null))));
        d.add(doc("sangam.connector.gcp-cloud-run", "Google Cloud Run / Cloud Functions",
                "Calls a Cloud Run service or a 2nd-gen function through the HTTP connector (auth.type=gcp-id-token; the "
                        + "audience is the service URL).",
                List.of(input("path", false, "/[A-Za-z0-9_./-]{0,200}", null), input("query", false, null, null),
                        input("body", false, null, null))));
        d.add(doc("sangam.connector.async-serverless", "Serverless function, asynchronous",
                "Starts a function and continues on its callback: the connector adds the callback (endpoint.callback.url, "
                        + "a /events/by-channel?channel= inbound webhook) and the correlation key (the operation id) to the request. "
                        + "Model a message catch event after this task correlated on the correlation key.",
                List.of(input("path", false, "/[A-Za-z0-9_./-]{0,200}", null), input("headers", false, null, null),
                        input("body", false, null, null))));
        d.add(doc("sangam.connector.digilocker-requester", "DigiLocker: verify adult / pull document",
                "Pull with consent from DigiLocker. verify-adult answers identifiable, adult, issuer and a token reference "
                        + "(never the date of birth); pull lists the issued documents and fetches those named in uris.The consent code and "
                        + "PKCE verifier come from the consent step of the case.",
                List.of(input("code", true, null, null), input("codeVerifier", true, null, null),
                        input("uris", false, null, null))));
        d.add(doc("sangam.connector.operation-ref", "API operation (contract reference)",
                "Calls an operation of an OpenAPI or AsyncAPI document stored in the package: sangam:operationRef names it "
                        + "as contractId#operationId; the request body is validated against the operation schema.",
                List.of(field("operationRef", "sangam:operationRef", true,
                                "[A-Za-z0-9_.-]{1,120}(@[0-9]{1,9})?#[A-Za-z0-9_.-]{1,200}", null, null),
                        input("body", false, null, null))));
        return d;
    }

    private static Map<String, Object> doc(String id, String name, String description, List<Map<String, Object>> extra) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("type", "operaton:type", true, "external", "external", List.of("external")));
        fields.add(field("topic", "operaton:topic", true, "[A-Za-z0-9._:-]{1,200}", null, null));
        fields.addAll(extra);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("appliesTo", List.of("bpmn:ServiceTask"));
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
