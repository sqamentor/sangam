// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.licence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * SG-26 guard: the consumer BOM pins the same Liquibase version as the build ({@code version.liquibase} in the root
 * pom), and that version is allowed by {@link LiquibaseLicenceGuard}. Run from sangam-core, so the reactor root is
 * the parent folder.
 */
class BomPinsTest {

    @Test
    void bomLiquibaseEqualsBuildPinAndIsAllowed() throws Exception {
        String root = Files.readString(Path.of("..", "pom.xml"));
        String bom = Files.readString(Path.of("..", "sangam-bom", "pom.xml"));
        Matcher pin = Pattern.compile("<version\\.liquibase>([^<]+)</version\\.liquibase>").matcher(root);
        assertTrue(pin.find(), "root pom pins version.liquibase");
        Matcher b = Pattern.compile("<artifactId>liquibase-core</artifactId><version>([^<]+)</version>").matcher(bom);
        assertTrue(b.find(), "sangam-bom pins liquibase-core");
        assertEquals(pin.group(1), b.group(1), "sangam-bom and the build pin the same Liquibase");
        LiquibaseLicenceGuard.check(b.group(1));
    }
}
