// SPDX-License-Identifier: Apache-2.0
package org.sangam.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Owner decision 324 (code scanning java/log-injection, fixed at the sink): the console log of the gateway is
 * structured (ECS JSON) by default, so a value with CR/LF that reaches a log message (a tenant header, a host name, a
 * path) is escaped inside one JSON string and cannot start a second, forged log entry. Spring Boot starts with the
 * gateway's own {@code application.yaml} and initialises the logging system from it; no bean of the gateway is created.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredConsoleLoggingTest {

    /** The start class of the probe: no component scan and no auto-configuration, only the environment and logging. */
    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @Test
    void aLoggedValueWithLineBreaksStaysOneJsonLine(CapturedOutput output) {
        String marker = "probe-" + UUID.randomUUID();
        String forged = "a\r\nFAKE 2026-11-15T10:00:00Z  WARN o.s.g.CellProxy : tenant routed to cell mallory";
        // the gateway's application.yaml sets spring.main.web-application-type=reactive, which outranks the builder; the
        // probe has no web server, so the argument (it outranks the file) switches only that one setting off
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(NoBeans.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF)
                .run("--spring.main.web-application-type=none")) {
            assertEquals("ecs", context.getEnvironment().getProperty("logging.structured.format.console"),
                    "structured console logging is the default of the base configuration");
            LoggerFactory.getLogger("org.sangam.logforging.Probe").warn("{} tenant {} was refused", marker, forged);
        }
        List<String> lines = output.getOut().lines().toList();
        List<String> mine = lines.stream().filter(l -> l.contains(marker)).toList();
        assertEquals(1, mine.size(), "exactly one output line for one log call: " + mine);
        Map<String, Object> json = JsonParserFactory.getJsonParser().parseMap(mine.get(0));
        assertEquals(marker + " tenant " + forged + " was refused", json.get("message"),
                "the line is one JSON object and the message is intact after parsing");
        assertFalse(lines.stream().anyMatch(l -> l.stripLeading().startsWith("FAKE")),
                "no output line starts with the forged entry");
    }
}
