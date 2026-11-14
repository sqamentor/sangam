// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * M-050: the AsyncAPI contract ({@code api/sangam-events.asyncapi.yaml}) agrees with the OpenAPI contracts and with
 * SANGAM.md X.4.0.3:
 * <ul>
 *   <li>the roll-up stream event is the same schema as the roll-up ingest body ({@code sangam-rollup-api.yaml});</li>
 *   <li>a cross-cell message binds to the target operation's request ({@code sangam-api.yaml} POST /messages);</li>
 *   <li>the webhook CloudEvents attributes are the REST CloudEvent's members or Sangam extensions;</li>
 *   <li>the roll-up type allow-list's history types are history events of the webhook contract;</li>
 *   <li>the audit event names are exactly the X.4.0.3 table, and none is both published and pending;</li>
 *   <li>every reference resolves.</li>
 * </ul>
 */
class AsyncApiConsistencyTest {

    private static final Path EVENTS = Path.of("api", "sangam-events.asyncapi.yaml");
    private static final Path SANGAM_MD = Path.of("..", "..", "SANGAM.md");

    @Test
    void rollupEventIsTheRollupIngestBody() throws IOException {
        Map<String, Object> events = AppContractDriftTest.read(EVENTS);
        Map<String, Object> rollup = AppContractDriftTest.read(Path.of("api", "sangam-rollup-api.yaml"));
        assertEquals(schemas(rollup).get("RollupCloudEvent"), schemas(events).get("RollupCloudEvent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void crossCellMessageBindsToCorrelateMessage() throws IOException {
        Map<String, Object> events = AppContractDriftTest.read(EVENTS);
        Map<String, Object> api = AppContractDriftTest.read(Path.of("api", "sangam-api.yaml"));
        Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) api.get("paths"))
                .get("/messages")).get("post");
        assertEquals("correlateMessage", post.get("operationId"));
        Map<String, Object> request = AppContractDriftTest.resolve(api, (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) post.get("requestBody")).get("content")).get("application/json"))
                .get("schema"));
        Set<String> accepted = ((Map<String, Object>) request.get("properties")).keySet();
        Set<String> sent = new TreeSet<>(((Map<String, Object>) schemas(events).get("CrossCellMessage")
                .get("properties")).keySet());
        sent.remove("operationId"); // travels as Idempotency-Key (CrossCellHeaders)
        assertTrue(accepted.containsAll(sent), "MessageRequest " + accepted + " must accept " + sent);
        Map<String, Object> headers = schemas(events).get("CrossCellHeaders");
        assertTrue(((Map<String, Object>) headers.get("properties")).containsKey("Idempotency-Key"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void webhookAttributesAreCloudEventMembersOrSangamExtensions() throws IOException {
        Map<String, Object> events = AppContractDriftTest.read(EVENTS);
        Map<String, Object> api = AppContractDriftTest.read(Path.of("api", "sangam-api.yaml"));
        Set<String> members = ((Map<String, Object>) schemas(api).get("CloudEvent").get("properties")).keySet();
        List<String> foreign = new ArrayList<>();
        for (String h : ((Map<String, Object>) schemas(events).get("WebhookHeaders").get("properties")).keySet()) {
            if (h.startsWith("ce-") && !members.contains(h.substring(3)) && !h.startsWith("ce-sangam")) {
                foreign.add(h);
            }
        }
        assertEquals(List.of(), foreign, "ce- attributes neither CloudEvent members nor sangam* extensions");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollupHistoryTypesAreWebhookHistoryEvents() throws IOException {
        Map<String, Object> messages = (Map<String, Object>) ((Map<String, Object>) AppContractDriftTest.read(EVENTS)
                .get("components")).get("messages");
        Pattern history = Pattern.compile((String) ((Map<String, Object>) messages.get("WebhookHistoryEvent"))
                .get("x-sangam-event-type-pattern"));
        for (String t : (List<String>) ((Map<String, Object>) messages.get("RollupEvent")).get("x-sangam-event-types")) {
            if (t.startsWith("sangam.history.")) {
                assertTrue(history.matcher(t).matches(), t);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void auditNamesAreTheX403Table() throws IOException {
        Map<String, Object> names = schemas(AppContractDriftTest.read(EVENTS)).get("AuditEventName");
        List<String> published = (List<String>) names.get("enum");
        assertEquals(published.size(), new TreeSet<>(published).size(), "duplicate audit names");
        assertEquals(x403(), published, "AuditEventName must list SANGAM.md X.4.0.3 in table order");
        for (List<String> p : ((Map<String, List<String>>) names.get("x-sangam-pending-change-control")).values()) {
            for (String n : p) {
                assertTrue(!published.contains(n), n + " is both published and pending");
            }
        }
    }

    @Test
    void everyReferenceResolves() throws IOException {
        Map<String, Object> events = AppContractDriftTest.read(EVENTS);
        String text = Files.readString(EVENTS, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\$ref: '#/([^']+)'").matcher(text);
        int refs = 0;
        while (m.find()) {
            Object at = events;
            for (String part : m.group(1).split("/")) {
                at = at instanceof Map<?, ?> map ? map.get(part) : null;
            }
            assertNotNull(at, "unresolved $ref #/" + m.group(1));
            refs++;
        }
        assertTrue(refs > 0);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Names of the X.4.0.3 table in order, by the generator's rule (sangam_events._audit_names): a name is a backticked
     * upper-case word of the second cell outside parentheses (text in parentheses is a note, never a name; parentheses
     * count only outside backticks), and {@code A_B`/`_C} (a name starting with _) replaces the last word: A_C.
     */
    static List<String> x403() throws IOException {
        String md = Files.readString(SANGAM_MD, StandardCharsets.UTF_8);
        int from = md.indexOf("#### X.4.0.3");
        int to = md.indexOf("#### X.4.0.4", from);
        assertTrue(from >= 0 && to > from, "SANGAM.md X.4.0.3 section");
        List<String> out = new ArrayList<>();
        for (String line : md.substring(from, to).split("\n")) {
            String[] cells = line.split("\\|");
            if (!line.startsWith("| ") || cells.length < 3 || cells[1].trim().equals("Group")) {
                continue;
            }
            assertEquals(0, cells[2].chars().filter(c -> c == '`').count() % 2,
                    "X.4.0.3 row " + cells[1].trim() + " has an unpaired backtick");
            out.addAll(names(cells[2]));
        }
        return out;
    }

    private static final Pattern NAME = Pattern.compile("_?[A-Z][A-Z_]*|_[A-Z_]+");

    /** The names of one second cell (rule of {@link #x403()}). */
    static List<String> names(String cell) {
        List<String> out = new ArrayList<>();
        String[] parts = cell.split("`", -1);
        String previous = null;
        int depth = 0;
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 0) {
                for (char c : parts[i].toCharArray()) {
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth = Math.max(0, depth - 1);
                    }
                }
            } else if (depth == 0 && NAME.matcher(parts[i]).matches()) {
                String name = parts[i];
                if (name.startsWith("_") && previous != null) {
                    name = previous.substring(0, previous.lastIndexOf('_')) + name;
                }
                out.add(name);
                previous = name;
            }
        }
        return out;
    }

    /** Guard for the backtick slip of 2026-11-13: a backticked value in a note is not an event name. */
    @Test
    void aBacktickedValueInANoteIsNotAName() {
        assertEquals(List.of("DATA_OBJECT_RECORD_CREATED", "TENANT_UNRESOLVED", "NEXT_EVENT"),
                names(" `DATA_OBJECT_RECORD_CREATED` (REST-backed: status `PENDING`, backing `REST`, chain `data:<key>`),"
                        + " `TENANT_UNRESOLVED` (resolutionReason `UNRESOLVED_TENANT`; nested (`INNER`) note),"
                        + " `NEXT_EVENT` "));
        assertEquals(List.of("A_B", "A_C"), names(" `A_B`/`_C` (note `_D`) "));
    }

    /**
     * Backticked upper-case words that X.4.0.3 writes inside a note (parentheses) and nowhere as a name: payload values,
     * never events. Each needs its reason. A name the table means to publish must be written outside parentheses.
     */
    static final Map<String, String> NOTE_VALUES = Map.of(
            "UNRESOLVED_TENANT", "decision 144: the resolutionReason value in the TENANT_UNRESOLVED payload note");

    /**
     * No event name is written only inside parentheses: the rule drops it silently, so every backticked upper-case word
     * in a note is either published elsewhere in the table or a listed note value; a listed value no longer in a note
     * fails too, so the list stays exact.
     */
    @Test
    void noNameIsWrittenOnlyInsideParentheses() throws IOException {
        String md = Files.readString(SANGAM_MD, StandardCharsets.UTF_8);
        int from = md.indexOf("#### X.4.0.3");
        int to = md.indexOf("#### X.4.0.4", from);
        Set<String> published = new TreeSet<>(x403());
        Set<String> inNotes = new TreeSet<>();
        List<String> unlisted = new ArrayList<>();
        for (String line : md.substring(from, to).split("\n")) {
            String[] cells = line.split("\\|");
            if (!line.startsWith("| ") || cells.length < 3 || cells[1].trim().equals("Group")) {
                continue;
            }
            String[] parts = cells[2].split("`", -1);
            int depth = 0;
            for (int i = 0; i < parts.length; i++) {
                if (i % 2 == 0) {
                    for (char c : parts[i].toCharArray()) {
                        depth += c == '(' ? 1 : c == ')' && depth > 0 ? -1 : 0;
                    }
                } else if (depth > 0 && NAME.matcher(parts[i]).matches() && !published.contains(parts[i])) {
                    inNotes.add(parts[i]);
                    if (!NOTE_VALUES.containsKey(parts[i])) {
                        unlisted.add(cells[1].trim() + ": " + parts[i]);
                    }
                }
            }
        }
        assertEquals(List.of(), unlisted, "backticked upper-case words only in X.4.0.3 notes: write an event name "
                + "outside parentheses, or list a payload value in NOTE_VALUES with its reason");
        Set<String> stale = new TreeSet<>(NOTE_VALUES.keySet());
        stale.removeAll(inNotes);
        assertEquals(Set.of(), stale, "NOTE_VALUES entries no longer in an X.4.0.3 note");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> schemas(Map<String, Object> doc) {
        return (Map<String, Map<String, Object>>) ((Map<String, Object>) doc.get("components")).get("schemas");
    }
}
