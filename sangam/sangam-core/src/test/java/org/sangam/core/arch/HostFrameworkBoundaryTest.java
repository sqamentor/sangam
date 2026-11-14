// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.arch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-015 / M-139 / M-144 host-framework boundary (owner architecture directive 2026-11-12: a framework-independent
 * product layer with host frameworks as thin adapters; owner decisions 72, 74 and 81). Only the hosts in {@link #HOSTS}
 * may refer to a host framework in main sources. The core, the domain modules, the services the REST controllers call
 * and the future plain-Java assembly layer never do.
 *
 * <p><b>Host frameworks</b> ({@link #HOST_FRAMEWORK_PACKAGES}, decision 81: Quarkus is one possible outer host, and any
 * later framework is a thin adapter that leaves the core untouched): Spring, Quarkus, Micronaut, Helidon, CDI
 * ({@code jakarta.enterprise}, {@code javax.enterprise}), JSR-330 injection ({@code jakarta.inject},
 * {@code javax.inject}), Guice, Dropwizard, and the HTTP binding of JAX-RS and Jersey. On 2026-11-12 no CDI or JSR-330
 * annotation was used anywhere in the reactor, and JAX-RS and Jersey only in {@code sangam-server/enginerest} (a host),
 * so the wider ban added no ratchet entry.
 *
 * <p>The hosts are:
 * <ul>
 *   <li>the host modules: the standalone server, the Spring Boot starter and, once it exists, the Quarkus host;</li>
 *   <li>the host applications (decision 74): the cell control plane, the gateway and the rollup service;</li>
 *   <li>the REST host area of {@code sangam-rest} (decision 72), listed by package or file: the controller packages
 *       {@code web/} and {@code modules/}, the Spring configuration package {@code config/}, and in {@code compliance/}
 *       and {@code lifecycle/} only the controller, the problem advice and the servlet filter. Every other
 *       {@code sangam-rest} package ({@code adapter/}, {@code support/}, {@code port/}, {@code extension/} and the
 *       compliance and lifecycle services) is not a host, so its Spring files stay on the ratchet. Since decision 285
 *       the ports, adapters, support classes and extension SPI live in {@code sangam-rest-core} (same packages),
 *       which is never a host;</li>
 *   <li>the SDK framework-adapter packages (decision 74, M-027): the Spring Boot, Python-framework and NestJS
 *       adapters.</li>
 * </ul>
 *
 * <p>A reference is a code line (not a comment line, as in {@link ArchitectureGuardTest#codeHits}) that names one of
 * those packages: an import, a static import, an annotation or a fully qualified name. The scan shares
 * {@link ArchitectureGuardTest}'s file walk (build output, {@code node_modules} and {@code .git} skipped).
 *
 * <p><b>Ratchet.</b> The main sources outside the hosts that already broke the rule are on
 * {@link #HOST_FRAMEWORK_ALLOWED} (snapshot 2026-11-12, recomputed once for decisions 72 and 74; no further
 * re-snapshot). The list is a set of files, not line counts: an already listed adapter file may change, but no new file
 * may join, and a listed file that no longer refers to any host framework fails until its entry is removed. The list
 * only ever gets shorter: the extraction phases of the design note ({@code sangam/docs/M-015-QUARKUS-EXTENSION-DESIGN.md})
 * move these files into a host or make them framework-free.
 */
class HostFrameworkBoundaryTest {

    /**
     * Host-framework root packages (decision 81). Adding a framework here is how a new outer host is fenced in before
     * its adapter is written; never remove one while non-host code could start to use it.
     */
    static final List<String> HOST_FRAMEWORK_PACKAGES = List.of(
            "org.springframework", "io.quarkus", "io.micronaut", "io.helidon",
            "jakarta.enterprise", "javax.enterprise", "jakarta.inject", "javax.inject",
            "com.google.inject", "io.dropwizard", "jakarta.ws.rs", "javax.ws.rs", "org.glassfish.jersey");

    /** A host-framework package named in Java source: one of {@link #HOST_FRAMEWORK_PACKAGES} followed by a dot. */
    static final Pattern HOST_FRAMEWORK = Pattern.compile("\\b(?:" + String.join("|",
            HOST_FRAMEWORK_PACKAGES.stream().map(Pattern::quote).toList()) + ")\\.");

    /** The REST host area of sangam-rest (decision 72): packages end with a slash, single files with .java. */
    static final String REST = "sangam-rest/src/main/java/org/sangam/rest/";

    /** Decision 285: the ports, default adapters, support classes and extension SPI of sangam-rest; never a host. */
    static final String REST_CORE = "sangam-rest-core/src/main/java/org/sangam/rest/";

    /**
     * Hosts (reactor-relative prefixes, forward slashes): the only places that may use a host framework. A folder ends
     * with a slash; a single file is named in full.
     */
    static final Set<String> HOSTS = Set.of(
            // host modules
            "sangam-server/",
            "sangam-spring-boot-starter/",
            "sangam-quarkus-extension/",
            // host applications (decision 74)
            "sangam-control-plane/",
            "sangam-gateway/",
            "sangam-rollup/",
            // REST host area of sangam-rest (decision 72)
            REST + "web/",
            REST + "modules/",
            REST + "config/",
            REST + "compliance/ComplianceController.java",
            REST + "compliance/ComplianceProblemAdvice.java",
            REST + "lifecycle/DeprecationHeadersFilter.java",
            // SDK framework-adapter packages (decision 74, M-027)
            "sdks/java-spring-boot/",
            "sdks/python-frameworks/",
            "sdks/typescript-nestjs/");

    /**
     * Ratchet: main sources outside {@link #HOSTS} that referred to a host framework on 2026-11-12 (reactor-relative,
     * forward slashes). Never add an entry: put new framework code in a host.
     */
    static final Set<String> HOST_FRAMEWORK_ALLOWED = new TreeSet<>();

    static {
        // sangam-field, sangam-geo, sangam-registry: 0 file(s) since M-141d (their Spring controllers moved to the REST
        // host area of sangam-rest, modules/geo/web and modules/registry/web; the unregistered field adapter was removed)
        // sangam-rest: 0 file(s) since M-141c (TxSupport and ReadTransactions are plain Java; their Spring
        // implementations SpringTxSupport and SpringReadTransactions live in the REST host area, config/)
        // sangam-security: 0 file(s) since M-141a (its 6 Spring files moved to the Spring host, sangam-server)
    }

    static boolean isHost(String rel) {
        return HOSTS.stream().anyMatch(rel::startsWith);
    }

    /** Main Java sources outside the hosts -> their code lines naming a host framework. */
    static Map<String, List<String>> scan(Path root) throws IOException {
        Map<String, List<String>> out = new TreeMap<>();
        for (Path p : ArchitectureGuardTest.files(root)) {
            String r = ArchitectureGuardTest.rel(root, p);
            if (!r.endsWith(".java") || !ArchitectureGuardTest.isMain(r) || isHost(r)) {
                continue;
            }
            String text = ArchitectureGuardTest.read(p);
            if (text == null) {
                continue;
            }
            List<String> hits = ArchitectureGuardTest.codeHits(r, text, HOST_FRAMEWORK);
            if (!hits.isEmpty()) {
                out.put(r, hits);
            }
        }
        return out;
    }

    /** Every violation of the ratchet, worded with the fix it needs. */
    static List<String> violations(Map<String, List<String>> found, Set<String> allowed) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : found.entrySet()) {
            if (!allowed.contains(e.getKey())) {
                out.add(e.getKey() + ": refers to a host framework outside the hosts (move the framework code into"
                        + " a host listed in HOSTS; first hit " + e.getValue().get(0) + ")");
            }
        }
        for (String a : allowed) {
            if (!found.containsKey(a)) {
                out.add(a + ": no longer refers to a host framework (remove its HOST_FRAMEWORK_ALLOWED entry)");
            }
        }
        return out;
    }

    @Test
    void onlyHostsUseAHostFramework() throws IOException {
        Map<String, List<String>> found = scan(ArchitectureGuardTest.root());
        List<String> v = violations(found, HOST_FRAMEWORK_ALLOWED);
        assertTrue(v.isEmpty(), String.join("\n", v));
    }

    @Test
    void coreAndEngineNeverUseAHostFramework() throws IOException {
        Map<String, List<String>> found = scan(ArchitectureGuardTest.root());
        // M-144: the plain-Java assembly layer and the host conformance kit are never a host either
        for (String module : List.of("sangam-core/", "sangam-persistence/", "sangam-engine/", "sangam-assembly/",
                "sangam-host-conformance/", "sangam-field/", "sangam-geo/", "sangam-registry/",
                // decision 285: the Spring-free ports and default adapters moved out of sangam-rest
                "sangam-rest-core/")) {
            List<String> hits = found.keySet().stream().filter(k -> k.startsWith(module)).toList();
            assertTrue(hits.isEmpty(), module + " must stay free of Spring and Quarkus: " + hits);
            assertFalse(HOST_FRAMEWORK_ALLOWED.stream().anyMatch(k -> k.startsWith(module)),
                    module + " may never be on the ratchet");
        }
    }

    /**
     * M-141a (decisions 72-75): the security jar is plain Java. No main source names a host framework, it is never on
     * the ratchet, and its pom declares no Spring or servlet dependency (the Spring Security adapters live in the host).
     */
    @Test
    void securityJarStaysFrameworkFree() throws IOException {
        Path root = ArchitectureGuardTest.root();
        List<String> hits = scan(root).keySet().stream().filter(k -> k.startsWith("sangam-security/")).toList();
        assertTrue(hits.isEmpty(), "sangam-security must stay free of host frameworks (M-141a): " + hits);
        assertFalse(HOST_FRAMEWORK_ALLOWED.stream().anyMatch(k -> k.startsWith("sangam-security/")),
                "sangam-security may never be on the ratchet again (M-141a)");
        String pom = ArchitectureGuardTest.read(root.resolve("sangam-security/pom.xml"));
        assertTrue(pom != null, "sangam-security/pom.xml not found under " + root);
        for (String banned : List.of("<groupId>org.springframework", "<groupId>jakarta.servlet",
                "<groupId>io.quarkus")) {
            assertFalse(pom.contains(banned), "sangam-security/pom.xml declares " + banned + " (M-141a)");
        }
    }

    @Test
    void ratchetReportsEveryCase() {
        Map<String, List<String>> found = new TreeMap<>();
        found.put("a/src/main/java/New.java", List.of("a/src/main/java/New.java:3: import org.springframework.x.Y;"));
        found.put("b/src/main/java/Kept.java", List.of("b/src/main/java/Kept.java:4: import io.quarkus.x.Y;"));
        List<String> v = violations(found, Set.of("b/src/main/java/Kept.java", "c/src/main/java/Gone.java"));
        assertEquals(2, v.size(), String.join("\n", v));
        assertTrue(v.get(0).startsWith("a/src/main/java/New.java: refers to a host framework"), v.get(0));
        assertTrue(v.get(1).startsWith("c/src/main/java/Gone.java: no longer refers"), v.get(1));
    }

    @Test
    void patternMatchesWhatItBans() {
        assertTrue(HOST_FRAMEWORK.matcher("import org.springframework.context.annotation.Bean;").find());
        assertTrue(HOST_FRAMEWORK.matcher("import static org.springframework.util.Assert.notNull;").find());
        assertTrue(HOST_FRAMEWORK.matcher("new org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter()").find());
        assertTrue(HOST_FRAMEWORK.matcher("import io.quarkus.runtime.annotations.Recorder;").find());
        assertFalse(HOST_FRAMEWORK.matcher("import org.sangam.springish.Thing;").find());
        assertFalse(HOST_FRAMEWORK.matcher("import io.quarkusx.Other;").find());
        assertFalse(HOST_FRAMEWORK.matcher("String s = \"springframework\";").find());
        // decision 81: every host framework, not only Spring and Quarkus
        for (String banned : List.of("import io.micronaut.context.annotation.Factory;",
                "import io.helidon.webserver.WebServer;", "@jakarta.enterprise.context.ApplicationScoped",
                "import javax.enterprise.inject.Produces;", "import jakarta.inject.Inject;", "import javax.inject.Named;",
                "import com.google.inject.Module;", "import io.dropwizard.core.Application;",
                "import jakarta.ws.rs.GET;", "import org.glassfish.jersey.server.ResourceConfig;")) {
            assertTrue(HOST_FRAMEWORK.matcher(banned).find(), banned);
        }
        // plain Jakarta APIs the product layer may use, and look-alike names
        for (String allowed : List.of("import jakarta.servlet.http.HttpServletRequest;",
                "import jakarta.persistence.Entity;", "import jakarta.injection.Thing;", "import io.helidonx.Other;",
                "import org.sangam.micronaut.Thing;")) {
            assertFalse(HOST_FRAMEWORK.matcher(allowed).find(), allowed);
        }
    }

    @Test
    void everyBannedPackageIsARootPackageName() {
        for (String p : HOST_FRAMEWORK_PACKAGES) {
            assertTrue(p.matches("[a-z]+(\\.[a-z]+)+"), "a dotted lower-case package name: " + p);
            assertTrue(HOST_FRAMEWORK.matcher("import " + p + ".X;").find(), p);
        }
    }

    @Test
    void theRestHostAreaIsExplicit() {
        assertFalse(HOSTS.contains("sangam-rest/") || HOSTS.contains(REST), "sangam-rest as a whole is not a host");
        assertTrue(isHost(REST + "web/TaskController.java"));
        assertTrue(isHost(REST + "modules/ledger/LedgerController.java"));
        assertTrue(isHost(REST + "config/SangamRestAutoConfiguration.java"));
        assertTrue(isHost(REST + "compliance/ComplianceController.java"));
        for (String service : List.of(REST + "compliance/ComplianceRestService.java",
                REST + "compliance/ComplianceApiImpl.java", REST + "lifecycle/ApiDeprecationRegistry.java",
                REST_CORE + "support/TxSupport.java", REST_CORE + "adapter/operaton/OperatonOpsAdapter.java",
                REST_CORE + "port/AdminPort.java", "sangam-security/src/main/java/org/sangam/security/SangamSecurity.java")) {
            assertFalse(isHost(service), "the services and ports stay framework-free: " + service);
        }
        for (String h : HOSTS) {
            assertTrue(h.endsWith("/") || h.endsWith(".java"), "a host is a folder or one file: " + h);
        }
    }

    @Test
    void hostsAndRatchetDoNotOverlap() {
        for (String a : HOST_FRAMEWORK_ALLOWED) {
            assertFalse(isHost(a), "a host file needs no ratchet entry: " + a);
            assertTrue(a.contains("/src/main/") && a.endsWith(".java"), "entries are main Java sources: " + a);
        }
    }
}
