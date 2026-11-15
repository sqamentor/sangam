// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.arazzo.ArazzoOperationStep;
import org.sangam.integration.connector.file.FileDropConnector;
import org.sangam.integration.connector.gov.AadhaarSubAuaConnector;
import org.sangam.integration.connector.gov.DigiLockerPushConnector;
import org.sangam.integration.connector.gov.DigiLockerRequesterConnector;
import org.sangam.integration.connector.gov.DltSmsConnector;
import org.sangam.integration.connector.gov.EsignConnector;
import org.sangam.integration.connector.gov.IfmsJitConnector;
import org.sangam.integration.connector.gov.PaymentGatewayConnector;
import org.sangam.integration.connector.gov.PfmsFileConnector;
import org.sangam.integration.connector.graphql.GraphQlConnector;
import org.sangam.integration.connector.http.AsyncCallback;
import org.sangam.integration.connector.http.HttpRestConnector;
import org.sangam.integration.connector.http.SoapConnector;
import org.sangam.integration.connector.jdbc.JdbcConnector;
import org.sangam.integration.connector.jdbc.NamedStatement;
import org.sangam.integration.connector.mail.SmtpEmailConnector;
import org.sangam.integration.connector.s3.S3Connector;
import org.sangam.integration.connector.temporal.TemporalConnector;

/**
 * Owner decisions 320 and 321: what each connector of this module answers to {@link SangamConnector#readOnly}, request
 * by request. Read-only means the request changes no state and sends nothing outside; the answer comes from what the
 * request will really do (the resolved HTTP method, the resolved statement), never from the operation's name.
 *
 * <p>A connector that is not in {@link #DECLARING} must keep the default ({@code false}): adding a {@code readOnly}
 * override to one of them fails {@link #everyOtherConnectorKeepsTheFailClosedDefault} until its requests are listed
 * here, so the choice is made on purpose. (The S3 connector's rows are in {@code S3ConnectorTest}, which has its
 * client fixture.)
 */
class ConnectorReadOnlyTableTest {

    /** The connectors of this module that answer readOnly themselves. */
    static final List<Class<? extends SangamConnector>> DECLARING = List.of(HttpRestConnector.class, JdbcConnector.class,
            S3Connector.class);

    /**
     * Every other connector of this module: each one sends a message, a payment, a signature request, a file or a
     * workflow start, or (SOAP, GraphQL, Arazzo) always posts a body whose effect the connector cannot see; the
     * DigiLocker requester's pull and age check and the Aadhaar OTP are inquiries that send something (decision 321).
     */
    static final List<Class<? extends SangamConnector>> DEFAULT_FALSE = List.of(SoapConnector.class, GraphQlConnector.class,
            SmtpEmailConnector.class, FileDropConnector.class, TemporalConnector.class, ArazzoOperationStep.class,
            AadhaarSubAuaConnector.class, DigiLockerPushConnector.class, DigiLockerRequesterConnector.class,
            DltSmsConnector.class, EsignConnector.class, IfmsJitConnector.class, PaymentGatewayConnector.class,
            PfmsFileConnector.class);

    static ConnectorRequest req(String operation, Map<String, Object> input) {
        return new ConnectorRequest("t1", UUID.randomUUID(), "a1", 1, null, null, operation, input, null);
    }

    static Map<String, Object> in(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void everyOtherConnectorKeepsTheFailClosedDefault() throws Exception {
        for (Class<? extends SangamConnector> c : DEFAULT_FALSE) {
            assertEquals(SangamConnector.class, c.getMethod("readOnly", ConnectorRequest.class).getDeclaringClass(),
                    c.getSimpleName() + " must not declare readOnly without its requests being listed in this test");
        }
        for (Class<? extends SangamConnector> c : DECLARING) {
            assertEquals(c, c.getMethod("readOnly", ConnectorRequest.class).getDeclaringClass(), c.getSimpleName());
        }
    }

    // ------------------------------------------------------------------ HTTP: the resolved method decides

    static HttpRestConnector http(String defaultMethod, AsyncCallback callback) {
        return new HttpRestConnector(new HttpRestConnector.Config("erp", URI.create("http://127.0.0.1:9/api"),
                CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, null, null, null, Map.of(), Duration.ofSeconds(1),
                ConnectorProfile.SIMULATOR, null, null, defaultMethod, null, callback), HttpTransport.forSimulator(),
                SecretResolver.of(Map.of()));
    }

    @Test
    void httpIsReadOnlyOnlyWhenTheResolvedMethodIsGetOrHead() {
        HttpRestConnector post = http(null, null);   // the default method is POST
        HttpRestConnector get = http("GET", null);
        record Row(HttpRestConnector c, String operation, Map<String, Object> input, boolean expected) {
        }
        List<Row> rows = List.of(
                new Row(post, "list", in("offset", 0, "limit", 20), false),            // no method: the default, POST
                new Row(post, "list", in("method", "POST"), false),
                new Row(post, "list", in("method", "GET"), true),
                new Row(post, "list", in("method", "get", "path", "/vendors"), true),   // resolved upper-case, as invoke
                new Row(post, "list", in("method", "HEAD"), true),
                new Row(post, "get", in("method", "PUT"), false),                      // the name plays no part
                new Row(post, "read", in("method", "DELETE"), false),
                new Row(post, "fetch", in("method", "PATCH"), false),
                new Row(post, "list", in("method", "OPTIONS"), false),
                new Row(get, "list", in("offset", 0, "limit", 20), true),              // no method: the default, GET
                new Row(get, "closePeriod", in(), true),                               // still a GET, whatever the name
                new Row(get, "list", in("method", "POST"), false),                     // the request overrides the default
                new Row(get, "list", in("method", "not a method"), false));
        for (Row r : rows) {
            assertEquals(r.expected(), r.c().readOnly(req(r.operation(), r.input())), r.operation() + " " + r.input());
        }
    }

    @Test
    void anHttpCallThatAsksForAnAsyncResultIsNeverReadOnly() {
        AsyncCallback cb = new AsyncCallback(URI.create("https://sangam.example/cb"), "sangam.result", AsyncCallback.Placement.HEADERS, null);
        assertFalse(http("GET", cb).readOnly(req("list", in())));
        assertFalse(http("GET", cb).readOnly(req("list", in("method", "GET"))));
    }

    // ------------------------------------------------------------------ JDBC: the resolved statement decides

    static JdbcConnector jdbc() {
        Map<String, NamedStatement> st = new LinkedHashMap<>();
        st.put("list", NamedStatement.parse("list", "select ref, due from payment_request where tenant = :tenantId",
                NamedStatement.Kind.QUERY));
        st.put("commented", NamedStatement.parse("commented", "/* vendors */ -- by tenant\n  SELECT ref FROM vendor;",
                NamedStatement.Kind.QUERY));
        st.put("record", NamedStatement.parse("record", "insert into payment_request (op_id) values (:operationId)",
                NamedStatement.Kind.UPDATE));
        st.put("purge", NamedStatement.parse("purge", "delete from payment_request where tenant = :tenantId",
                NamedStatement.Kind.UPDATE));
        st.put("insertReturning", NamedStatement.parse("insertReturning",
                "insert into payment_request (op_id) values (:operationId) returning op_id", NamedStatement.Kind.QUERY));
        st.put("cte", NamedStatement.parse("cte", "with d as (delete from payment_request returning ref) select ref from d",
                NamedStatement.Kind.QUERY));
        st.put("locking", NamedStatement.parse("locking", "select ref from payment_request for update",
                NamedStatement.Kind.QUERY));
        st.put("selectInto", NamedStatement.parse("selectInto", "select ref into archive from payment_request",
                NamedStatement.Kind.QUERY));
        st.put("two", NamedStatement.parse("two", "select 1; delete from payment_request", NamedStatement.Kind.QUERY));
        st.put("call", NamedStatement.parse("call", "call close_period(:tenantId)", NamedStatement.Kind.QUERY));
        st.put("selectAsUpdate", NamedStatement.parse("selectAsUpdate", "select ref from payment_request",
                NamedStatement.Kind.UPDATE));
        DataSource never = (DataSource) java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (p, m, a) -> {
                    throw new AssertionError("readOnly must not touch the database");
                });
        return new JdbcConnector(new JdbcConnector.Config("ledger-db", st, null, CapabilityClass.C_NEITHER, 10,
                Duration.ofSeconds(5), ConnectorProfile.LIVE), never);
    }

    @Test
    void jdbcIsReadOnlyOnlyWhenTheResolvedStatementIsADeclaredSelect() {
        JdbcConnector c = jdbc();
        record Row(String operation, Map<String, Object> input, boolean expected) {
        }
        List<Row> rows = List.of(
                new Row("list", in(), true),
                new Row("commented", in(), true),
                new Row("record", in(), false),
                new Row("purge", in(), false),
                // the payload's statement replaces the operation, exactly as in invoke
                new Row("list", in("statement", "purge"), false),
                new Row("list", in("statement", "record"), false),
                new Row("purge", in("statement", "list"), true),
                new Row("list", in("statement", "no-such-statement"), false),
                new Row("no-such-statement", in(), false),
                // a query that is not a plain select is not counted as a read
                new Row("insertReturning", in(), false),
                new Row("cte", in(), false),
                new Row("locking", in(), false),
                new Row("selectInto", in(), false),
                new Row("two", in(), false),
                new Row("call", in(), false),
                new Row("selectAsUpdate", in(), false));
        for (Row r : rows) {
            assertEquals(r.expected(), c.readOnly(req(r.operation(), r.input())), r.operation() + " " + r.input());
        }
    }
}
