// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.flow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.sangam.core.layer.Dialect;
import org.sangam.onboarding.api.OnboardingRun;
import org.sangam.onboarding.support.OnboardingJson;

/** Persistence of runs. A save commits on its own connection: step state survives a rolled-back engine job. */
public interface OnboardingStore {

    void save(OnboardingRun run);

    Optional<OnboardingRun> find(String runId);

    /** Runs of a tenant, newest first; a null tenant lists all. */
    List<OnboardingRun> list(String tenantId);

    /** DDL with {@link Dialect} type tokens; the integrator copies it to the Liquibase changelogs. */
    List<String> TEMPLATES = List.of("""
            CREATE TABLE ONB_RUN (
              run_id        {str64} NOT NULL PRIMARY KEY,
              tenant_id     {str64} NOT NULL,
              status        {str64} NOT NULL,
              updated_ms    {long} NOT NULL,
              document_json {text} NOT NULL
            )""", "CREATE INDEX ONB_RUN_TENANT ON ONB_RUN (tenant_id, updated_ms)");

    /** Creates the tables with the dialect's types. */
    static void create(Connection c, Dialect dialect) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String t : TEMPLATES) {
                String sql = dialect.expand(t);
                st.execute(sql.startsWith("CREATE TABLE") ? sql + dialect.createTableSuffix() : sql);
            }
        }
    }

    /** JDBC store; portable SQL (update, then insert when no row changed). */
    final class Jdbc implements OnboardingStore {
        private final DataSource ds;

        public Jdbc(DataSource ds) {
            this.ds = ds;
        }

        @Override
        public void save(OnboardingRun r) {
            String json = OnboardingJson.write(r);
            long ms = r.updatedAt().toEpochMilli();
            try (Connection c = ds.getConnection()) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(true);
                try (PreparedStatement u = c.prepareStatement(
                        "UPDATE ONB_RUN SET status=?, updated_ms=?, document_json=? WHERE run_id=?")) {
                    u.setString(1, r.status().name());
                    u.setLong(2, ms);
                    u.setString(3, json);
                    u.setString(4, r.id());
                    if (u.executeUpdate() == 0) {
                        try (PreparedStatement i = c.prepareStatement("INSERT INTO ONB_RUN(run_id, tenant_id, status,"
                                + " updated_ms, document_json) VALUES (?,?,?,?,?)")) {
                            i.setString(1, r.id());
                            i.setString(2, r.tenantId());
                            i.setString(3, r.status().name());
                            i.setLong(4, ms);
                            i.setString(5, json);
                            i.executeUpdate();
                        }
                    }
                } finally {
                    c.setAutoCommit(auto);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("cannot save onboarding run " + r.id(), e);
            }
        }

        @Override
        public Optional<OnboardingRun> find(String runId) {
            List<OnboardingRun> l = query("SELECT document_json FROM ONB_RUN WHERE run_id=?", runId);
            return l.stream().findFirst();
        }

        @Override
        public List<OnboardingRun> list(String tenantId) {
            return tenantId == null ? query("SELECT document_json FROM ONB_RUN ORDER BY updated_ms DESC", null)
                    : query("SELECT document_json FROM ONB_RUN WHERE tenant_id=? ORDER BY updated_ms DESC", tenantId);
        }

        private List<OnboardingRun> query(String sql, String arg) {
            try (Connection c = ds.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
                if (arg != null) {
                    p.setString(1, arg);
                }
                List<OnboardingRun> out = new ArrayList<>();
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        out.add(OnboardingJson.read(rs.getString(1), OnboardingRun.class));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw new IllegalStateException("cannot read onboarding runs", e);
            }
        }
    }

    /** For tests. */
    final class InMemory implements OnboardingStore {
        private final Map<String, OnboardingRun> runs = new ConcurrentHashMap<>();

        @Override
        public void save(OnboardingRun run) {
            runs.put(run.id(), run);
        }

        @Override
        public Optional<OnboardingRun> find(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public List<OnboardingRun> list(String tenantId) {
            return runs.values().stream().filter(r -> tenantId == null || tenantId.equals(r.tenantId()))
                    .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt())).toList();
        }
    }
}
