// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every deploy path must build its model validator from the same settings (SangamModelValidator.Options.of), so app
 * bundles and engine deployments refuse the same constructs. Regression: the app-bundle path passed only the ad-hoc
 * flag and refused the standard loop (patch P-6) that the engine path accepted.
 */
class ValidatorOptionsWiringGuardTest {

    private static final Pattern NEW_VALIDATOR = Pattern.compile("new\\s+SangamModelValidator\\s*\\(([^;]*)");

    @Test
    void everyServerValidatorUsesOptionsOf() throws IOException {
        Path main = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(main), "run from the sangam-server module");
        int found = 0;
        StringBuilder bad = new StringBuilder();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = NEW_VALIDATOR.matcher(Files.readString(f));
                while (m.find()) {
                    found++;
                    if (!m.group(1).contains("Options.of(")) {
                        bad.append(f).append(": ").append(m.group(0).strip()).append('\n');
                    }
                }
            }
        }
        assertTrue(found > 0, "no SangamModelValidator construction found; the guard would check nothing");
        assertEquals("", bad.toString(), "build the validator with SangamModelValidator.Options.of(settings)");
    }
}
