// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.template;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Built-in ELEMENT templates for the connector families of M-067 (serverless functions over the generic HTTP connector),
 * M-072 (document-wallet requester) and M-068 (API by contract reference). Every template binds a BPMN service task as
 * an external task ({@code operaton:type=external}) on a topic; the topic is mapped to {@code connector:operation} by
 * {@code sangam.integration.topics}, and the {@code input:*} fields become the connector request input
 * ({@code path}, {@code query}, {@code headers}, {@code body} of the HTTP connector). The provider auth (SigV4, function
 * key, GCP ID token) and the async callback are connector configuration ({@code sangam.connectors.http.*}), never template
 * values, so no credential is ever written into a model.
 * <p>
 * The template documents are data (ADR-D-20, SL-104): they are read from the class-path resource {@link #RESOURCE},
 * in palette order, so a template for a named external system of one country is a line of data and never product
 * Java. A missing or unreadable resource fails loudly; there is no built-in fallback list.
 * <p>
 * {@link #install} publishes a template only when its stored latest version differs from the built-in document, so a
 * repeated start never creates a new version.
 */
public final class ConnectorTemplates {

    public static final String AWS_LAMBDA = "sangam.connector.aws-lambda";
    public static final String AZURE_FUNCTION = "sangam.connector.azure-function";
    public static final String GCP_CLOUD_RUN = "sangam.connector.gcp-cloud-run";
    public static final String ASYNC_SERVERLESS = "sangam.connector.async-serverless";
    public static final String OPERATION_REF = "sangam.connector.operation-ref";

    /** The catalogue of built-in template documents: {@code {"templates": [ {id, name, description, appliesTo, fields} ]}}. */
    static final String RESOURCE = "/org/sangam/modeller/template/connector-templates.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {
    };

    private ConnectorTemplates() {
    }

    /** The built-in template documents, in palette order (fresh copies). */
    public static List<Map<String, Object>> documents() {
        return read(RESOURCE);
    }

    /** Reads a template catalogue; refuses a missing, unreadable or empty one, naming the resource. */
    static List<Map<String, Object>> read(String resource) {
        Map<String, Object> catalogue;
        try (InputStream in = ConnectorTemplates.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("connector template catalogue " + resource + " is not on the class path");
            }
            catalogue = JSON.readValue(in, DOCUMENT);
        } catch (IOException e) {
            throw new IllegalStateException("connector template catalogue " + resource + " is not readable: "
                    + e.getMessage(), e);
        }
        if (!(catalogue.get("templates") instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("connector template catalogue " + resource + " has no templates list");
        }
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?>)) {
                throw new IllegalStateException("connector template catalogue " + resource
                        + ": every template is an object");
            }
            documents.add(JSON.convertValue(entry, DOCUMENT));
        }
        return documents;
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
}