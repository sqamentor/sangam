// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.sangam.assembly.host.Capability.Kind;

/**
 * The capability catalogue: the matrix of design note section 3.1 plus the host SPI seams of section 10.1, in one
 * place. Every host and every assembly area refers to these constants; nobody declares a second copy.
 *
 * <p>Some required capabilities apply only while a feature is on (the outbox relay and inbox with integration, the job
 * leases with the job executor, the money invariants with an opt-in money module). The assembly area that turns the
 * feature on adds them to the set it needs ({@link CapabilitySet#resolve}); {@link #required()} lists them all, which is
 * the strictest reading and the one the conformance kit checks.
 */
public final class Capabilities {

    private Capabilities() {
    }

    // ------------------------------------------------------------------ required (fail closed)

    /** The JVM runs on UTC; the core check is {@code EngineTimeZone}, run inside {@code start()}. */
    public static final Capability UTC_JVM = required("utc-jvm", "lifecycle",
            "start-up refused (the JVM default time zone is not UTC)");

    /** READ COMMITTED, the database guard and the SQL Server RCSI check. */
    public static final Capability DATABASE_CHECKS = required("database-checks", "transaction",
            "start-up refused (isolation level or database checks failed)");

    /** Liquibase 4.x creates the product schema before the engine starts. */
    public static final Capability SCHEMA_BEFORE_ENGINE = required("schema-before-engine", "engine",
            "start-up refused (the product schema must exist before the engine)");

    /** P-1: engine state and product rows of one command share one connection and commit once. */
    public static final Capability SHARED_TRANSACTION = required("shared-transaction", "transaction",
            "every command that writes product rows is refused; never two transactions");

    /** The tenant and seat engine plugin and the job identity binding. */
    public static final Capability TENANT_SECURITY_PLUGIN = required("tenant-security-plugin", "security",
            "start-up refused while any tenant-bearing module is on");

    /** Deny-wins authorization by the product security layer. */
    public static final Capability AUTHORIZATION = required("authorization", "security",
            "operations needing a permission are refused; there is no group-based fallback");

    /** The host turns its authenticated request into the product principal and request context for one call. */
    public static final Capability SECURITY_CONTEXT = required("security-context", "security-context",
            "no call reaches a port without a bound principal");

    /** The audit chain and the single outbox writer. */
    public static final Capability AUDIT_CHAIN = required("audit-chain", "integration",
            "start-up refused (no audit chain or outbox writer)");

    /** The idempotency store. */
    public static final Capability IDEMPOTENCY_STORE = required("idempotency-store", "integration",
            "commands carrying an Idempotency-Key are refused");

    /** The outbox relay and the inbox dedupe (while integration is on). */
    public static final Capability OUTBOX_RELAY_INBOX = required("outbox-relay-inbox", "integration",
            "publish and inbound are refused, never dropped");

    /** Job partition leases and fencing epochs (while the job executor is on). */
    public static final Capability JOB_LEASES = required("job-leases", "engine",
            "the job executor is refused to start");

    /** The FEEL engine runs on the Jackson and Scala versions it is built for. */
    public static final Capability JACKSON_FEEL = required("jackson-feel-compatibility", "engine",
            "build or start-up refused");

    /** Money and ledger invariants (while an opt-in money module is on). */
    public static final Capability MONEY_INVARIANTS = required("money-invariants", "modules",
            "the money module is refused");

    /** The config-source seam ({@link SangamProperties}). */
    public static final Capability CONFIG_SOURCE = required("config-source", "config-source",
            "start-up refused (no configuration source)");

    /** The lifecycle seam: the host calls {@code start()} and {@code stop(drain)} of {@link SangamRuntime}. */
    public static final Capability LIFECYCLE = required("lifecycle", "lifecycle",
            "start-up refused (without a stop hook nothing drains in order)");

    /** The scheduling seam: a {@link HostScheduler}, or the assembly's own standalone scheduler. */
    public static final Capability SCHEDULING = required("scheduling", "scheduling",
            "the job executor, relay and sweepers are refused to start");

    // ------------------------------------------------------------------ conveniences (reported when absent)

    /** The REST API generated from the published contract. */
    public static final Capability HTTP = convenience("http", "http",
            "no HTTP surface is offered; the Java API still authorizes");

    /** OIDC resource server and API tokens over HTTP. */
    public static final Capability OIDC_HTTP = convenience("oidc-http", "http",
            "no HTTP token authentication; the Java API still authorizes");

    /** Metrics and tracing. */
    public static final Capability METRICS_TRACING = convenience("metrics-tracing", "health",
            "metrics and tracing are off, which is logged");

    /** {@code sangam-defaults.yaml} as the lowest configuration source. */
    public static final Capability DEFAULTS_SOURCE = convenience("defaults-source", "config-source",
            "only the host's own configuration applies");

    /** The health seam ({@link HealthCheck} endpoints). */
    public static final Capability HEALTH = convenience("health", "health",
            "no health endpoint; the checks still gate start()");

    /** Async long-poll, the actuator and the web application. */
    public static final Capability WEB_EXTRAS = convenience("web-extras", "http",
            "long-poll, actuator and web application are not offered");

    private static final List<Capability> ALL = List.of(UTC_JVM, DATABASE_CHECKS, SCHEMA_BEFORE_ENGINE,
            SHARED_TRANSACTION, TENANT_SECURITY_PLUGIN, AUTHORIZATION, SECURITY_CONTEXT, AUDIT_CHAIN, IDEMPOTENCY_STORE,
            OUTBOX_RELAY_INBOX, JOB_LEASES, JACKSON_FEEL, MONEY_INVARIANTS, CONFIG_SOURCE, LIFECYCLE, SCHEDULING, HTTP,
            OIDC_HTTP, METRICS_TRACING, DEFAULTS_SOURCE, HEALTH, WEB_EXTRAS);

    /**
     * Owner decision 278: the conveniences only the Spring host offers in 0.1.0. The Quarkus host offers the ports and
     * the engine only: no REST controllers ({@link #HTTP}) and no OIDC over HTTP ({@link #OIDC_HTTP}); it reports both
     * absent and never claims them.
     */
    public static final Set<Capability> SPRING_ONLY = Set.of(HTTP, OIDC_HTTP);

    /** True when {@code c} is offered only by the Spring host in this release ({@link #SPRING_ONLY}). */
    public static boolean springOnly(Capability c) {
        return SPRING_ONLY.contains(c);
    }

    /** Every capability, required ones first, in catalogue order. */
    public static List<Capability> all() {
        return ALL;
    }

    /** Every required capability, in catalogue order. */
    public static List<Capability> required() {
        return ALL.stream().filter(Capability::required).toList();
    }

    /** Every convenience, in catalogue order. */
    public static List<Capability> conveniences() {
        return ALL.stream().filter(c -> !c.required()).toList();
    }

    /** The catalogue entry with {@code id}. */
    public static Optional<Capability> byId(String id) {
        return ALL.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    private static Capability required(String id, String seam, String whenMissing) {
        return new Capability(id, Kind.REQUIRED, seam, whenMissing);
    }

    private static Capability convenience(String id, String seam, String whenMissing) {
        return new Capability(id, Kind.CONVENIENCE, seam, whenMissing);
    }
}
