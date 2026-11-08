// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.acceptance.AbstractAcceptanceIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Base of the Stage B contract ports (E2, E3, E4, E5, E7 and the product-layer specs) on the real Sangam server: the
 * server's DataSource and Liquibase schema, its {@link EngineAdapter} bean (sangam-engine's hardened Operaton adapter)
 * and its REST API with the acceptance dev token (tenant {@code acc}). Every key a test writes carries {@link #RUN}, so
 * the ports never collide with other acceptance tests in the same JVM.
 */
abstract class ContractSupport extends AbstractAcceptanceIT {

    /** Unique per JVM: prefixes tenants, business keys, topics, process keys. */
    static final String RUN = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    /** The dev token's tenant: every REST call acts in it. */
    static final String REST_TENANT = "acc";
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    DataSource dataSource;

    @Autowired
    EngineAdapter engine;

    @Autowired
    ProcessEngine processEngine;

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    static String key(String local) {
        return RUN + "-" + local;
    }

    private Dialect dialect;

    Dialect dialect() {
        if (dialect == null) {
            dialect = TxSupport.detectDialect(dataSource);
        }
        return dialect;
    }

    /** Product-only transaction on the server's pool (outside any engine command). */
    <T> T inTx(Function<Connection, T> work) {
        return Transactions.of(dataSource).inTransaction(work);
    }

    /** One engine command; product rows commit with the engine state (Q1). */
    <T> T inEngineTx(Function<Connection, T> work) {
        return engine.inEngineTransaction(work);
    }

    void runEngineTx(java.util.function.Consumer<Connection> work) {
        engine.inEngineTransaction(c -> {
            work.accept(c);
            return null;
        });
    }

    long count(String sql, Object... params) {
        try (Connection c = dataSource.getConnection()) {
            return Jdbc.one(c, sql, rs -> rs.getLong(1), params).orElse(0L);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    List<String> strings(String sql, Object... params) {
        try (Connection c = dataSource.getConnection()) {
            return Jdbc.query(c, sql, rs -> rs.getString(1), params);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    void exec(String sql, Object... params) {
        try (Connection c = dataSource.getConnection()) {
            Jdbc.update(c, sql, params);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    String deploy(String tenant, String resourceName, String bpmnXml) {
        return engine.deploy(tenant, key("dep-" + resourceName),
                Map.of(resourceName, bpmnXml.getBytes(StandardCharsets.UTF_8)));
    }

    long instancesWithBusinessKey(String businessKey) {
        return count("SELECT count(*) FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_ = ?", businessKey);
    }

    long runtimeInstancesWithBusinessKey(String businessKey) {
        return count("SELECT count(*) FROM ACT_RU_EXECUTION WHERE BUSINESS_KEY_ = ? AND PARENT_ID_ IS NULL", businessKey);
    }

    // ------------------------------------------------------------------ REST (dev token, tenant acc)

    record Rest(int status, Map<String, List<String>> headers, String body) {
        JsonNode json() {
            try {
                return body == null || body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("not JSON (" + status + "): " + body, e);
            }
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                    return e.getValue().isEmpty() ? null : e.getValue().get(0);
                }
            }
            return null;
        }

        String code() {
            JsonNode n = json();
            return n.hasNonNull("code") ? n.get("code").asText() : null;
        }
    }

    Rest post(String path, Object body, String... headers) {
        return send("POST", path, body, headers);
    }

    Rest get(String path) {
        return send("GET", path, null);
    }

    Rest delete(String path) {
        return send("DELETE", path, null);
    }

    Rest send(String method, String path, Object body, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", "application/json");
            boolean ownAuth = false;
            for (int i = 0; i + 1 < headers.length; i += 2) {
                ownAuth |= "Authorization".equalsIgnoreCase(headers[i]);
            }
            if (!ownAuth) {
                b.header("Authorization", "Bearer " + bearer(REST_TENANT, "acc-admin", ADMIN_GROUPS));
            }
            for (int i = 0; i + 1 < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Rest(r.statusCode(), r.headers().map(), r.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
