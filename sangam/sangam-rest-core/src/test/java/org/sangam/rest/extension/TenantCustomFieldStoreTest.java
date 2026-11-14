// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.sangam.rest.extension.TenantCustomFieldRegistry.ConcurrentChangeException;
import org.sangam.rest.extension.TenantCustomFieldRegistry.FieldDefinition;
import org.sangam.rest.extension.TenantCustomFieldRegistry.Store;

/**
 * SL-40: the registry reads and writes through its {@link Store} (nothing cached), every write and its audit run in one
 * store transaction, and concurrent changes are refused instead of overwriting history.
 */
class TenantCustomFieldStoreTest {

    private static final String FORM = "form:grant-application";
    private static final Map<String, Object> STR = Map.of("type", "string");
    private static final LocalDate APR = LocalDate.of(2026, 4, 1);
    private static final LocalDate JAN27 = LocalDate.of(2027, 1, 1);

    /** Records every store call, marking those made inside {@link #inTransaction}. */
    static final class RecordingStore implements Store {
        final Store target = new TenantCustomFieldRegistry.InMemoryStore();
        final List<String> calls = new ArrayList<>();
        boolean inTx;

        private void call(String c) {
            calls.add((inTx ? "tx:" : "") + c);
        }

        @Override
        public List<FieldDefinition> versions(String t, String o, String k) {
            call("versions " + k);
            return target.versions(t, o, k);
        }

        @Override
        public List<FieldDefinition> inForce(String t, String o, LocalDate on) {
            call("inForce " + on);
            return target.inForce(t, o, on);
        }

        @Override
        public List<FieldDefinition> history(String t) {
            call("history");
            return target.history(t);
        }

        @Override
        public void insert(String t, String actor, FieldDefinition d) {
            call("insert " + d.key() + " v" + d.version() + " by " + actor);
            target.insert(t, actor, d);
        }

        @Override
        public void close(String t, String actor, FieldDefinition cur, LocalDate to) {
            call("close " + cur.key() + " v" + cur.version() + " at " + to + " by " + actor);
            target.close(t, actor, cur, to);
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            inTx = true;
            try {
                return work.get();
            } finally {
                inTx = false;
            }
        }

        @Override
        public boolean knownEmpty() {
            return target.knownEmpty();
        }
    }

    private final RecordingStore store = new RecordingStore();
    private final List<String> audit = new ArrayList<>();
    private final TenantCustomFieldRegistry reg = new TenantCustomFieldRegistry(Set.of(),
            (t, a, action, p) -> audit.add((store.inTx ? "tx:" : "") + action + " " + p.get("key") + " v" + p.get("version")),
            store);

    @Test
    void aNewVersionClosesThePreviousOneAndIsAuditedInOneStoreTransaction() {
        reg.define("t1", "admin", FORM, "x_code", "Code", true, false, STR, APR, null);
        reg.define("t1", "officer", FORM, "x_code", "Code", true, false, Map.of("type", "string", "maxLength", 4), JAN27, null);
        assertEquals(List.of(
                "tx:versions x_code", "tx:insert x_code v1 by admin",
                "tx:versions x_code", "tx:close x_code v1 at 2027-01-01 by officer", "tx:insert x_code v2 by officer"),
                store.calls);
        assertEquals(List.of("tx:CUSTOM_FIELD_DEFINED x_code v1", "tx:CUSTOM_FIELD_DEFINED x_code v2"), audit);
    }

    @Test
    void retireClosesTheCurrentVersionInOneStoreTransaction() {
        reg.define("t1", "admin", FORM, "x_code", null, false, false, STR, APR, null);
        store.calls.clear();
        FieldDefinition closed = reg.retire("t1", "officer", FORM, "x_code", JAN27);
        assertEquals(JAN27, closed.effectiveTo());
        assertEquals(List.of("tx:versions x_code", "tx:close x_code v1 at 2027-01-01 by officer"), store.calls);
        assertEquals("tx:CUSTOM_FIELD_RETIRED x_code v1", audit.get(1));
        assertThrows(IllegalArgumentException.class, () -> reg.retire("t1", "officer", FORM, "x_code", null),
                "a retirement needs a date");
    }

    @Test
    void readsGoToTheStoreSoAnotherRegistryOnTheSameStoreSeesTheDefinitions() {
        reg.define("t1", "admin", FORM, "x_code", null, false, false, STR, APR, null);
        reg.define("t1", "admin", FORM, "x_code", null, false, false, STR, JAN27, null);
        // a second node / a restart: a fresh registry over the same store, nothing loaded into it
        TenantCustomFieldRegistry other = new TenantCustomFieldRegistry(Set.of(), (t, a, x, p) -> { }, store);
        assertEquals(1, other.inForce("t1", FORM, LocalDate.of(2026, 12, 31)).get(0).version());
        assertEquals(2, other.inForce("t1", FORM, JAN27).get(0).version());
        assertEquals(List.of(1, 2), other.versions("t1", FORM, "x_code").stream().map(FieldDefinition::version).toList());
        assertEquals(2, other.history("t1").size());
        assertTrue(other.history("t2").isEmpty(), "tenants are isolated");
        // the next version continues from the stored history
        assertEquals(3, other.define("t1", "admin", FORM, "x_code", null, false, false, STR,
                LocalDate.of(2028, 1, 1), null).version());
    }

    @Test
    void aStaleCloseIsAConflictAndNothingIsAudited() {
        reg.define("t1", "admin", FORM, "x_code", null, false, false, STR, APR, null);
        FieldDefinition stale = reg.versions("t1", FORM, "x_code").get(0);
        reg.retire("t1", "admin", FORM, "x_code", JAN27); // another node closed it meanwhile
        audit.clear();
        assertThrows(ConcurrentChangeException.class, () -> store.close("t1", "officer", stale, LocalDate.of(2026, 9, 1)));
        assertThrows(ConcurrentChangeException.class, () -> store.insert("t1", "officer", stale),
                "the same version twice is refused");
        assertEquals(JAN27, reg.versions("t1", FORM, "x_code").get(0).effectiveTo(), "the stored end is kept");
        assertTrue(audit.isEmpty());
    }

    @Test
    void aFailedAuditFailsTheDefinitionAndARefusedDefinitionWritesNothing() {
        TenantCustomFieldRegistry failing = new TenantCustomFieldRegistry(Set.of(), (t, a, x, p) -> {
            throw new IllegalStateException("audit down");
        }, store);
        assertThrows(IllegalStateException.class,
                () -> failing.define("t1", "admin", FORM, "x_code", null, false, false, STR, APR, null));
        store.calls.clear();
        assertThrows(IllegalArgumentException.class,
                () -> reg.define("t1", "admin", FORM, "x_bad", null, false, false, Map.of("type", "number"), APR, null));
        assertTrue(store.calls.isEmpty(), "an invalid definition is refused before the store is touched");
    }

    @Test
    void thePerCallAuditSinkReplacesTheRegistrysOne() {
        List<String> request = new ArrayList<>();
        reg.define("t1", "admin", FORM, "x_code", null, false, false, STR, APR, null,
                (t, a, action, p) -> request.add(t + "|" + a + "|" + action));
        reg.retire("t1", "admin", FORM, "x_code", JAN27, (t, a, action, p) -> request.add(t + "|" + a + "|" + action));
        assertEquals(List.of("t1|admin|CUSTOM_FIELD_DEFINED", "t1|admin|CUSTOM_FIELD_RETIRED"), request);
        assertTrue(audit.isEmpty());
    }

    @Test
    void anInMemoryRegistryIsEmptyUntilAFieldIsDefined() {
        assertTrue(TenantCustomFieldRegistry.empty().isEmpty());
        assertTrue(reg.isEmpty());
        reg.define("t1", "admin", FORM, "x_code", null, false, false, STR, APR, null);
        assertFalse(reg.isEmpty());
    }
}
