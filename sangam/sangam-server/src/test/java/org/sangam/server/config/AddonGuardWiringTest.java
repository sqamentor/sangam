// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Owner decisions 232 and 235: the server class path carries neither jnats nor Bouncy Castle, and start-up refuses the
 * NATS add-on together with the eSign add-on, or two Bouncy Castle provider jars.
 */
class AddonGuardWiringTest {

    /** A class loader that only knows the given resources (name to the jars that hold it). */
    static ClassLoader loader(Map<String, List<String>> resources) {
        return new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                List<String> jars = resources.get(name);
                return jars == null || jars.isEmpty() ? null : url(jars.get(0), name);
            }

            @Override
            public Enumeration<URL> getResources(String name) {
                List<URL> out = new ArrayList<>();
                resources.getOrDefault(name, List.of()).forEach(j -> out.add(url(j, name)));
                return Collections.enumeration(out);
            }
        };
    }

    static URL url(String jar, String name) {
        try {
            return new URL("jar:file:/opt/sangam/lib-ext/" + jar + "!/" + name);
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    static final String NATS = "io/nats/client/Nats.class";
    static final String ESIGN = "org/sangam/esign/DscSigningProvider.class";

    @Test
    void theServerClassPathCarriesNoJnatsAndNoBouncyCastle() {
        ClassLoader own = AddonGuardWiring.class.getClassLoader();
        assertNull(own.getResource(NATS), "decision 232: io.nats:jnats is not a sangam-server dependency");
        assertNull(own.getResource(AddonGuardWiring.BC_PROVIDER_RESOURCE),
                "decision 217/232: no Bouncy Castle provider in the default server build");
        assertFalse(AddonGuardWiring.natsPresent(own));
        assertDoesNotThrow(() -> AddonGuardWiring.check(own), "the default server starts");
    }

    @Test
    void oneAddOnAloneStarts() {
        Map<String, List<String>> nats = new LinkedHashMap<>();
        nats.put(NATS, List.of("jnats-2.26.4.jar"));
        nats.put(AddonGuardWiring.BC_PROVIDER_RESOURCE, List.of("bcprov-lts8on-2.73.12.1.jar"));
        assertTrue(AddonGuardWiring.natsPresent(loader(nats)));
        assertDoesNotThrow(() -> AddonGuardWiring.check(loader(nats)));

        Map<String, List<String>> esign = new LinkedHashMap<>();
        esign.put(ESIGN, List.of("sangam-esign-0.1.0.jar"));
        esign.put(AddonGuardWiring.BC_PROVIDER_RESOURCE, List.of("bcprov-jdk18on-1.84.jar"));
        assertTrue(AddonGuardWiring.esignPresent(loader(esign)));
        assertDoesNotThrow(() -> AddonGuardWiring.check(loader(esign)));
    }

    @Test
    void theNatsAndESignAddOnsTogetherRefuseStartUp() {
        Map<String, List<String>> both = new LinkedHashMap<>();
        both.put(NATS, List.of("jnats-2.26.4.jar"));
        both.put(ESIGN, List.of("sangam-esign-0.1.0.jar"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AddonGuardWiring.check(loader(both)));
        assertTrue(e.getMessage().contains("decision 235"), e.getMessage());
        assertTrue(e.getMessage().contains(AddonGuardWiring.NATS_CLASS), e.getMessage());
        assertTrue(e.getMessage().contains(AddonGuardWiring.ESIGN_CLASS), e.getMessage());
    }

    @Test
    void twoBouncyCastleProviderJarsRefuseStartUp() {
        Map<String, List<String>> two = new LinkedHashMap<>();
        two.put(AddonGuardWiring.BC_PROVIDER_RESOURCE, List.of("bcprov-lts8on-2.73.12.1.jar", "bcprov-jdk18on-1.84.jar"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AddonGuardWiring.check(loader(two)));
        assertTrue(e.getMessage().contains("more than one Bouncy Castle provider jar"), e.getMessage());
        assertTrue(e.getMessage().contains("bcprov-lts8on") && e.getMessage().contains("bcprov-jdk18on"), e.getMessage());
    }

    /**
     * W4-C REQUEST, decisions 235 and 243: the health module with Fidelius brings bcprov-jdk18on, the NATS add-on brings
     * bcprov-lts8on; together they are two Bouncy Castle provider jars, so start-up is refused by the one-provider rule,
     * whatever the class path order. The health module alone starts.
     */
    @Test
    void theHealthModuleWithFideliusAndTheNatsAddOnRefuseStartUp() {
        String fidelius = "org/sangam/connectors/health/fidelius/Fidelius.class";
        Map<String, List<String>> health = new LinkedHashMap<>();
        health.put(fidelius, List.of("sangam-connectors-health-0.1.0.jar"));
        health.put(AddonGuardWiring.BC_PROVIDER_RESOURCE, List.of("bcprov-jdk18on-1.84.jar"));
        assertFalse(AddonGuardWiring.natsPresent(loader(health)));
        assertDoesNotThrow(() -> AddonGuardWiring.check(loader(health)), "the health module alone starts");

        for (List<String> order : List.of(List.of("bcprov-jdk18on-1.84.jar", "bcprov-lts8on-2.73.12.1.jar"),
                List.of("bcprov-lts8on-2.73.12.1.jar", "bcprov-jdk18on-1.84.jar"))) {
            Map<String, List<String>> both = new LinkedHashMap<>();
            both.put(fidelius, List.of("sangam-connectors-health-0.1.0.jar"));
            both.put(NATS, List.of("jnats-2.26.4.jar"));
            both.put(AddonGuardWiring.BC_PROVIDER_RESOURCE, order);
            assertTrue(AddonGuardWiring.natsPresent(loader(both)));
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> AddonGuardWiring.check(loader(both)), String.valueOf(order));
            assertTrue(e.getMessage().contains("decision 235"), e.getMessage());
            assertTrue(e.getMessage().contains("more than one Bouncy Castle provider jar"), e.getMessage());
            assertTrue(e.getMessage().contains("bcprov-lts8on") && e.getMessage().contains("bcprov-jdk18on"),
                    e.getMessage());
        }
    }

    /**
     * W4-C REQUEST 03:52:48Z, decision 243: the health module switched on never starts beside the NATS add-on, even
     * when no second Bouncy Castle provider jar is present yet; the message names the switch and the add-on class.
     */
    @Test
    void theHealthModuleOnWithTheNatsAddOnRefusesStartUpExplicitly() {
        Map<String, List<String>> nats = new LinkedHashMap<>();
        nats.put(NATS, List.of("jnats-2.26.4.jar"));
        nats.put(AddonGuardWiring.BC_PROVIDER_RESOURCE, List.of("bcprov-lts8on-2.73.12.1.jar"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AddonGuardWiring.check(loader(nats), true));
        assertTrue(e.getMessage().contains("decision 243"), e.getMessage());
        assertTrue(e.getMessage().contains(AddonGuardWiring.HEALTH_MODULE_PROPERTY + "=true"), e.getMessage());
        assertTrue(e.getMessage().contains(AddonGuardWiring.NATS_CLASS), e.getMessage());
        assertDoesNotThrow(() -> AddonGuardWiring.check(loader(nats), false), "NATS alone starts");
        assertDoesNotThrow(() -> AddonGuardWiring.check(loader(Map.of()), true), "the health module alone starts");
    }

    @Test
    void theHealthSwitchIsReadAsHealthConnectorsWiringReadsIt() {
        assertFalse(AddonGuardWiring.healthModuleOn(new org.springframework.mock.env.MockEnvironment()),
                "missing: off");
        assertFalse(AddonGuardWiring.healthModuleOn(new org.springframework.mock.env.MockEnvironment()
                .withProperty(AddonGuardWiring.HEALTH_MODULE_PROPERTY, "false")));
        assertTrue(AddonGuardWiring.healthModuleOn(new org.springframework.mock.env.MockEnvironment()
                .withProperty(AddonGuardWiring.HEALTH_MODULE_PROPERTY, "true")));
        assertTrue(AddonGuardWiring.healthModuleOn(new org.springframework.mock.env.MockEnvironment()
                .withProperty(AddonGuardWiring.HEALTH_MODULE_PROPERTY, "TRUE")), "havingValue is case-insensitive");
    }
}
