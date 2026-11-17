// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host.conformance;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.app.RunningQuarkusApplication;
import io.quarkus.bootstrap.app.StartupAction;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.paths.PathList;

import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.Capability;
import org.sangam.assembly.host.HttpBinding;
import org.sangam.host.conformance.HostHarness;
import org.sangam.quarkus.host.QuarkusConfigSource;
import org.sangam.quarkus.host.QuarkusHost;
import org.sangam.quarkus.host.QuarkusLifecycle;
import org.sangam.quarkus.host.QuarkusScheduling;
import org.sangam.quarkus.host.QuarkusSecurityContext;
import org.sangam.rest.port.RequestContext;

/**
 * The Quarkus host's {@link HostHarness} (M-150 on Quarkus, owner decisions 229 and 279). Each {@link #start()} boots a
 * fresh Quarkus application in TEST mode through {@link QuarkusBootstrap}: this module's classes and test classes,
 * augmented offline, on its own in-memory H2 database (test {@code application.properties}). There is no
 * quarkus-maven-plugin; the bootstrap augments from the local repository.
 *
 * <p>Every required capability is removable, the way an operator's configuration would take it away; there are no
 * gaps (the kit allows them only on the baseline host):
 * <ul>
 *   <li>the four seams (decision 279): their optional beans are excluded at augmentation with
 *       {@code quarkus.arc.exclude-types} ({@link #SEAM_BEANS});</li>
 *   <li>{@code shared-transaction}: {@code quarkus.datasource.jdbc.transactions=disabled};</li>
 *   <li>{@code utc-jvm} (decision 279): the start runs in a separate JVM with {@value #NON_UTC_OPTION}
 *       ({@link ForkedStart}); the refusal is the core UTC gate's own;</li>
 *   <li>the engine area of extraction phase 1b (decisions 286 and 287), each by an operator setting:
 *       {@code database-checks} by {@value #ISOLATION_KEY}={@code serializable} (the data source the engine is handed
 *       runs another isolation level); {@code schema-before-engine} by {@code sangam.engine.apply-product-schema=false}
 *       (this host has no schema step of its own); {@code jackson-feel-compatibility} by jackson-databind
 *       {@value #UNSUPPORTED_JACKSON} forced into the application ({@link #JACKSON_DATABIND}); {@code job-leases} by
 *       {@code sangam.engine.executor-partitioning=true} with {@value #DEPLOYMENT_AWARE_KEY}={@code true}, an acquisition
 *       option the partitioned query does not reproduce (the engine's own M-056 refusal);</li>
 *   <li>every other required capability is not supplied by this host until its extraction phase (decision 276: 3, 2,
 *       5) assembles it, so a start lacks it as a missing collaborator would.</li>
 * </ul>
 * While those later phases are open, every start is refused for their capabilities too; a removal's own marker is in
 * that refusal for the database-free checks and the seams, and appears for {@code database-checks} and
 * {@code job-leases} once the host has no other gap (they are checked after the host gaps).
 * The host offers no port yet ({@link #port} throws) until phase 3, so the equivalence scenarios cannot pass on it yet.
 * Written for the test phase (not run).
 */
public final class QuarkusHostHarness implements HostHarness {

    /** Decision 279: the bean that is each seam; excluding it takes the seam away. */
    static final Map<Capability, Class<?>> SEAM_BEANS = Map.of(
            Capabilities.CONFIG_SOURCE, QuarkusConfigSource.class,
            Capabilities.LIFECYCLE, QuarkusLifecycle.class,
            Capabilities.SCHEDULING, QuarkusScheduling.class,
            Capabilities.SECURITY_CONTEXT, QuarkusSecurityContext.class);

    /** Decision 279: the JVM option of the separate JVM that lacks {@code utc-jvm}. */
    static final String NON_UTC_OPTION = "-Duser.timezone=Asia/Kolkata";

    /** Text of the core UTC gate's refusal ({@code EngineTimeZone.requireUtcJvm}, SL-53). */
    static final String UTC_REFUSAL = "The Sangam engine refuses to start";

    /** Decision 287 (c): the Agroal setting that hands the engine a data source with another isolation level. */
    static final String ISOLATION_KEY = "quarkus.datasource.jdbc.transaction-isolation-level";

    /** Decision 286: an acquisition option the partitioned job acquisition refuses (Operaton generic config). */
    static final String DEPLOYMENT_AWARE_KEY = "quarkus.operaton.generic-config.job-executor-deployment-aware";

    /** The engine's refusal of that combination ({@code PartitionedAcquireJobsCmd.requireSupported}, M-056). */
    static final String JOB_LEASES_REFUSAL = "M-056: sangam.engine.executor-partitioning cannot be combined with";

    /** Decision 287 (a): a jackson-databind outside the FEEL engine's line (cached; SG-33 refuses it). */
    static final String UNSUPPORTED_JACKSON = "2.21.7";

    /** The forced dependency that puts {@value #UNSUPPORTED_JACKSON} into the application. */
    static final Dependency JACKSON_DATABIND = Dependency.of("com.fasterxml.jackson.core", "jackson-databind",
            UNSUPPORTED_JACKSON);

    /** How long the separate JVM may take. */
    static final long FORK_TIMEOUT_MINUTES = 10;

    private final Set<Capability> removed = new LinkedHashSet<>();
    private CuratedApplication curated;
    private RunningQuarkusApplication running;

    /**
     * Decision 402(1): the application model is resolved from this module's effective Maven model, so the module's
     * own dependency management (sshd 2.15.0, angus-mail 2.0.4, ...: the versions the default reactor resolves and the
     * licence record covers) wins over the imported Quarkus BOM, exactly as in the Maven build. Without it the
     * bootstrap's raw workspace model took the Quarkus BOM versions (sshd-core 2.19.0), which are neither cached nor
     * licence-reviewed.
     */
    io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver appResolver()
            throws io.quarkus.bootstrap.resolver.maven.BootstrapMavenException {
        return io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver.builder()
                .setOffline(true)
                .setWorkspaceDiscovery(true)
                .setCurrentProject(hostModule().toString())
                .setEffectiveModelBuilder(true)
                .build();
    }

    @Override
    public String hostName() {
        return QuarkusHost.HOST_NAME;
    }

    @Override
    public void start() {
        if (curated != null) {
            throw new IllegalStateException("already started");
        }
        if (removed.contains(Capabilities.UTC_JVM)) {
            startInNonUtcJvm();
            return;
        }
        Path testClasses = codeLocation();
        Path build = testClasses.getParent();
        Path classes = build.resolve("classes");
        try {
            QuarkusBootstrap.Builder bootstrap = QuarkusBootstrap.builder()
                    .setMavenArtifactResolver(appResolver())
                    .setApplicationRoot(PathList.of(classes, testClasses))
                    .setProjectRoot(hostModule())
                    .setTargetDirectory(build)
                    .setMode(QuarkusBootstrap.Mode.TEST)
                    .setTest(true)
                    .setIsolateDeployment(true)
                    .setOffline(true)
                    .setLocalProjectDiscovery(true);
            Properties buildConfig = buildConfiguration();
            if (!buildConfig.isEmpty()) {
                bootstrap.setBuildSystemProperties(buildConfig);
            }
            if (removed.contains(Capabilities.JACKSON_FEEL)) {
                // the application's own dependency graph decides its Jackson (not this JVM's class path)
                bootstrap.setForcedDependencies(List.of(JACKSON_DATABIND));
            }
            curated = bootstrap.build().bootstrap();
            StartupAction startup = curated.createAugmentor().createInitialRuntimeApplication();
            startup.overrideConfig(configuration());
            running = startup.run();
        } catch (Exception e) {
            stop();
            throw new IllegalStateException("the Quarkus host did not start", e);
        }
    }

    /** Augmentation (build-time) settings: the excluded seam beans. */
    Properties buildConfiguration() {
        List<String> excluded = new ArrayList<>();
        SEAM_BEANS.forEach((capability, bean) -> {
            if (removed.contains(capability)) {
                excluded.add(bean.getName());
            }
        });
        Properties p = new Properties();
        if (!excluded.isEmpty()) {
            p.setProperty("quarkus.arc.exclude-types", String.join(",", excluded.stream().sorted().toList()));
        }
        return p;
    }

    /** Per start: a private database, and the operator settings that take the removed capabilities away. */
    Map<String, String> configuration() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("quarkus.datasource.jdbc.url", "jdbc:h2:mem:quarkus-conformance-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL");
        if (removed.contains(Capabilities.SHARED_TRANSACTION)) {
            config.put("quarkus.datasource.jdbc.transactions", "disabled");
        }
        if (removed.contains(Capabilities.DATABASE_CHECKS)) {
            config.put(ISOLATION_KEY, "serializable");
        }
        if (removed.contains(Capabilities.SCHEMA_BEFORE_ENGINE)) {
            config.put("sangam.engine.apply-product-schema", "false");
        }
        if (removed.contains(Capabilities.JOB_LEASES)) {
            config.put("sangam.engine.executor-partitioning", "true");
            config.put(DEPLOYMENT_AWARE_KEY, "true");
        }
        return config;
    }

    /**
     * The start without {@code utc-jvm}: {@link ForkedStart} in a separate JVM with {@value #NON_UTC_OPTION} and this
     * JVM's class path; its refusal (stack traces with every cause) becomes this start's failure message.
     */
    private void startInNonUtcJvm() {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                NON_UTC_OPTION));
        String repo = System.getProperty("maven.repo.local");
        if (repo != null) {
            command.add("-Dmaven.repo.local=" + repo);
        }
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), ForkedStart.class.getName()));
        String output;
        int exit;
        try {
            Path log = Files.createTempFile("sangam-quarkus-non-utc", ".log");
            try {
                Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                if (!p.waitFor(FORK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                    throw new IllegalStateException("the separate non-UTC JVM did not finish in " + FORK_TIMEOUT_MINUTES
                            + " minutes");
                }
                exit = p.exitValue();
                output = Files.readString(log, StandardCharsets.UTF_8);
            } finally {
                Files.deleteIfExists(log);
            }
        } catch (IOException e) {
            throw new IllegalStateException("the separate non-UTC JVM could not run", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the separate non-UTC JVM ran", e);
        }
        if (exit != ForkedStart.STARTED) {
            throw new IllegalStateException("the Quarkus host did not start in a JVM with " + NON_UTC_OPTION + " (exit "
                    + exit + "):\n" + output);
        }
        // it started: the fail-closed kit reports this as a start without utc-jvm
    }

    @Override
    public void stop() {
        RunningQuarkusApplication r = running;
        CuratedApplication c = curated;
        running = null;
        curated = null;
        RuntimeException failure = null;
        if (r != null) {
            try {
                r.close();
            } catch (Exception e) {
                failure = new IllegalStateException("stopping the Quarkus host failed", e);
            }
        }
        if (c != null) {
            c.close();
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public <T> T port(Class<T> port) {
        throw new UnsupportedOperationException("the Quarkus host offers no " + port.getSimpleName()
                + " yet (the ports come with extraction phases 3, 2 and 5, decision 276)");
    }

    @Override
    public RequestContext administrator(String tenantId) {
        return new RequestContext(tenantId, "conformance-admin", Set.of("sangam-admin"), null, null, null, null, null,
                "QUARKUS_CONFORMANCE");
    }

    @Override
    public void withoutCapability(Capability capability) {
        if (!removableCapabilities().contains(capability)) {
            throw new IllegalArgumentException("not a required capability: " + capability.id());
        }
        removed.add(capability);
    }

    /** Every required capability (decision 274: no gaps on a non-baseline host). */
    @Override
    public Set<Capability> removableCapabilities() {
        return Set.copyOf(Capabilities.required());
    }

    /**
     * {@code utc-jvm}: the core UTC gate's refusal; {@code job-leases}: the engine's M-056 refusal (decision 286: the
     * engine supplies the leases and refuses what would bypass them); every other capability: the
     * {@code CAPABILITY_MISSING} message,
     * which names each missing capability as {@code id (what happens)}.
     */
    @Override
    public String refusalMarker(Capability capability) {
        if (Capabilities.UTC_JVM.equals(capability)) {
            return UTC_REFUSAL;
        }
        if (Capabilities.JOB_LEASES.equals(capability)) {
            return JOB_LEASES_REFUSAL;
        }
        return capability.id() + " (" + capability.whenMissing() + ")";
    }

    @Override
    public Map<Capability, String> capabilityGaps() {
        return Map.of();
    }

    @Override
    public boolean baseline() {
        return false;
    }

    /** Decision 278: no HTTP binding on the Quarkus host in 0.1.0. */
    @Override
    public Optional<HttpBinding> http() {
        return Optional.empty();
    }

    @Override
    public Path hostModule() {
        Path build = codeLocation().getParent();
        Path module = build.getParent();
        if (!Files.isRegularFile(module.resolve("pom.xml"))) {
            throw new IllegalStateException("no module pom next to " + build);
        }
        return module;
    }

    /** This class's test-classes folder. */
    private static Path codeLocation() {
        try {
            return Path.of(QuarkusHostHarness.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
