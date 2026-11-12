// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.example.sangamhost.SampleHostApplication;
import org.junit.jupiter.api.Test;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/** M-014 starter pieces in isolation: the import selector, the back-off marker, the defaults and the Boot check. */
class SangamStarterUnitTest {

    static List<String> candidates() {
        SangamProductImportSelector selector = new SangamProductImportSelector();
        selector.setEnvironment(new StandardEnvironment());
        selector.setResourceLoader(new DefaultResourceLoader());
        selector.setBeanClassLoader(SangamStarterUnitTest.class.getClassLoader());
        return selector.candidates();
    }

    @Test
    void theSelectorImportsTheServersWiringNotTheServerApplication() {
        List<String> found = candidates();
        assertTrue(found.contains("org.sangam.server.config.ModuleWiring"));
        assertTrue(found.contains("org.sangam.server.config.EngineWiring"));
        assertTrue(found.contains("org.sangam.server.config.SecurityConfig"));
        assertTrue(found.contains("org.sangam.server.config.SecurityAuditPoolWiring"), "always-on wiring too");
        assertTrue(found.stream().anyMatch(n -> n.startsWith("org.sangam.rest.web.")), "sangam-rest's controllers");
        assertFalse(found.contains(SangamServerApplication.class.getName()));
        int lastServer = -1;
        int firstRestWeb = Integer.MAX_VALUE;
        for (int i = 0; i < found.size(); i++) {
            if (found.get(i).startsWith("org.sangam.server.")) {
                lastServer = i;
            } else if (found.get(i).startsWith("org.sangam.rest.web.")) {
                firstRestWeb = Math.min(firstRestWeb, i);
            }
        }
        assertTrue(lastServer < firstRestWeb, "the server's base package order");
        assertEquals(found.size(), found.stream().distinct().count(), "each class once");
    }

    @Test
    void theStarterBacksOffWhereTheServerWiringIsAlreadyLoaded() throws Exception {
        assertEquals(Class.forName(SangamAutoConfiguration.SERVER_WIRING_MARKER).getName(),
                SangamAutoConfiguration.class.getAnnotation(ConditionalOnMissingBean.class).type()[0]);
        assertEquals(Ordered.HIGHEST_PRECEDENCE, SangamAutoConfiguration.class.getAnnotation(AutoConfigureOrder.class).value(),
                "ahead of every other auto-configuration, as the server's own scan");
    }

    @Test
    void theDefaultsAreNotAppliedToTheStandaloneServer() {
        assertTrue(SangamDefaultsEnvironmentPostProcessor.isStandaloneServer(new SpringApplication(SangamServerApplication.class)));
        assertFalse(SangamDefaultsEnvironmentPostProcessor.isStandaloneServer(new SpringApplication(SampleHostApplication.class)));
    }

    @Test
    void theDefaultsGoBelowEverythingTheHostSets() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("host", Map.of("sangam.modules.ledger.enabled", "true")));
        new SangamDefaultsEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication(SampleHostApplication.class));
        assertEquals(SangamDefaultsEnvironmentPostProcessor.SOURCE_NAME,
                env.getPropertySources().stream().reduce((a, b) -> b).orElseThrow().getName());
        assertEquals("true", env.getProperty("sangam.modules.ledger.enabled"), "the host's value wins");
        assertEquals("false", env.getProperty("sangam.modules.billing.enabled"), "domain modules off by default");
        assertEquals("true", env.getProperty("sangam.modules.engine.enabled"), "core on");
        assertEquals("false", env.getProperty("operaton.bpm.auto-deployment-enabled"), "Sangam's engine defaults");
        assertNotNull(env.getProperty("sangam.persistence.changelog"));
        StandardEnvironment off = new StandardEnvironment();
        off.getPropertySources().addFirst(new MapPropertySource("host",
                Map.of(SangamDefaultsEnvironmentPostProcessor.ENABLED_PROPERTY, "false")));
        new SangamDefaultsEnvironmentPostProcessor().postProcessEnvironment(off, new SpringApplication(SampleHostApplication.class));
        assertFalse(off.getPropertySources().contains(SangamDefaultsEnvironmentPostProcessor.SOURCE_NAME));
    }

    /** A host resolves the broker clients the server is built with, not the Spring Boot BOM's (kafka-clients 4.x). */
    @Test
    void theBomPinsTheBuildsBrokerClients() throws Exception {
        String root = java.nio.file.Files.readString(java.nio.file.Path.of("..", "pom.xml"));
        String bom = java.nio.file.Files.readString(java.nio.file.Path.of("..", "sangam-bom", "pom.xml"));
        for (String[] p : new String[][] {{"version.kafka", "kafka-clients"}, {"version.jakarta-jms", "jakarta.jms-api"}}) {
            java.util.regex.Matcher build = java.util.regex.Pattern.compile("<" + java.util.regex.Pattern.quote(p[0])
                    + ">([^<]+)<").matcher(root);
            java.util.regex.Matcher pinned = java.util.regex.Pattern.compile("<artifactId>" + java.util.regex.Pattern.quote(p[1])
                    + "</artifactId><version>([^<]+)</version>").matcher(bom);
            assertTrue(build.find() && pinned.find(), p[1]);
            assertEquals(build.group(1), pinned.group(1), "sangam-bom and the build pin the same " + p[1]);
        }
    }

    @Test
    void anUnsupportedSpringBootLineFailsWithAClearMessage() {
        assertDoesNotThrow(() -> SangamDefaultsEnvironmentPostProcessor.requireBootLine("4.1.2"));
        assertDoesNotThrow(() -> SangamDefaultsEnvironmentPostProcessor.requireBootLine(null));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SangamDefaultsEnvironmentPostProcessor.requireBootLine("3.5.0"));
        assertTrue(e.getMessage().contains("Spring Boot 4.1.x") && e.getMessage().contains("sangam-bom"), e.getMessage());
    }
}
