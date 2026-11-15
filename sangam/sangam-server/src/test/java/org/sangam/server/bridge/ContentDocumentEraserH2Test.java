// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.schema.IntegrationSchema;
import org.sangam.ports.PortsSchema;
import org.sangam.ports.content.ContentService;
import org.sangam.ports.content.FileSystemDocumentStore;

/**
 * The document step of data erasure on the server's document store: the documents of the erased process instances
 * (root and sub-instances) leave the store with all their versions, the logically deleted ones too; documents of
 * other instances and of other tenants stay, and a document under its own legal hold is kept and not counted.
 */
class ContentDocumentEraserH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String TENANT = "T1";

    @TempDir
    Path root;

    private final JdbcDataSource ds = new JdbcDataSource();
    private Connection keepAlive;
    private Transactions tx;
    private ContentService content;
    private ContentDocumentEraser eraser;

    private void open() throws Exception {
        ds.setURL("jdbc:h2:mem:docerase" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        keepAlive = ds.getConnection();
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        PortsSchema.create(keepAlive, D);
        tx = Transactions.of(ds);
        content = new ContentService(D, tx, new FileSystemDocumentStore(root), null, false);
        eraser = new ContentDocumentEraser(content, tx);
    }

    @AfterEach
    void close() throws Exception {
        if (keepAlive != null) {
            keepAlive.close();
        }
    }

    private String doc(String tenant, String instance, String name) {
        return content.create(tenant, name, "text/plain", ContentDocumentEraser.SCOPE, instance, null,
                new ByteArrayInputStream(("content of " + name).getBytes(StandardCharsets.UTF_8)), "user:clerk").id();
    }

    private long storedObjects() throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private long rows(String table) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM " + table, rs -> rs.getLong(1))).orElseThrow();
    }

    @Test
    void theDocumentsOfTheErasedInstancesLeaveTheStore() throws Exception {
        open();
        String a1 = doc(TENANT, "pi-root", "application.txt");
        content.addVersion(a1, new ByteArrayInputStream("second version".getBytes(StandardCharsets.UTF_8)), "user:clerk");
        String a2 = doc(TENANT, "pi-root", "withdrawn.txt");
        content.delete(a2); // logically deleted before the erasure: its content must go too
        String sub = doc(TENANT, "pi-sub", "annexure.txt");
        String other = doc(TENANT, "pi-other", "another-case.txt");
        String foreign = doc("T2", "pi-root", "same-instance-id-other-tenant.txt");
        assertEquals(6, storedObjects(), "five documents, one with two versions");

        assertEquals(3, eraser.erase(TENANT, List.of("pi-root", "pi-sub"), "DPDP erasure"));

        assertTrue(content.item(a1).isEmpty());
        assertTrue(content.item(a2).isEmpty(), "a logically deleted document is erased as well");
        assertTrue(content.item(sub).isEmpty(), "the sub-instance's document");
        assertTrue(content.item(other).isPresent(), "another instance is untouched");
        assertTrue(content.item(foreign).isPresent(), "another tenant is untouched");
        assertEquals(2, storedObjects(), "the erased documents' objects left the store, every version");
        assertEquals(2, rows("CONTENT_ITEM"));
        assertEquals(2, rows("CONTENT_VERSION"));

        assertEquals(0, eraser.erase(TENANT, List.of("pi-root", "pi-sub"), "again"), "nothing left to erase");
    }

    @Test
    void aDocumentUnderItsOwnLegalHoldIsKept() throws Exception {
        open();
        String held = doc(TENANT, "pi-root", "evidence.txt");
        String free = doc(TENANT, "pi-root", "note.txt");
        tx.inTransaction(c -> Jdbc.update(c, "INSERT INTO LEGAL_HOLD(tenant_id, root_id, hold_id, reason, held_by, created_at)"
                + " VALUES (?,?,?,?,?," + D.now() + ")", TENANT, held, UUID.randomUUID().toString(), "court order", "user:legal"));

        assertEquals(1, eraser.erase(TENANT, List.of("pi-root"), "DPDP erasure"), "only the document that is not held");
        assertTrue(content.item(held).isPresent(), "the hold wins");
        assertFalse(content.item(free).isPresent());

        tx.inTransaction(c -> Jdbc.update(c, "UPDATE LEGAL_HOLD SET released_at=" + D.now() + ", released_by=? WHERE root_id=?",
                "user:legal", held));
        assertEquals(1, eraser.erase(TENANT, List.of("pi-root"), "after the release"));
        assertTrue(content.item(held).isEmpty());
    }

    @Test
    void anEmptyTreeErasesNothing() throws Exception {
        open();
        doc(TENANT, "pi-root", "a.txt");
        assertEquals(0, eraser.erase(TENANT, List.of(), "nothing"));
        assertEquals(0, eraser.erase(TENANT, null, "nothing"));
        assertEquals(1, rows("CONTENT_ITEM"));
    }
}
