// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * Owner decision 305: the per-tenant DLT registration is stored; each change that alters it writes
 * DLT_TEMPLATE_REGISTERED in its transaction (and nothing when it alters nothing); start-up configuration only seeds;
 * the registry the connector validates against follows the store on every node.
 */
class DltRegistrationStoreH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String TENANT = "T1";
    private static final String BODY = "Dear {#var#}, your certificate is ready: https://dept.gov.in/c/{#var#}";

    private final String url = "jdbc:h2:mem:dltreg" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final Connection keepAlive;
    private final DltRegistrationStore store;

    DltRegistrationStoreH2Test() throws SQLException {
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        store = new DltRegistrationStore(D, tx(), new AuditChain(D));
    }

    @AfterEach
    void close() throws SQLException {
        keepAlive.close();
    }

    private Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.read(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private int events() {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT seq FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? AND event_type=?",
                rs -> rs.getLong(1), TENANT, DltRegistrationStore.CHAIN, "DLT_TEMPLATE_REGISTERED")).size();
    }

    private String message() {
        return "Dear Asha, your certificate is ready: https://dept.gov.in/c/A12";
    }

    @Test
    void aRegistrationIsStoredWithItsEventAndTheConnectorValidatesAgainstIt() {
        assertEquals("TENANT_NOT_REGISTERED", store.registry().validate(TENANT, "DEPTGV", "1107001", "9876543210", message()));
        assertTrue(store.registerEntity(TENANT, "user:admin", "1101000000000000001").changed());
        assertTrue(store.registerTemplate(TENANT, "user:admin", "1107001", "DEPTGV", BODY).changed());
        DltRegistrationStore.Change url = store.registerUrl(TENANT, "user:admin", "https://dept.gov.in/c/");
        assertTrue(url.changed());
        assertEquals(3, url.revision());
        assertEquals(3, events(), "one event per change, in its transaction");

        assertNull(store.registry().validate(TENANT, "DEPTGV", "1107001", "9876543210", message()), "the connector sees it at once");
        assertEquals("1101000000000000001", store.registry().find(TENANT).orElseThrow().entityId());

        DltRegistrationStore.Snapshot s = store.snapshot(TENANT).orElseThrow();
        assertEquals(List.of("https://dept.gov.in/c/"), s.urlPrefixes());
        assertEquals(BODY, s.templates().get(0).body());
    }

    @Test
    void registeringWhatIsRegisteredWritesNothingAndAChangeIsAReplacement() {
        store.registerTemplate(TENANT, "user:admin", "1107001", "DEPTGV", BODY);
        DltRegistrationStore.Change same = store.registerTemplate(TENANT, "user:admin", "1107001", "DEPTGV", BODY);
        assertFalse(same.changed());
        assertEquals(1, same.revision());
        assertEquals(1, events());

        DltRegistrationStore.Change changed = store.registerTemplate(TENANT, "user:admin", "1107001", "DEPTGV", "Hello {#var#}");
        assertTrue(changed.changed());
        assertEquals(2, events());
        String payload = tx().inTransaction(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                + " WHERE e.event_type='DLT_TEMPLATE_REGISTERED' ORDER BY e.seq", rs -> rs.getString(1))).get(1);
        assertTrue(payload.contains("\"replaced\":true"));
        assertTrue(payload.contains("user:admin"));
        assertNull(store.registry().validate(TENANT, "DEPTGV", "1107001", "9876543210", "Hello Asha"));
    }

    @Test
    void refusedRegistrationsStoreAndWriteNothing() {
        assertEquals("HEADER_INVALID", assertThrows(DltRegistrationStore.InvalidRegistration.class,
                () -> store.registerHeader(TENANT, "user:admin", "TOOLONGHEADER")).reason());
        assertEquals("TEMPLATE_INVALID", assertThrows(DltRegistrationStore.InvalidRegistration.class,
                () -> store.registerTemplate(TENANT, "user:admin", "1107002", "DEPTGV", "Track at https://{#var#}")).reason(),
                "a URL may not be free text");
        assertEquals("TEMPLATE_INVALID", assertThrows(DltRegistrationStore.InvalidRegistration.class,
                () -> store.registerTemplate(TENANT, "user:admin", "1107002", "DEPTGV", " ")).reason());
        assertEquals("URL_INVALID", assertThrows(DltRegistrationStore.InvalidRegistration.class,
                () -> store.registerUrl(TENANT, "user:admin", "ftp://dept.gov.in/")).reason());
        assertEquals("ENTITY_INVALID", assertThrows(DltRegistrationStore.InvalidRegistration.class,
                () -> store.registerEntity(TENANT, "user:admin", "pe id")).reason());
        assertThrows(IllegalArgumentException.class, () -> store.registerHeader(TENANT, " ", "DEPTGV"), "an actor is required");
        assertEquals(0, events());
        assertTrue(store.snapshot(TENANT).isEmpty());
        assertTrue(store.registerHeader(TENANT, "user:admin", "DEPTGV-G").changed(), "a TRAI suffix is accepted");
    }

    @Test
    void startUpConfigurationOnlySeeds() {
        store.registerTemplate(TENANT, "user:admin", "1107001", "DEPTGV", BODY);
        DltRegistrationStore.Seed seed = new DltRegistrationStore.Seed(TENANT, "1101000000000000001", List.of("DEPTGV"),
                List.of(new DltRegistrationStore.TemplateEntry("1107001", "DEPTGV", "Configured {#var#}"),
                        new DltRegistrationStore.TemplateEntry("1107009", "DEPTGV", "Second {#var#}")),
                List.of("https://dept.gov.in/c/"));
        assertEquals(4, store.seed(List.of(seed)), "entity, header, the absent template and the URL");
        assertEquals(BODY, store.snapshot(TENANT).orElseThrow().templates().stream()
                .filter(t -> t.templateId().equals("1107001")).findFirst().orElseThrow().body(), "a stored entry is never overwritten");
        assertEquals(5, events());
        assertEquals(0, store.seed(List.of(seed)), "a second start-up adds nothing");
        assertEquals(5, events());

        assertThrows(DltRegistrationStore.InvalidRegistration.class, () -> store.seed(List.of(new DltRegistrationStore.Seed(
                TENANT, null, List.of("BAD"), null, null))), "a wrong configuration stops start-up");
    }

    @Test
    void anotherNodeFollowsTheStoreOnRefresh() {
        DltRegistrationStore other = new DltRegistrationStore(D, tx(), new AuditChain(D));
        store.registerTemplate(TENANT, "user:admin", "1107001", "DEPTGV", "Hello {#var#}");
        assertEquals("TENANT_NOT_REGISTERED", other.registry().validate(TENANT, "DEPTGV", "1107001", "9876543210", "Hello Asha"));
        assertEquals(1, other.refresh());
        assertNull(other.registry().validate(TENANT, "DEPTGV", "1107001", "9876543210", "Hello Asha"));
        assertEquals(0, other.refresh(), "nothing moved");
    }
}
