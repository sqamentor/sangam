// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * SL-92: the ports mapper registers its modules explicitly. Classpath discovery picked up the FEEL engine's
 * jackson-module-scala (operaton-engine -> operaton-engine-dmn -> feel-engine), which reads untyped nested JSON as
 * Scala collections; {@link Json#map} and {@link Json#plain} must yield {@code java.util.Map} / {@code java.util.List}.
 */
class JsonModulesTest {

    @Test
    void scalaModuleIsDiscoverableOnThisClassPath() throws java.io.IOException {
        List<String> declared = new java.util.ArrayList<>();
        java.util.Enumeration<java.net.URL> spi = getClass().getClassLoader()
                .getResources("META-INF/services/com.fasterxml.jackson.databind.Module");
        while (spi.hasMoreElements()) {
            try (java.io.InputStream in = spi.nextElement().openStream()) {
                declared.addAll(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                        .map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList());
            }
        }
        assertTrue(declared.contains("com.fasterxml.jackson.module.scala.DefaultScalaModule"),
                "jackson-module-scala must be discoverable on the test class path (FEEL-Scala closure), found " + declared);
    }

    @Test
    void nestedValuesAreJavaCollections() {
        Map<String, Object> m = Json.map("{\"a\":{\"b\":{\"c\":1}},\"l\":[{\"x\":\"y\"},[2]]}");
        assertInstanceOf(java.util.Map.class, m.get("a"));
        assertInstanceOf(java.util.Map.class, ((Map<?, ?>) m.get("a")).get("b"));
        List<?> l = assertInstanceOf(java.util.List.class, m.get("l"));
        assertInstanceOf(java.util.Map.class, l.get(0));
        assertInstanceOf(java.util.List.class, l.get(1));

        Map<String, Object> viaNode = Json.map(Json.tree("{\"p\":{\"q\":[1,2]}}"));
        assertInstanceOf(java.util.List.class, ((Map<?, ?>) viaNode.get("p")).get("q"));

        assertInstanceOf(java.util.Map.class, Json.plain(Json.tree("{\"k\":{\"v\":1}}")));
        assertInstanceOf(java.util.List.class, Json.plain(Json.tree("[{\"v\":1}]")));
        assertEquals(Map.of("k", Map.of("v", 1)), Json.plain(Json.tree("{\"k\":{\"v\":1}}")));
    }

    @Test
    void nestedMapsRoundTrip() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("outer", Map.of("inner", Map.of("list", List.of(Map.of("z", true), 3))));
        assertEquals(in, Json.map(Json.write(in)));
    }

    /** Same Java-type behaviour the server had with discovery: java.time as timestamps (mapper default), Optional unwrapped. */
    @Test
    void javaTimeAndOptionalAreSupported() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", Instant.ofEpochSecond(1_700_000_000L));
        m.put("opt", Optional.of("x"));
        m.put("none", Optional.empty());
        assertEquals("{\"at\":1700000000.000000000,\"opt\":\"x\",\"none\":null}", Json.write(m));
    }
}
