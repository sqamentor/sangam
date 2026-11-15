// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Code scanning java/log-injection (13 alerts: a tenant id, a path, a header or an exception text of a request reaches
 * a log message), fixed once at the sink: the console log of the server is structured (ECS JSON) in every profile, so
 * a value with CR/LF is escaped inside one JSON string and cannot start a second, forged log entry.
 *
 * <p>The server's real configuration is used: Spring Boot starts with the application's own {@code application.yaml}
 * (and the profile's file) and initialises the logging system from it; no bean of the server is created. A message
 * with a line break is then logged through SLF4J and read back from the console.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredConsoleLoggingTest {

    /** The start class of the probe: no component scan and no auto-configuration, only the environment and logging. */
    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "h2", "postgres", "oracle", "sqlserver", "mysql"})
    void aLoggedValueWithLineBreaksStaysOneJsonLine(String profile, CapturedOutput output) {
        String marker = "probe-" + UUID.randomUUID();
        String forged = "a\r\nFAKE 2026-11-15T10:00:00Z  WARN o.s.s.BreakGlassAlarm : break-glass granted to mallory";
        SpringApplicationBuilder app = new SpringApplicationBuilder(NoBeans.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF);
        if (!profile.isEmpty()) {
            app.profiles(profile);
        }
        try (ConfigurableApplicationContext context = app.run()) {
            assertEquals("ecs", context.getEnvironment().getProperty("logging.structured.format.console"),
                    "structured console logging is the default of the base configuration, profile '" + profile + "'");
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
