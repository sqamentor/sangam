// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.modeller.ModellerSchema;
import org.sangam.modeller.catalogue.ExtensionCatalogue.CatalogueException;

/**
 * Owner decision 380 P2 (b): a catalogue entry of kind connector-template carries data-only element templates, checked
 * before anything is stored, kept inside the entry's id namespace, and installed per tenant in one transaction with the
 * caller's audit work.
 */
class CatalogueConnectorTemplatesTest {

    static final String ENTRY = "acme-erp";
    static final String TEMPLATES = "{\"templates\":[{\"id\":\"acme-erp.create-order\",\"name\":\"ERP: create order\","
            + "\"description\":\"Creates an order\",\"appliesTo\":[\"bpmn:ServiceTask\"],\"version\":9,\"fields\":["
            + "{\"name\":\"type\",\"binding\":\"operaton:type\",\"required\":true,\"default\":\"external\",\"choices\":[\"external\"]},"
            + "{\"name\":\"topic\",\"binding\":\"operaton:topic\",\"required\":true,\"pattern\":\"[a-z][a-z0-9.-]*\"},"
            + "{\"name\":\"path\",\"binding\":\"input:path\",\"required\":true}]},"
            + "{\"id\":\"acme-erp.get-order\",\"name\":\"ERP: get order\",\"appliesTo\":[\"bpmn:ServiceTask\"],\"fields\":["
            + "{\"name\":\"topic\",\"binding\":\"operaton:topic\",\"required\":true}]}]}";

    Transactions tx;
    ElementTemplates store;

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            ModellerSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
        store = new ElementTemplates(Dialect.POSTGRESQL, tx);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsTheTemplatesInOrderAndDropsAnyCarriedVersion() {
        List<Map<String, Object>> docs = CatalogueConnectorTemplates.read(ENTRY, bytes(TEMPLATES));
        assertEquals(List.of("acme-erp.create-order", "acme-erp.get-order"), docs.stream().map(d -> d.get("id")).toList());
        assertFalse(docs.get(0).containsKey("version"), "the installing tenant numbers the versions");
    }

    @Test
    void refusesEveryArtefactThatIsNotADataOnlyTemplateSetOfTheEntry() {
        List<String> bad = List.of(
                "not json",
                "{\"templates\":[]}",
                "{\"other\":1}",
                "{\"templates\":[\"x\"]}",
                // outside the namespace: would replace a built-in, or another entry's template
                TEMPLATES.replace("acme-erp.create-order", "sangam.connector.aws-lambda"),
                TEMPLATES.replace("acme-erp.create-order", "acme-erpx.create-order"),
                // duplicate id
                TEMPLATES.replace("acme-erp.get-order", "acme-erp.create-order"),
                // not a valid ELEMENT template: unknown binding, no fields list
                TEMPLATES.replace("\"binding\":\"input:path\"", "\"binding\":\"java:class\""),
                "{\"templates\":[{\"id\":\"acme-erp.x\",\"appliesTo\":[\"bpmn:ServiceTask\"]}]}",
                // a credential is connector configuration, never a template value
                TEMPLATES.replace("\"name\":\"path\"", "\"name\":\"clientSecret\""));
        for (String b : bad) {
            CatalogueException e = assertThrows(CatalogueException.class, () -> CatalogueConnectorTemplates.read(ENTRY, bytes(b)), b);
            assertEquals("CATALOGUE_ARTIFACT_INVALID", e.code(), b);
        }
        StringBuilder many = new StringBuilder("{\"templates\":[");
        for (int i = 0; i <= CatalogueConnectorTemplates.MAX_TEMPLATES; i++) {
            many.append(i == 0 ? "" : ",").append("{\"id\":\"acme-erp.t").append(i)
                    .append("\",\"appliesTo\":[\"bpmn:ServiceTask\"],\"fields\":[]}");
        }
        assertEquals("CATALOGUE_ARTIFACT_INVALID", assertThrows(CatalogueException.class,
                () -> CatalogueConnectorTemplates.read(ENTRY, bytes(many + "]}"))).code());
    }

    @Test
    void installPublishesChangedTemplatesOnlyAndRunsTheAuditWorkInTheSameTransaction() {
        List<Map<String, Object>> docs = CatalogueConnectorTemplates.read(ENTRY, bytes(TEMPLATES));
        List<List<String>> audited = new ArrayList<>();
        CatalogueConnectorTemplates.Installed first = CatalogueConnectorTemplates.install(store, "t1", "carol", docs,
                p -> audited.add(p.stream().map(t -> t.id() + "@" + t.version()).toList()));
        assertEquals(List.of("acme-erp.create-order@1", "acme-erp.get-order@1"), audited.get(0));
        assertEquals(2, first.published().size());
        assertTrue(first.unchanged().isEmpty());
        assertEquals(1, store.find("t1", "acme-erp.create-order", null).orElseThrow().version());
        assertTrue(store.find("t2", "acme-erp.create-order", null).isEmpty(), "installs are per tenant (P4 (i))");

        // a repeated install writes nothing and records nothing
        CatalogueConnectorTemplates.Installed again = CatalogueConnectorTemplates.install(store, "t1", "carol", docs, p -> audited.add(List.of("x")));
        assertTrue(again.published().isEmpty());
        assertEquals(List.of("acme-erp.create-order", "acme-erp.get-order"), again.unchanged());
        assertEquals(1, audited.size());

        // a changed template gets a new version; the unchanged one keeps its version
        List<Map<String, Object>> v2 = CatalogueConnectorTemplates.read(ENTRY, bytes(TEMPLATES.replace("Creates an order", "Creates a sales order")));
        CatalogueConnectorTemplates.Installed second = CatalogueConnectorTemplates.install(store, "t1", "carol", v2, p -> { });
        assertEquals(List.of("acme-erp.create-order"), second.published().stream().map(ElementTemplates.Template::id).toList());
        assertEquals(2, second.published().get(0).version());
        assertEquals(List.of("acme-erp.get-order"), second.unchanged());
    }

    @Test
    void aFailingAuditLeavesNoTemplateBehind() {
        List<Map<String, Object>> docs = CatalogueConnectorTemplates.read(ENTRY, bytes(TEMPLATES));
        assertThrows(IllegalStateException.class, () -> CatalogueConnectorTemplates.install(store, "t1", "carol", docs, p -> {
            throw new IllegalStateException("audit store down");
        }));
        int rows = tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM MOD_TEMPLATE", rs -> rs.getInt(1)).orElse(-1));
        assertEquals(0, rows);
    }
}
