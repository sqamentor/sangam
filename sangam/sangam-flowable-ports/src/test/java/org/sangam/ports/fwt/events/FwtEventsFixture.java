// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.events;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.UniqueStartPort;
import org.sangam.ports.PortsSchema;
import org.sangam.ports.PortsSettings;
import org.sangam.ports.SangamPortsPlugin;
import org.sangam.ports.events.EventDispatcher;
import org.sangam.ports.events.EventLog;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.support.EngineTx;

/**
 * Event-registry test fixture for the FWT scenarios: an Operaton engine on H2 with
 * {@link SangamPortsPlugin} (event registry on), the core product schema (INBOX, OUTBOX ...), the ports schema and
 * WS-INTEGRATION's {@code UNIQUE_START} table. Intake ({@link InboundEventProcessor}) runs on product transactions over
 * a data source that can be taken "down" (every new connection fails, as when the database is unreachable); dispatch
 * runs the inbox loop inside engine commands, as production wires it.
 */
public final class FwtEventsFixture implements AutoCloseable {

    static final Dialect D = Dialect.POSTGRESQL;

    /** A data source whose connections fail while {@link #down} is set (database unreachable). */
    public static final class SwitchableDataSource implements DataSource {
        private final DataSource target;
        public volatile boolean down;

        SwitchableDataSource(DataSource target) {
            this.target = target;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (down) {
                throw new SQLException("Connection refused (database down)", "08001");
            }
            return target.getConnection();
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return target.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            target.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            target.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return target.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return target.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return target.isWrapperFor(iface);
        }
    }

    /** JDBC {@code UNIQUE_START} guard with WS-INTEGRATION's table shape. */
    static final class JdbcUniqueStart implements UniqueStartPort {
        @Override
        public boolean claim(Connection c, String tenantId, String definitionKey, String businessKey, String source, String messageId) {
            return Jdbc.insertIfAbsent(c, D, "INSERT INTO UNIQUE_START(tenant_id, definition_key, business_key, instance_id, source,"
                    + " message_id, created_at) VALUES (?,?,?,NULL,?,?," + D.now() + ")",
                    tenantId == null ? "" : tenantId, definitionKey, businessKey, source, messageId);
        }

        @Override
        public void bind(Connection c, String tenantId, String definitionKey, String businessKey, String instanceId) {
            Jdbc.update(c, "UPDATE UNIQUE_START SET instance_id = ? WHERE tenant_id = ? AND definition_key = ? AND business_key = ?",
                    instanceId, tenantId == null ? "" : tenantId, definitionKey, businessKey);
        }

        @Override
        public Optional<String> existingInstance(Connection c, String tenantId, String definitionKey, String businessKey) {
            return Jdbc.one(c, "SELECT instance_id FROM UNIQUE_START WHERE tenant_id = ? AND definition_key = ? AND business_key = ?",
                    rs -> rs.getString(1), tenantId == null ? "" : tenantId, definitionKey, businessKey);
        }
    }

    public final JdbcDataSource h2;
    public final SwitchableDataSource productDs;
    public final ProcessEngine engine;
    final SangamPortsPlugin plugin;
    public final EventRegistry registry;
    public final EventLog log;
    public final InboundEventProcessor processor;
    final Inbox dispatchInbox;
    final EventDispatcher dispatcher;

    public FwtEventsFixture() {
        this(InboundEventProcessor.DEFAULT_MAX_PAYLOAD_BYTES);
    }

    public FwtEventsFixture(int maxPayloadBytes) {
        h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:fwt" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        productDs = new SwitchableDataSource(h2);
        PortsSettings settings = new PortsSettings();
        settings.setDialect(D);
        plugin = new SangamPortsPlugin(settings, null);
        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setDataSource(h2);
        cfg.setDatabaseSchemaUpdate("true");
        cfg.setJobExecutorActivate(false);
        cfg.setEnforceHistoryTimeToLive(false);
        cfg.setProcessEnginePlugins(List.of(plugin));
        // Operaton's H2 create script needs H2's regular mode (BLOB); the product layer runs in MODE=PostgreSQL, as
        // Sangam requires on H2 (sangam-engine DatabaseGuard): engine schema first, then the mode switch.
        engine = cfg.buildProcessEngine();
        try (Connection c = h2.getConnection()) {
            Jdbc.update(c, "SET MODE PostgreSQL");
            Schema.create(c, D);
            PortsSchema.create(c, D);
            Jdbc.update(c, D.expand("""
                    CREATE TABLE UNIQUE_START (
                      tenant_id       {str64} NOT NULL,
                      definition_key  {str} NOT NULL,
                      business_key    {str} NOT NULL,
                      instance_id     {str64},
                      source          {str64},
                      message_id      {str},
                      created_at      {ts} NOT NULL,
                      PRIMARY KEY (tenant_id, definition_key, business_key)
                    )"""));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        registry = plugin.eventRegistry();
        log = new EventLog(D, true);
        processor = new InboundEventProcessor(registry, new Inbox(D, Transactions.of(productDs), "node-1",
                Duration.ofMinutes(1), Duration.ZERO, null), log, Transactions.of(productDs), maxPayloadBytes);
        dispatchInbox = new Inbox(D, EngineTx.transactions(engine), "node-1", Duration.ofMinutes(1), Duration.ZERO, null);
        dispatcher = new EventDispatcher(engine, registry, log, plugin.links(), new JdbcUniqueStart());
    }

    /** One pass of the inbox loop (engine commands); returns the number of events PROCESSED. */
    public int dispatch() {
        return dispatchInbox.processOnce(1000, dispatcher);
    }

    void deploy(String resource, String bpmn) {
        engine.getRepositoryService().createDeployment().addString(resource, bpmn).deploy();
    }

    void deploy(String tenant, String resource, String bpmn) {
        engine.getRepositoryService().createDeployment().tenantId(tenant).addString(resource, bpmn).deploy();
    }

    public long count(String sql, Object... params) {
        try (Connection c = h2.getConnection()) {
            return Jdbc.one(c, sql, rs -> rs.getLong(1), params).orElse(0L);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> strings(String sql, Object... params) {
        try (Connection c = h2.getConnection()) {
            return Jdbc.query(c, sql, rs -> rs.getString(1), params);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    Inbox.State inboxState(String tenant, String channelKey, String messageId) {
        try (Connection c = h2.getConnection()) {
            return Inbox.state(c, tenant, InboundEventProcessor.SOURCE_PREFIX + channelKey, messageId);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String webhookChannel(String key, String extra) {
        return "{\"key\":\"" + key + "\",\"channelType\":\"webhook\",\"type\":\"inbound\",\"messageIdJsonPointer\":\"/id\""
                + (extra == null || extra.isEmpty() ? "" : "," + extra) + "}";
    }

    static Map<String, String> noHeaders() {
        return Map.of();
    }

    /** A process waiting at a message catch named {@code message} (business key and variables set at start). */
    static String catchModel(String key, String message) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <message id="m1" name="%2$s"/>
                  <process id="%1$s" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                    <intermediateCatchEvent id="wait"><messageEventDefinition messageRef="m1"/></intermediateCatchEvent>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>
                    <userTask id="after"/>
                    <sequenceFlow id="f3" sourceRef="after" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key, message);
    }

    @Override
    public void close() {
        engine.close();
    }
}
