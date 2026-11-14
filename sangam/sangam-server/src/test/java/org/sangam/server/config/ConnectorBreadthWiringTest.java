// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.graphql.GraphQlConnector;
import org.sangam.integration.connector.jdbc.JdbcConnector;
import org.sangam.integration.connector.s3.S3Connector;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-105 wiring of the GraphQL, S3 and JDBC connectors: nothing is bound by default, the per-tenant maps bind from
 * properties, and a connector that cannot be built (missing endpoint, plain http or a literal credential on a production
 * profile, unresolvable reference, invalid SQL) stops start-up with a message naming the tenant and connector.
 */
class ConnectorBreadthWiringTest {

    static final SecretResolver SECRETS = SecretResolver.of(Map.of(
            "secret://s3/access", "AKIDEXAMPLE", "secret://s3/secret", "s3cr3t", "secret://db/pw", "pw"));

    @Test
    void nothingIsBoundByDefaultAndTheTenantMapsBindFromProperties() {
        assertTrue(ConnectorBreadthWiring.bind(new MockEnvironment(), ConnectorBreadthWiring.S3,
                ConnectorBreadthWiring.S3Endpoint.class).isEmpty());
        MockEnvironment env = new MockEnvironment()
                .withProperty("sangam.connectors.s3.tenants.t1.files.endpoint", "https://s3.example")
                .withProperty("sangam.connectors.s3.tenants.t1.files.bucket", "docs")
                .withProperty("sangam.connectors.s3.tenants.t1.files.path-style", "false")
                .withProperty("sangam.connectors.jdbc.tenants.t1.erp.url", "jdbc:h2:mem:erp")
                .withProperty("sangam.connectors.jdbc.tenants.t1.erp.statements.find.sql", "select 1 where :a = 1")
                .withProperty("sangam.connectors.jdbc.tenants.t1.erp.statements.find.kind", "query")
                .withProperty("sangam.connectors.graphql.tenants.t2.shop.base-url", "https://shop.example/graphql")
                .withProperty("sangam.connectors.graphql.tenants.t2.shop.documents.order", "query { order { id } }");
        var s3 = ConnectorBreadthWiring.bind(env, ConnectorBreadthWiring.S3, ConnectorBreadthWiring.S3Endpoint.class);
        assertEquals("docs", s3.get("t1").get("files").getBucket());
        assertEquals(false, s3.get("t1").get("files").isPathStyle());
        assertEquals("us-east-1", s3.get("t1").get("files").getRegion());
        var jdbc = ConnectorBreadthWiring.bind(env, ConnectorBreadthWiring.JDBC, ConnectorBreadthWiring.JdbcEndpoint.class);
        assertEquals("select 1 where :a = 1", jdbc.get("t1").get("erp").getStatements().get("find").getSql());
        var gql = ConnectorBreadthWiring.bind(env, ConnectorBreadthWiring.GRAPHQL,
                ConnectorBreadthWiring.GraphQlEndpoint.class);
        assertEquals(Map.of("order", "query { order { id } }"), gql.get("t2").get("shop").getDocuments());
    }

    static ConnectorBreadthWiring.S3Endpoint s3(String endpoint, String access, String secret) {
        ConnectorBreadthWiring.S3Endpoint e = new ConnectorBreadthWiring.S3Endpoint();
        e.setEndpoint(endpoint);
        e.setBucket("docs");
        e.setAccessKeyRef(access);
        e.setSecretKeyRef(secret);
        return e;
    }

    @Test
    void anS3ConnectorIsBuiltOnlyWithAnEndpointResolvableCredentialsAndHttpsInProduction() {
        S3Connector c = ConnectorBreadthWiring.s3("t1", "files",
                s3("https://s3.example", "secret://s3/access", "secret://s3/secret"), SECRETS, true);
        assertEquals("files", c.descriptor().id());
        assertEquals(CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, c.descriptor().capabilityClass());

        assertThrows(IllegalStateException.class, () -> ConnectorBreadthWiring.s3("t1", "files",
                s3(null, "secret://s3/access", "secret://s3/secret"), SECRETS, false));
        assertThrows(IllegalStateException.class, () -> ConnectorBreadthWiring.s3("t1", "files",
                s3("http://minio.internal:9000", "secret://s3/access", "secret://s3/secret"), SECRETS, true));
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> ConnectorBreadthWiring.s3("t1",
                "files", s3("https://s3.example", "secret://s3/access", "secret://s3/other"), SECRETS, false));
        assertTrue(missing.getMessage().startsWith("connector t1/files: secret-key-ref"), missing.getMessage());
        assertThrows(IllegalStateException.class, () -> ConnectorBreadthWiring.s3("t1", "files",
                s3("https://s3.example", "AKIDLITERAL", "secret://s3/secret"), SECRETS, true),
                "a literal credential is refused on a production profile");
    }

    static ConnectorBreadthWiring.JdbcEndpoint jdbc(String sql, String kind) {
        ConnectorBreadthWiring.JdbcEndpoint e = new ConnectorBreadthWiring.JdbcEndpoint();
        e.setUrl("jdbc:h2:mem:wiring");
        e.setPasswordRef("secret://db/pw");
        ConnectorBreadthWiring.StatementSpec st = new ConnectorBreadthWiring.StatementSpec();
        st.setSql(sql);
        st.setKind(kind);
        e.setStatements(Map.of("find", st));
        return e;
    }

    @Test
    void aJdbcConnectorNeedsAJdbcUrlValidStatementsAndAPasswordReferenceInProduction() {
        try (ConnectorBreadthWiring.JdbcPools pools = new ConnectorBreadthWiring.JdbcPools()) {
            JdbcConnector c = ConnectorBreadthWiring.jdbc("t1", "erp", jdbc("select 1 where :a = 1", "query"), SECRETS,
                    true, pools);
            assertEquals(List.of("find"), c.descriptor().operations());
            assertEquals(CapabilityClass.C_NEITHER, c.descriptor().capabilityClass(), "class (c) unless declared");

            assertThrows(IllegalStateException.class, () -> ConnectorBreadthWiring.jdbc("t1", "erp",
                    jdbc("select * from t where a = ?", "query"), SECRETS, false, pools));
            assertThrows(IllegalStateException.class, () -> ConnectorBreadthWiring.jdbc("t1", "erp",
                    jdbc("select 1", "call"), SECRETS, false, pools));
            ConnectorBreadthWiring.JdbcEndpoint notJdbc = jdbc("select 1", "query");
            notJdbc.setUrl("postgres://db");
            assertThrows(IllegalStateException.class, () -> ConnectorBreadthWiring.jdbc("t1", "erp", notJdbc, SECRETS,
                    false, pools));
            ConnectorBreadthWiring.JdbcEndpoint noPassword = jdbc("select 1", "query");
            noPassword.setPasswordRef(null);
            assertThrows(IllegalStateException.class, () -> ConnectorBreadthWiring.jdbc("t1", "erp", noPassword, SECRETS,
                    true, pools));
            ConnectorBreadthWiring.JdbcEndpoint classB = jdbc("select 1", "query");
            classB.setCapabilityClass("b");
            assertThrows(IllegalStateException.class, () -> ConnectorBreadthWiring.jdbc("t1", "erp", classB, SECRETS,
                    false, pools), "class (b) without a lookup statement");
        }
    }

    @Test
    void aGraphQlConnectorWrapsTheHttpEndpointAndKeepsItsDocuments() {
        ConnectorBreadthWiring.GraphQlEndpoint e = new ConnectorBreadthWiring.GraphQlEndpoint();
        e.setBaseUrl("https://shop.example/graphql");
        e.setDocuments(Map.of("order", "query { order { id } }"));
        GraphQlConnector c = ConnectorBreadthWiring.graphQl("t2", "shop", e, SECRETS, true);
        assertEquals("graphql", c.descriptor().type());
        assertEquals(List.of("order"), c.descriptor().operations());

        ConnectorBreadthWiring.GraphQlEndpoint plain = new ConnectorBreadthWiring.GraphQlEndpoint();
        plain.setBaseUrl("http://shop.example/graphql");
        assertThrows(IllegalStateException.class, () -> ConnectorBreadthWiring.graphQl("t2", "shop", plain, SECRETS, true));
    }
}
