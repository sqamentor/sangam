// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.graphql.GraphQlConnector;
import org.sangam.integration.connector.http.LiveHttpConnectors;
import org.sangam.integration.connector.jdbc.JdbcConnector;
import org.sangam.integration.connector.jdbc.NamedStatement;
import org.sangam.integration.connector.s3.S3Connector;
import org.sangam.integration.connector.s3.S3ObjectClient;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.server.bridge.ConfigConnectorBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

/**
 * M-105 LIVE connectors beyond plain HTTP, registered per tenant on the integration {@link ConnectorRegistry} exactly like
 * {@link HttpConnectorsWiring}: a connector that cannot be built fails start-up (never a silently missing connector),
 * and nothing is configured by default.
 *
 * <ul>
 *   <li>{@code sangam.connectors.graphql.tenants.<tenant>.<id>}: every key of an HTTP connector endpoint
 *       ({@code base-url} is the GraphQL endpoint, {@code auth}, {@code tls}, {@code egress}, ...) plus
 *       {@code documents.<name>} (reviewed operations; when set, only they run).</li>
 *   <li>{@code sangam.connectors.s3.tenants.<tenant>.<id>}: {@code endpoint}, {@code bucket}, {@code region} (default
 *       {@code us-east-1}), {@code path-style} (default true), {@code key-prefix}, {@code access-key-ref},
 *       {@code secret-key-ref}, {@code session-token-ref}, {@code allow-private}, {@code max-put-bytes},
 *       {@code max-response-bytes}, {@code connect-timeout}, {@code read-timeout}. The egress rule admits exactly the
 *       bucket's origin.</li>
 *   <li>{@code sangam.connectors.jdbc.tenants.<tenant>.<id>}: {@code url}, {@code username}, {@code password-ref},
 *       {@code statements.<name>.sql} and {@code .kind} ({@code query} or {@code update}), {@code lookup-sql},
 *       {@code capability-class}, {@code max-rows}, {@code query-timeout}, {@code pool-size}. The JDBC driver is the
 *       operator's (not shipped); the pool does not connect at start-up, so a database that is down makes calls fail
 *       without effect instead of stopping the server. The password is read once when the pool is built.</li>
 * </ul>
 * Every connector also takes {@code pack-bindings} like the HTTP connectors. On a production profile credentials must be
 * {@code secret://} references and URLs https.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectorBreadthWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorBreadthWiring.class);
    static final String GRAPHQL = "sangam.connectors.graphql.tenants";
    static final String S3 = "sangam.connectors.s3.tenants";
    static final String JDBC = "sangam.connectors.jdbc.tenants";

    /** A GraphQL endpoint: an HTTP endpoint plus its reviewed documents. */
    public static class GraphQlEndpoint extends HttpConnectorsProperties.Endpoint {
        private Map<String, String> documents = new LinkedHashMap<>();

        public Map<String, String> getDocuments() { return documents; }

        public void setDocuments(Map<String, String> documents) { this.documents = documents; }
    }

    /** One bucket. */
    public static class S3Endpoint {
        private String endpoint;
        private String bucket;
        private String region = "us-east-1";
        private boolean pathStyle = true;
        private String keyPrefix;
        private String accessKeyRef;
        private String secretKeyRef;
        private String sessionTokenRef;
        private boolean allowPrivate;
        private long maxPutBytes;
        private long maxResponseBytes = 64L * 1024 * 1024;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(60);
        private Map<String, String> packBindings = new LinkedHashMap<>();

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String v) { endpoint = v; }
        public String getBucket() { return bucket; }
        public void setBucket(String v) { bucket = v; }
        public String getRegion() { return region; }
        public void setRegion(String v) { region = v; }
        public boolean isPathStyle() { return pathStyle; }
        public void setPathStyle(boolean v) { pathStyle = v; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String v) { keyPrefix = v; }
        public String getAccessKeyRef() { return accessKeyRef; }
        public void setAccessKeyRef(String v) { accessKeyRef = v; }
        public String getSecretKeyRef() { return secretKeyRef; }
        public void setSecretKeyRef(String v) { secretKeyRef = v; }
        public String getSessionTokenRef() { return sessionTokenRef; }
        public void setSessionTokenRef(String v) { sessionTokenRef = v; }
        public boolean isAllowPrivate() { return allowPrivate; }
        public void setAllowPrivate(boolean v) { allowPrivate = v; }
        public long getMaxPutBytes() { return maxPutBytes; }
        public void setMaxPutBytes(long v) { maxPutBytes = v; }
        public long getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(long v) { maxResponseBytes = v; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration v) { connectTimeout = v; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration v) { readTimeout = v; }
        public Map<String, String> getPackBindings() { return packBindings; }
        public void setPackBindings(Map<String, String> v) { packBindings = v; }
    }

    /** One configured statement. */
    public static class StatementSpec {
        private String sql;
        private String kind;

        public String getSql() { return sql; }
        public void setSql(String v) { sql = v; }
        public String getKind() { return kind; }
        public void setKind(String v) { kind = v; }
    }

    /** One external database. */
    public static class JdbcEndpoint {
        private String url;
        private String username;
        private String passwordRef;
        private Map<String, StatementSpec> statements = new LinkedHashMap<>();
        private String lookupSql;
        private String capabilityClass;
        private int maxRows = 1000;
        private Duration queryTimeout = Duration.ofSeconds(30);
        private int poolSize = 4;
        private Map<String, String> packBindings = new LinkedHashMap<>();

        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getUsername() { return username; }
        public void setUsername(String v) { username = v; }
        public String getPasswordRef() { return passwordRef; }
        public void setPasswordRef(String v) { passwordRef = v; }
        public Map<String, StatementSpec> getStatements() { return statements; }
        public void setStatements(Map<String, StatementSpec> v) { statements = v; }
        public String getLookupSql() { return lookupSql; }
        public void setLookupSql(String v) { lookupSql = v; }
        public String getCapabilityClass() { return capabilityClass; }
        public void setCapabilityClass(String v) { capabilityClass = v; }
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int v) { maxRows = v; }
        public Duration getQueryTimeout() { return queryTimeout; }
        public void setQueryTimeout(Duration v) { queryTimeout = v; }
        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int v) { poolSize = v; }
        public Map<String, String> getPackBindings() { return packBindings; }
        public void setPackBindings(Map<String, String> v) { packBindings = v; }
    }

    /** The JDBC connectors' pools, closed with the context. */
    public static final class JdbcPools implements AutoCloseable {
        private final List<HikariDataSource> pools = new ArrayList<>();

        synchronized HikariDataSource add(HikariDataSource ds) {
            pools.add(ds);
            return ds;
        }

        @Override
        public synchronized void close() {
            pools.forEach(HikariDataSource::close);
            pools.clear();
        }
    }

    @Bean(destroyMethod = "close")
    public JdbcPools sangamJdbcConnectorPools() {
        return new JdbcPools();
    }

    @Bean
    public InitializingBean sangamConnectorBreadthRegistration(Environment env, ObjectProvider<ConnectorRegistry> registry,
            ObjectProvider<PackConnectorBindings> bindings, ObjectProvider<SecretResolver> secrets, JdbcPools pools) {
        return () -> {
            Map<String, Map<String, GraphQlEndpoint>> graphql = bind(env, GRAPHQL, GraphQlEndpoint.class);
            Map<String, Map<String, S3Endpoint>> s3 = bind(env, S3, S3Endpoint.class);
            Map<String, Map<String, JdbcEndpoint>> jdbc = bind(env, JDBC, JdbcEndpoint.class);
            if (graphql.isEmpty() && s3.isEmpty() && jdbc.isEmpty()) {
                return;
            }
            boolean production = ProductionEnvironment.isProduction(env);
            SecretResolver resolver = secrets.getIfAvailable(SecretResolver::fromEnvironment);
            ConfigConnectorBinder binder = new ConfigConnectorBinder(registry, bindings);
            int n = 0;
            for (var t : graphql.entrySet()) {
                for (var e : t.getValue().entrySet()) {
                    register(binder, t.getKey(), graphQl(t.getKey(), e.getKey(), e.getValue(), resolver, production),
                            e.getValue().getPackBindings());
                    n++;
                }
            }
            for (var t : s3.entrySet()) {
                for (var e : t.getValue().entrySet()) {
                    register(binder, t.getKey(), s3(t.getKey(), e.getKey(), e.getValue(), resolver, production),
                            e.getValue().getPackBindings());
                    n++;
                }
            }
            for (var t : jdbc.entrySet()) {
                for (var e : t.getValue().entrySet()) {
                    register(binder, t.getKey(), jdbc(t.getKey(), e.getKey(), e.getValue(), resolver, production, pools),
                            e.getValue().getPackBindings());
                    n++;
                }
            }
            LOG.info("M-105 LIVE GraphQL/S3/JDBC connectors registered: {}", n);
        };
    }

    private static void register(ConfigConnectorBinder binder, String tenant, SangamConnector c, Map<String, String> packs) {
        if (!binder.register(tenant, c)) {
            throw new IllegalStateException("sangam.connectors (graphql/s3/jdbc) is configured but sangam-integration "
                    + "(the connector registry) is not available");
        }
        if (packs != null && !packs.isEmpty()) {
            Map<String, String> ops = new LinkedHashMap<>();
            packs.forEach((k, v) -> ops.put(k, v == null || v.isBlank() ? null : v));
            binder.bind(c.descriptor().id(), ops);
        }
    }

    static <T> Map<String, Map<String, T>> bind(Environment env, String prefix, Class<T> type) {
        ResolvableType inner = ResolvableType.forClassWithGenerics(Map.class, ResolvableType.forClass(String.class),
                ResolvableType.forClass(type));
        ResolvableType outer = ResolvableType.forClassWithGenerics(Map.class, ResolvableType.forClass(String.class), inner);
        Bindable<Map<String, Map<String, T>>> b = Bindable.of(outer);
        return Binder.get(env).bind(prefix, b).orElse(Map.of());
    }

    static GraphQlConnector graphQl(String tenant, String id, GraphQlEndpoint e, SecretResolver secrets, boolean production) {
        try {
            return new GraphQlConnector(LiveHttpConnectors.build(tenant, HttpConnectorsWiring.spec(id, e), secrets, production),
                    e.getDocuments());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("connector " + tenant + "/" + id + ": " + ex.getMessage(), ex);
        }
    }

    static S3Connector s3(String tenant, String id, S3Endpoint e, SecretResolver secrets, boolean production) {
        String where = "connector " + tenant + "/" + id;
        S3ObjectClient.Settings s;
        try {
            if (e.getEndpoint() == null || e.getEndpoint().isBlank()) {
                throw new IllegalArgumentException("endpoint is required");
            }
            s = new S3ObjectClient.Settings(URI.create(e.getEndpoint().trim()), e.getBucket(), e.getRegion(),
                    e.isPathStyle(), e.getAccessKeyRef(), e.getSecretKeyRef(), e.getSessionTokenRef());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(where + ": " + ex.getMessage(), ex);
        }
        if (production && !"https".equalsIgnoreCase(s.endpoint().getScheme())) {
            throw new IllegalStateException(where + ": endpoint must be https on a production profile");
        }
        secret(where, "access-key-ref", s.accessKeyRef(), secrets, production);
        secret(where, "secret-key-ref", s.secretKeyRef(), secrets, production);
        if (s.sessionTokenRef() != null) {
            secret(where, "session-token-ref", s.sessionTokenRef(), secrets, production);
        }
        EgressPolicy egress = EgressPolicy.of(List.of(EgressPolicy.Rule.parse(tenant, s.origin(), e.isAllowPrivate())), 0,
                e.getMaxResponseBytes() <= 0 ? 64L * 1024 * 1024 : e.getMaxResponseBytes());
        HttpTransport transport = new HttpTransport(egress, null, e.getConnectTimeout(), e.getReadTimeout(),
                RetryPolicy.none());
        try {
            return new S3Connector(new S3Connector.Config(id, e.getKeyPrefix(), e.getMaxPutBytes(), ConnectorProfile.LIVE),
                    new S3ObjectClient(s, transport, secrets, Clock.systemUTC()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(where + ": " + ex.getMessage(), ex);
        }
    }

    static JdbcConnector jdbc(String tenant, String id, JdbcEndpoint e, SecretResolver secrets, boolean production,
                              JdbcPools pools) {
        String where = "connector " + tenant + "/" + id;
        if (e.getUrl() == null || !e.getUrl().trim().startsWith("jdbc:")) {
            throw new IllegalStateException(where + ": url must be a jdbc: URL");
        }
        JdbcConnector.Config config;
        try {
            Map<String, NamedStatement> statements = new LinkedHashMap<>();
            e.getStatements().forEach((name, st) -> statements.put(name, NamedStatement.parse(name, st.getSql(),
                    NamedStatement.kind(st.getKind()))));
            NamedStatement lookup = e.getLookupSql() == null || e.getLookupSql().isBlank() ? null
                    : NamedStatement.parse("lookup", e.getLookupSql(), NamedStatement.Kind.QUERY);
            CapabilityClass cls = e.getCapabilityClass() == null || e.getCapabilityClass().isBlank()
                    ? CapabilityClass.C_NEITHER : CapabilityClass.fromDeclaration(e.getCapabilityClass());
            config = new JdbcConnector.Config(id, statements, lookup, cls, e.getMaxRows(), e.getQueryTimeout(),
                    ConnectorProfile.LIVE);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(where + ": " + ex.getMessage(), ex);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(e.getUrl().trim());
        if (e.getUsername() != null && !e.getUsername().isBlank()) {
            cfg.setUsername(e.getUsername());
        }
        if (e.getPasswordRef() != null && !e.getPasswordRef().isBlank()) {
            cfg.setPassword(secret(where, "password-ref", e.getPasswordRef(), secrets, production));
        } else if (production) {
            throw new IllegalStateException(where + ": password-ref is required on a production profile");
        }
        cfg.setMaximumPoolSize(Math.max(1, e.getPoolSize()));
        cfg.setPoolName("sangam-connector-" + tenant + "-" + id);
        cfg.setInitializationFailTimeout(-1);   // never block or fail start-up on the external database
        return new JdbcConnector(config, pools.add(new HikariDataSource(cfg)));
    }

    /** A credential reference that must resolve to a non-empty value (a literal only off production profiles). */
    private static String secret(String where, String name, String ref, SecretResolver secrets, boolean production) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalStateException(where + ": " + name + " is required");
        }
        if (production && !ref.toLowerCase(Locale.ROOT).startsWith("secret://")) {
            throw new IllegalStateException(where + ": " + name + " must be a secret:// reference on a production profile");
        }
        String v;
        try {
            v = secrets.require(ref);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(where + ": " + name + " cannot be resolved (" + ex.getMessage() + ")");
        }
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(where + ": " + name + " resolves to an empty value");
        }
        return v;
    }
}
