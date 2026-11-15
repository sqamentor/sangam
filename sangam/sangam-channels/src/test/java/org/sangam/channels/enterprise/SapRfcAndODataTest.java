// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.enterprise;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SAP RFC over a customer-supplied JCo (fake client; the real JCo is never on the build path) and OData update/delete
 * (NG-15, M-071). Written, not run (Wave 3).
 */
class SapRfcAndODataTest {

    static ConnectorRequest req(String op, Map<String, Object> in) {
        return new ConnectorRequest("t", UUID.randomUUID(), "a", 1, "root", null, op, in, null);
    }

    static final SapRfc.Destination DEST = new SapRfc.Destination("sangam-t-erp", "127.0.0.1", "00", "100", "RFC_USER", "secret://erp/password", "EN", Map.of());
    static final SecretResolver SECRETS = SecretResolver.of(Map.of("secret://erp/password", "sim-password"));

    /** Records what it was asked and answers with a scripted result or failure. */
    static final class FakeClient implements SapRfc.Client {
        final List<SapRfc.Call> calls = new ArrayList<>();
        final List<Boolean> commits = new ArrayList<>();
        SapRfc.Result result = new SapRfc.Result(Map.of("EV_ID", "4711"), Map.of(), Map.of());
        SapRfc.RfcException failure;
        String password;

        @Override
        public SapRfc.Result call(SapRfc.Destination d, String password, SapRfc.Call call, java.util.function.Predicate<SapRfc.Result> commitIf) throws SapRfc.RfcException {
            this.password = password;
            calls.add(call);
            if (failure != null) {
                throw failure;
            }
            if (call.commit()) {
                commits.add(commitIf.test(result));
            }
            return result;
        }
    }

    static EgressPolicy loopbackAllowed() {
        return EgressPolicy.simulatorLoopback();
    }

    @Test
    void callMapsInputAndOutput() {
        FakeClient fake = new FakeClient();
        SangamConnector c = SapRfc.connector("erp", ConnectorProfile.SIMULATOR, DEST, fake, loopbackAllowed(), SECRETS);
        ConnectorResult r = c.invoke(req("call", Map.of("function", "Z_READ_ORDER", "imports", Map.of("IV_ID", "1", "IS_HEAD", Map.of("A", "b")),
                "tables", Map.of("IT_ITEMS", List.of(Map.of("POS", "10"))))));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r);
        assertEquals(Map.of("EV_ID", "4711"), s.output().get("exports"));
        assertEquals("Z_READ_ORDER", fake.calls.get(0).function());
        assertEquals(List.of(Map.of("POS", "10")), fake.calls.get(0).tables().get("IT_ITEMS"));
        assertEquals("sim-password", fake.password, "the password comes from the secret reference");
        assertEquals("sap-rfc", c.descriptor().type());
    }

    @Test
    void aBapiWithAnErrorMessageIsRolledBackAndRefused() {
        FakeClient fake = new FakeClient();
        fake.result = new SapRfc.Result(Map.of(), Map.of(), Map.of("RETURN", List.of(Map.of("TYPE", "S", "MESSAGE", "fine"),
                Map.of("TYPE", "E", "ID", "V1", "NUMBER", "001", "MESSAGE", "no such customer"))));
        ConnectorResult r = SapRfc.connector("erp", ConnectorProfile.SIMULATOR, DEST, fake, loopbackAllowed(), SECRETS)
                .invoke(req("call", Map.of("function", "BAPI_SALESORDER_CREATEFROMDAT2", "commit", true)));
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r);
        assertEquals("SAP_BAPI_ERROR", f.code());
        assertTrue(f.message().contains("no such customer"));
        assertEquals(List.of(false), fake.commits, "rollback, not commit");

        FakeClient ok = new FakeClient();
        ok.result = new SapRfc.Result(Map.of("RETURN", Map.of("TYPE", "S")), Map.of(), Map.of());
        assertInstanceOf(ConnectorResult.Success.class, SapRfc.connector("erp", ConnectorProfile.SIMULATOR, DEST, ok, loopbackAllowed(), SECRETS)
                .invoke(req("call", Map.of("function", "BAPI_X", "commit", true))));
        assertEquals(List.of(true), ok.commits);
    }

    @Test
    void failuresMapToTheRightOutcome() {
        FakeClient fake = new FakeClient();
        SangamConnector c = SapRfc.connector("erp", ConnectorProfile.SIMULATOR, DEST, fake, loopbackAllowed(), SECRETS);
        fake.failure = new SapRfc.RfcException(SapRfc.Failure.ABAP_EXCEPTION, "not_found", "NOT_FOUND", null);
        assertEquals("SAP_ABAP_NOT_FOUND", ((ConnectorResult.BusinessFailure) c.invoke(req("call", Map.of("function", "Z_X")))).code());
        fake.failure = new SapRfc.RfcException(SapRfc.Failure.COMMUNICATION, null, "partner not reached", null);
        assertInstanceOf(ConnectorResult.OutcomeUnknown.class, c.invoke(req("call", Map.of("function", "Z_X"))), "a changing call may have run");
        ConnectorResult ro = c.invoke(req("call", Map.of("function", "Z_X", "readOnly", true)));
        assertFalse(((ConnectorResult.TransientFailure) ro).noEffect(), "a read may be repeated, but nothing proves it did not run");
        fake.failure = new SapRfc.RfcException(SapRfc.Failure.LOGON, null, "logon", null);
        assertEquals("SAP_LOGON_FAILED", ((ConnectorResult.BusinessFailure) c.invoke(req("call", Map.of("function", "Z_X")))).code());
        assertEquals("INVALID_REQUEST", ((ConnectorResult.BusinessFailure) c.invoke(req("call", Map.of("function", "z x; drop")))).code());
        assertEquals("INVALID_REQUEST", ((ConnectorResult.BusinessFailure) c.invoke(req("call", Map.of("function", "Z_X", "tables", Map.of("T", "rows"))))).code());
    }

    @Test
    void egressAndSecretsAreCheckedBeforeTheCall() {
        FakeClient fake = new FakeClient();
        ConnectorResult denied = SapRfc.connector("erp", ConnectorProfile.LIVE, DEST, fake, EgressPolicy.denyAll(), SECRETS).invoke(req("call", Map.of("function", "Z_X")));
        assertTrue(((ConnectorResult.TransientFailure) denied).noEffect());
        ConnectorResult noSecret = SapRfc.connector("erp", ConnectorProfile.SIMULATOR, DEST, fake, loopbackAllowed(), SecretResolver.of(Map.of()))
                .invoke(req("call", Map.of("function", "Z_X")));
        assertTrue(((ConnectorResult.TransientFailure) noSecret).noEffect());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void withoutTheCustomersJcoTheCallFailsClearlyAndSendsNothing() throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            JcoRfcClient client = new JcoRfcClient(empty);
            assertFalse(client.available());
            ConnectorResult r = SapRfc.connector("erp", ConnectorProfile.SIMULATOR, DEST, client, loopbackAllowed(), SECRETS).invoke(req("call", Map.of("function", "Z_X")));
            ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r);
            assertEquals("SAP_JCO_NOT_AVAILABLE", f.code());
            assertTrue(f.message().contains("sapjco3.jar"), "the message says what to install");
        }
    }

    @Test
    void noJcoClassIsOnTheBuildPath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(JcoRfcClient.MANAGER), "JCo is customer-supplied and never a dependency");
    }

    @Test
    void odataUpdateAndDeleteUseCsrfAndIfMatch() {
        try (EnterpriseSimulator sim = new EnterpriseSimulator()) {
            SangamConnector od = EnterpriseConnectors.sapOData("sap", ConnectorProfile.SIMULATOR, sim.odataRoot(), "a", HttpTransport.forSimulator(),
                    SecretResolver.of(Map.of("a", "Basic x")));
            assertInstanceOf(ConnectorResult.Success.class, od.invoke(req("create", Map.of("entitySet", "Orders", "fields", Map.of("k", "v", "n", "1")))));
            assertInstanceOf(ConnectorResult.Success.class, od.invoke(req("update", Map.of("entitySet", "Orders", "key", "'1'", "fields", Map.of("n", "2")))));
            assertEquals(Map.of("k", "v", "n", "2"), sim.entities().get(0), "PATCH merges");
            assertInstanceOf(ConnectorResult.Success.class, od.invoke(req("update", Map.of("entitySet", "Orders", "key", "'1'", "method", "PUT", "fields", Map.of("n", "3")))));
            assertEquals(Map.of("n", "3"), sim.entities().get(0), "PUT replaces");
            assertEquals("INVALID_REQUEST", ((ConnectorResult.BusinessFailure) od.invoke(req("update", Map.of("entitySet", "Orders", "key", "'1'", "method", "POST")))).code());

            sim.expireNextToken();
            assertInstanceOf(ConnectorResult.Success.class, od.invoke(req("delete", Map.of("entitySet", "Orders", "key", "'1'"))), "an expired CSRF token is fetched again once");
            assertNull(sim.entities().get(0));
            assertEquals("HTTP_404", ((ConnectorResult.BusinessFailure) od.invoke(req("delete", Map.of("entitySet", "Orders", "key", "'9'")))).code());
            assertEquals("INVALID_REQUEST", ((ConnectorResult.BusinessFailure) od.invoke(req("delete", Map.of("entitySet", "Orders")))).code(), "delete needs a key");
        }
    }
}
