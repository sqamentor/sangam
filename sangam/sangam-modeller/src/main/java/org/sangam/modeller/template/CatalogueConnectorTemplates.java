// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.template;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.modeller.catalogue.ExtensionCatalogue;
import org.sangam.modeller.catalogue.ExtensionCatalogue.CatalogueException;

/**
 * Owner decision 380 P2 (b): the artefact of a catalogue entry of kind
 * {@link ExtensionCatalogue#KIND_CONNECTOR_TEMPLATE}. It is data only, in the format of the built-in connector
 * templates ({@code {"templates": [ {id, name, description, appliesTo, fields} ]}}, see {@link ConnectorTemplates}):
 * no code is ever staged (P2 (c)), and a template carries no credential (provider auth stays connector configuration).
 *
 * <p>{@link #read} refuses, with {@code CATALOGUE_ARTIFACT_INVALID}, an artefact that is not such a document, an
 * empty or oversized list, a template that is not a valid ELEMENT template ({@link ElementTemplates#check}), a
 * duplicate id, a field named like a credential ({@link #CREDENTIAL_WORDS}), or an id outside the entry's namespace: every template id is the entry id or starts with the entry id
 * and a dot, so a catalogue entry can never replace a built-in template ({@code sangam.connector.*}) or a template of
 * another entry. {@link #install} publishes, in the caller's tenant and in one transaction with the caller's audit
 * work, only the templates whose latest stored version differs, so a repeated install writes nothing.
 */
public final class CatalogueConnectorTemplates {

    /** At most this many templates in one entry. */
    public static final int MAX_TEMPLATES = 200;

    /** A field whose name contains one of these is refused: a credential is never a template value. */
    static final List<String> CREDENTIAL_WORDS = List.of("secret", "password", "passwd", "token", "credential", "apikey", "api_key", "privatekey");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {
    };

    /** What one install did: the template ids published (a new version each) and those already current. */
    public record Installed(List<ElementTemplates.Template> published, List<String> unchanged) {
    }

    private CatalogueConnectorTemplates() {
    }

    /** The checked template documents of the artefact of entry {@code entryId}, in their order. */
    public static List<Map<String, Object>> read(String entryId, byte[] artifact) {
        Map<String, Object> root;
        try {
            root = JSON.readValue(artifact, DOCUMENT);
        } catch (IOException | RuntimeException e) {
            throw invalid(entryId, "the artefact is not a JSON object");
        }
        if (root == null || !(root.get("templates") instanceof List<?> list) || list.isEmpty()) {
            throw invalid(entryId, "the artefact has no non-empty templates list");
        }
        if (list.size() > MAX_TEMPLATES) {
            throw invalid(entryId, "the artefact has " + list.size() + " templates; at most " + MAX_TEMPLATES);
        }
        Set<String> ids = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?>)) {
                throw invalid(entryId, "every template is an object");
            }
            Map<String, Object> doc = new LinkedHashMap<>(JSON.convertValue(o, DOCUMENT));
            doc.remove("version");
            String id = String.valueOf(doc.get("id"));
            try {
                ElementTemplates.check(ElementTemplates.Kind.ELEMENT, doc);
            } catch (IllegalArgumentException e) {
                throw invalid(entryId, "template " + id + ": " + e.getMessage());
            }
            if (!id.equals(entryId) && !id.startsWith(entryId + ".")) {
                throw invalid(entryId, "template id " + id + " is outside the entry's namespace (" + entryId + " or "
                        + entryId + ".*)");
            }
            if (doc.get("fields") instanceof List<?> fields) {
                for (Object f : fields) {
                    String name = String.valueOf(((Map<?, ?>) f).get("name")).toLowerCase(java.util.Locale.ROOT);
                    if (CREDENTIAL_WORDS.stream().anyMatch(name::contains)) {
                        throw invalid(entryId, "template " + id + ": field " + name
                                + " looks like a credential; credentials are connector configuration, never template values");
                    }
                }
            }
            if (!ids.add(id)) {
                throw invalid(entryId, "duplicate template id " + id);
            }
            out.add(doc);
        }
        return out;
    }

    /**
     * Publishes the templates of {@code documents} that differ from their latest stored version in {@code tenantId};
     * {@code inSameTransaction} runs with the published templates before that transaction commits. Nothing is written,
     * and {@code inSameTransaction} does not run, when every template is current.
     */
    public static Installed install(ElementTemplates templates, String tenantId, String actor, List<Map<String, Object>> documents,
                                    Consumer<List<ElementTemplates.Template>> inSameTransaction) {
        List<Map<String, Object>> changed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        for (Map<String, Object> doc : documents) {
            String id = String.valueOf(doc.get("id"));
            Optional<ElementTemplates.Template> current = templates.find(tenantId, id, null);
            if (current.isPresent() && current.get().kind() == ElementTemplates.Kind.ELEMENT
                    && ConnectorTemplates.same(current.get().document(), doc)) {
                unchanged.add(id);
            } else {
                changed.add(doc);
            }
        }
        if (changed.isEmpty()) {
            return new Installed(List.of(), List.copyOf(unchanged));
        }
        return new Installed(templates.publishAll(tenantId, ElementTemplates.Kind.ELEMENT, changed, actor, inSameTransaction),
                List.copyOf(unchanged));
    }

    private static CatalogueException invalid(String entryId, String detail) {
        return new CatalogueException("CATALOGUE_ARTIFACT_INVALID", entryId + ": " + detail);
    }
}
