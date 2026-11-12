// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.config.api.ConfigConnector;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.MasterStore;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;

/**
 * The optional {@code formKind} restriction of the catalogue lookups and of {@code documentType.get} (coordinator
 * decision Q8 on APPROVE-401 step 2b): with a kind, only types of that kind match, so a bill never resolves a report
 * or a register; without one, the lookup is exactly as before.
 */
class DocumentTypeFormKindFilterTest {

    static final DocumentTypeCatalogue CAT = new DocumentTypeCatalogue(List.of(
            Map.<String, Object>of("id", "ST_BILL", "formKind", "BILL", "formNumber", "ST:9"),
            Map.<String, Object>of("id", "ST_REPORT", "formKind", "REPORT", "formNumber", "ST:9"),
            Map.<String, Object>of("id", "ST_REGISTER", "formKind", "FORM", "formNumber", "ST:10",
                    "legacyForms", List.of("OLD-10")),
            Map.<String, Object>of("id", "ST_NOKIND", "formNumber", "ST:11")), List.of());

    static List<String> ids(List<DocumentType> types) {
        return types.stream().map(DocumentType::id).toList();
    }

    @Test
    void aKindRestrictsTheReferenceLookupAndNoKindLeavesItAsBefore() {
        assertEquals(List.of("ST_BILL", "ST_REPORT"), ids(CAT.byReference("ST:9")), "no kind: both, ambiguous");
        assertEquals(List.of("ST_BILL", "ST_REPORT"), ids(CAT.byReference("ST:9", null)));
        assertEquals(List.of("ST_BILL", "ST_REPORT"), ids(CAT.byReference("ST:9", " ")));
        assertEquals(List.of("ST_BILL"), ids(CAT.byReference("ST:9", "BILL")), "the bill alone: unambiguous");
        assertEquals(List.of("ST_REPORT"), ids(CAT.byReference("ST:9", "REPORT")));
        assertTrue(CAT.byReference("ST_REGISTER", "BILL").isEmpty(), "a register is no bill, even by its id");
        assertTrue(CAT.byReference("OLD-10", "BILL").isEmpty(), "nor by its legacy form");
        assertEquals(List.of("ST_REGISTER"), ids(CAT.byReference("OLD-10", "FORM")));
        assertTrue(CAT.byReference("ST_NOKIND", "BILL").isEmpty(), "a row that declares no kind does not match a kind");
        assertEquals(List.of("ST_NOKIND"), ids(CAT.byReference("ST_NOKIND", null)));
    }

    @Test
    void aKindRestrictsTheFormLookup() {
        assertEquals("ST_BILL", CAT.byForm("ST:9").orElseThrow().id(), "no kind: the first row, as before");
        assertEquals("ST_REPORT", CAT.byForm("ST:9", "REPORT").orElseThrow().id(),
                "a kind looks past the first row of another kind");
        assertTrue(CAT.byForm("ST:10", "BILL").isEmpty());
        assertEquals("ST_REGISTER", CAT.byForm("OLD-10", "FORM").orElseThrow().id());
        assertEquals("FORM", CAT.type("ST_REGISTER").orElseThrow().formKind());
        assertEquals(null, CAT.type("ST_NOKIND").orElseThrow().formKind());
    }

    @Test
    void documentTypeGetHonoursTheFormKindInput() throws Exception {
        Path dir = Path.of("..", "sangam-packs", "src", "main", "resources", "profiles");
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(Files.readString(dir.resolve("layers.yaml"))).get("layers"));
        ConfigConnector connector = new ConfigConnector(
                new ProfileResolver(InMemoryProfileRepository.active(ProfileLoader.loadDirectory(dir)), layers),
                new ClassificationRegistry(new MasterStore.InMemory()), new NumberSeries.InMemory());
        // a bill type of the shipped Rajasthan catalogue, asked for as a bill, by id, typeId and form number
        assertEquals("RJ_AC_BILL", assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(get("RJ", Map.of("typeRef", "RJ_AC_BILL", "formKind", "BILL")))).output().get("id"));
        assertEquals("RJ_AC_BILL", assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(get("RJ", Map.of("typeId", "RJ_AC_BILL", "formKind", "BILL")))).output().get("id"));
        assertEquals("RJ_AC_BILL", assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(get("RJ", Map.of("formNumber", "RJ:GA 85", "formKind", "BILL")))).output().get("id"));
        // the same type asked for as another kind is not found
        assertEquals("TYPE_UNKNOWN", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                connector.invoke(get("RJ", Map.of("typeId", "RJ_AC_BILL", "formKind", "REPORT")))).code());
        assertEquals("TYPE_UNKNOWN", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                connector.invoke(get("RJ", Map.of("typeRef", "RJ_AC_BILL", "formKind", "REPORT")))).code());
        // no formKind: unchanged
        assertEquals("RJ_AC_BILL", assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(get("RJ", Map.of("typeRef", "RJ_AC_BILL")))).output().get("id"));
    }

    static ConnectorRequest get(String tenant, Map<String, Object> input) {
        Map<String, Object> in = new LinkedHashMap<>(input);
        in.put("asOf", LocalDate.of(2026, 11, 5).toString());
        return new ConnectorRequest(tenant, UUID.randomUUID(), "a", 1, null, null, "documentType.get", in, null);
    }
}
