// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * SL-92: {@link Sql}'s mapper must register its modules explicitly. With classpath discovery
 * ({@code findAndRegisterModules}) the FEEL engine's jackson-module-scala (operaton-engine-dmn -> feel-engine) is
 * registered too, every nested JSON object is read as a Scala {@code Map}, and {@link Sql#asMap} turns it into an
 * empty map, so nested service-point settings ({@code skillRouting}, {@code priorityLevels}, {@code seriesBySkill},
 * {@code slaMinutesByClass}) are silently ignored.
 */
class SqlJsonTest {

    /** The test only proves something when the module that broke production is discoverable here too. */
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
    void nestedObjectsAndArraysAreJavaCollections() {
        Map<String, Object> m = Sql.mapJson(
                "{\"skillRouting\":{\"R1\":\"PASSPORT\"},\"deep\":{\"a\":{\"b\":[{\"c\":1}]}},\"list\":[1,{\"x\":\"y\"}]}");
        assertInstanceOf(LinkedHashMap.class, m);
        assertInstanceOf(java.util.Map.class, m.get("skillRouting"));
        assertEquals("PASSPORT", Sql.asMap(m.get("skillRouting")).get("R1"));
        Map<String, Object> a = Sql.asMap(Sql.asMap(m.get("deep")).get("a"));
        assertInstanceOf(java.util.List.class, a.get("b"));
        assertEquals(1, Sql.asMap(Sql.asList(a.get("b")).get(0)).get("c"));
        List<Object> list = Sql.asList(m.get("list"));
        assertEquals(2, list.size());
        assertInstanceOf(java.util.Map.class, list.get(1));
        assertEquals("y", Sql.asMap(list.get(1)).get("x"));

        List<Object> top = Sql.listJson("[{\"k\":\"v\"},[2]]");
        assertInstanceOf(java.util.Map.class, top.get(0));
        assertInstanceOf(java.util.List.class, top.get(1));
    }

    @Test
    void nestedMapsRoundTripAsJavaMaps() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("skillRouting", Map.of("R1", "PASSPORT"));
        cfg.put("priorityLevels", Map.of("SENIOR", 5));
        cfg.put("seriesBySkill", Map.of("PASSPORT", "P"));
        cfg.put("slaMinutesByClass", Map.of("SENIOR", 7));
        cfg.put("nested", Map.of("inner", Map.of("list", List.of(Map.of("z", true)))));
        Map<String, Object> back = Sql.mapJson(Sql.toJson(cfg));
        assertEquals(cfg, back);
        assertEquals(back, Sql.mapJson(Sql.toJson(back)));
    }

    /** The explicitly registered Java modules keep the behaviour the server had: ISO-8601 dates, Optional unwrapped. */
    @Test
    void javaTimeAndOptionalAreWrittenAsBefore() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", Instant.parse("2026-11-11T10:15:30Z"));
        m.put("day", LocalDate.of(2026, 11, 11));
        m.put("opt", Optional.of("x"));
        m.put("none", Optional.empty());
        assertEquals("{\"at\":\"2026-11-11T10:15:30Z\",\"day\":\"2026-11-11\",\"none\":null,\"opt\":\"x\"}", Sql.toJson(m));
    }
}
