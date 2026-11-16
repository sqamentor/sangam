// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.Capability;
import org.sangam.assembly.host.HttpBinding;
import org.sangam.host.conformance.HostHarness;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.sangam.rest.port.RequestContext;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The Spring host's {@link HostHarness} (M-144 / M-150): the whole standalone server ({@link SangamServerApplication})
 * on a private in-memory H2 database, started and stopped by the kit.
 *
 * <ul>
 *   <li>A normal start uses the {@code h2} development profile with its security bootstrap for the two conformance
 *       tenants (an ALLOW-everything policy for the administrator groups, {@code SecurityBootstrap}).</li>
 *   <li>A start without a capability uses the neutral profile {@link #NON_DEVELOPMENT_PROFILE} instead of a development
 *       profile (so decision 75's development exemption does not apply; with no active profile at all the server's
 *       default profile, h2, would make it a development start) and the configuration a non-development start needs,
 *       generated fresh per run as the acceptance suite does ({@code AbstractAcceptanceIT}): key-encryption key,
 *       registry and bundle signing keys, pepper.</li>
 * </ul>
 *
 * <p>Removable today: the security module ({@code sangam.modules.security.enabled=false}) takes away authorization and
 * the tenant security engine plugin; a non-development start then refuses (decision 75). Every other required
 * capability is a declared gap of the baseline host until its extraction phase moves the decision into the assembly
 * (design note section 4). Written for the test phase (not run).
 */
public final class SpringHostHarness implements HostHarness {

    /** Groups of the administrator contexts: the dev bootstrap grants every permission to the first. */
    static final List<String> ADMIN_GROUPS = List.of("sangam-admin", "sangam-user", "sangam-operator", "sangam-worker");

    /** A profile that says nothing about the environment (no profile file, not a development or production name). */
    static final String NON_DEVELOPMENT_PROFILE = "conformance";

    /** The refusal of a start without the security module (SangamRestAutoConfiguration, decision 75). */
    static final String SECURITY_REFUSAL = "sangam-security is not active";

    private final Set<Capability> removed = new LinkedHashSet<>();
    private ConfigurableApplicationContext context;

    @Override
    public String hostName() {
        return "spring";
    }

    @Override
    public void start() {
        if (context != null) {
            throw new IllegalStateException("already started");
        }
        boolean development = removed.isEmpty();
        Map<String, Object> props = development ? developmentProperties() : nonDevelopmentProperties();
        if (removed.contains(Capabilities.AUTHORIZATION) || removed.contains(Capabilities.TENANT_SECURITY_PLUGIN)) {
            props.put("sangam.modules.security.enabled", "false");
        }
        SpringApplicationBuilder app = new SpringApplicationBuilder(SangamServerApplication.class);
        // without a capability: a neutral profile, because with no active profile the server's default profile applies,
        // and that is h2 (application.yaml), a development profile
        app.profiles(development ? "h2" : NON_DEVELOPMENT_PROFILE);
        // as command-line arguments: builder properties are only defaults, which application.yaml and the profile
        // files override (server port 8080, the h2 profile's database URL and bootstrap tenants)
        context = app.run(props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    @Override
    public void stop() {
        ConfigurableApplicationContext c = context;
        context = null;
        if (c != null) {
            c.close();
        }
    }

    @Override
    public <T> T port(Class<T> port) {
        if (context == null) {
            throw new IllegalStateException("the Spring host is not started");
        }
        // the bean the controllers inject, resolved by type exactly as they resolve it
        return context.getBean(port);
    }

    /** RM-30: the host's own data source (the one its engine and product tables use). */
    @Override
    public javax.sql.DataSource database() {
        return port(org.operaton.bpm.engine.ProcessEngine.class).getProcessEngineConfiguration().getDataSource();
    }

    @Override
    public RequestContext administrator(String tenantId) {
        return new RequestContext(tenantId, "conformance-admin", Set.copyOf(ADMIN_GROUPS), null, null, null, null, null,
                "DEV_TOKEN");
    }

    @Override
    public void withoutCapability(Capability capability) {
        if (!removableCapabilities().contains(capability)) {
            throw new IllegalArgumentException("the Spring host cannot take away " + capability.id()
                    + " yet: " + capabilityGaps().get(capability));
        }
        removed.add(capability);
    }

    @Override
    public Set<Capability> removableCapabilities() {
        return Set.of(Capabilities.AUTHORIZATION, Capabilities.TENANT_SECURITY_PLUGIN);
    }

    @Override
    public String refusalMarker(Capability capability) {
        return SECURITY_REFUSAL;
    }

    @Override
    public Map<Capability, String> capabilityGaps() {
        String p1b = "decided inside the Spring engine wiring; removable once extraction phase 1b (EngineAssembly) owns it";
        String p2 = "decided inside the Spring security wiring; removable once extraction phase 2 (security and tenancy)"
                + " owns it";
        String p3 = "decided inside the Spring integration wiring; removable once extraction phase 3 (integration core,"
                + " lifecycle descriptors) owns it";
        String p5 = "decided inside the Spring module wiring; removable once extraction phase 5 (domain modules) owns it";
        Map<Capability, String> gaps = new LinkedHashMap<>();
        gaps.put(Capabilities.UTC_JVM, p1b + " (a non-UTC default time zone cannot be set safely inside one test JVM)");
        gaps.put(Capabilities.DATABASE_CHECKS, p1b);
        gaps.put(Capabilities.SCHEMA_BEFORE_ENGINE, p1b);
        gaps.put(Capabilities.SHARED_TRANSACTION, p1b + " (ConnectionBinding over the transaction-aware data source)");
        gaps.put(Capabilities.JACKSON_FEEL, p1b);
        gaps.put(Capabilities.JOB_LEASES, p1b);
        gaps.put(Capabilities.CONFIG_SOURCE, p1b + " (SangamProperties over the Spring Environment)");
        gaps.put(Capabilities.SECURITY_CONTEXT, p2);
        gaps.put(Capabilities.AUDIT_CHAIN, p3);
        gaps.put(Capabilities.IDEMPOTENCY_STORE, p3);
        gaps.put(Capabilities.OUTBOX_RELAY_INBOX, p3);
        gaps.put(Capabilities.LIFECYCLE, p3);
        gaps.put(Capabilities.SCHEDULING, p3);
        gaps.put(Capabilities.MONEY_INVARIANTS, p5);
        return gaps;
    }

    @Override
    public boolean baseline() {
        return true;
    }

    @Override
    public Optional<HttpBinding> http() {
        String version = contractVersion();
        return Optional.of(new HttpBinding() {
            @Override
            public String contractVersion() {
                return version;
            }

            @Override
            public String basePath() {
                return "/api/v1";
            }
        });
    }

    @Override
    public Path hostModule() {
        return Path.of("").toAbsolutePath();
    }

    /** {@code info.version} of the contract the server serves ({@code sangam-api.yaml} at the class-path root). */
    static String contractVersion() {
        try (InputStream in = SpringHostHarness.class.getClassLoader().getResourceAsStream("sangam-api.yaml")) {
            if (in == null) {
                return "unknown";
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(?m)^info:\\s*$[\\s\\S]*?^\\s+version:\\s*['\"]?([^'\"\\s]+)").matcher(text);
            return m.find() ? m.group(1) : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------ configuration

    private static Map<String, Object> common() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("server.port", "0");
        p.put("management.server.port", "0");
        p.put("spring.datasource.url", "jdbc:h2:mem:conformance-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL");
        p.put("spring.datasource.username", "sa");
        p.put("spring.datasource.password", "");
        p.put("spring.datasource.driver-class-name", "org.h2.Driver");
        p.put("operaton.bpm.database.schema-update", "true");
        p.put("sangam.db.require-rcsi", "false");
        p.put("sangam.persistence.dev-schema", "false");
        return p;
    }

    /** The h2 profile plus the two conformance tenants in the development security bootstrap. */
    static Map<String, Object> developmentProperties() {
        Map<String, Object> p = common();
        String tenants = TENANT_A + "," + TENANT_B;
        p.put("sangam.security.bootstrap.enabled", "true");
        p.put("sangam.security.bootstrap.tenants", tenants);
        p.put("sangam.security.bootstrap.admin-groups", "sangam-admin,sangam-tenant-admin");
        p.put("sangam.security.jit-provisioning-tenants", tenants);
        return p;
    }

    /** No development profile: fresh keys per run (never committed secrets), no bootstrap, no dev token. */
    static Map<String, Object> nonDevelopmentProperties() {
        Map<String, Object> p = common();
        String[] registry = ed25519();
        String[] bundle = ed25519();
        p.put("sangam.security.kek.keys.conf1", randomBase64(32));
        p.put("sangam.security.kek.current-id", "conf1");
        p.put("sangam.registry.signer.private-key", registry[0]);
        p.put("sangam.registry.signer.public-key", registry[1]);
        p.put("sangam.registry.vault.hmac-pepper", randomBase64(32));
        p.put("sangam.onboarding.bundle.key-id", "conf-bundle");
        p.put("sangam.onboarding.bundle.private-key", bundle[0]);
        p.put("sangam.onboarding.bundle.trusted-keys.conf-bundle", bundle[1]);
        p.put("sangam.security.bootstrap.enabled", "false");
        p.put("sangam.security.dev-token.enabled", "false");
        p.put(SangamRestAutoConfiguration.ALLOW_WITHOUT_SECURITY, "false");
        return p;
    }

    private static String randomBase64(int bytes) {
        byte[] k = new byte[bytes];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    private static String[] ed25519() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new String[] {Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
