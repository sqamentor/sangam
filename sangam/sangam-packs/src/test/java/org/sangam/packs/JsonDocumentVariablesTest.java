// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * SL-47 (after SL-48): an engine string variable holds 4,000 characters (ACT_RU_VARIABLE.TEXT_), so a JSON value that
 * grows with the case (version history, committee votes, merit list, batch items, DMN rows, event windows) must be
 * stored with {@code Vars.setJsonDocument} (UTF-8 bytes, read back by {@code Vars.jsonList} / {@code jsonMap}).
 * Guard: no pack delegate writes {@code Vars.toJson(...)} into an engine variable, except the bounded values listed.
 */
class JsonDocumentVariablesTest {

    /** Values whose size is fixed by their shape, not by the case history. */
    static final Set<String> BOUNDED = Set.of("\"approvedPayload\"", "\"batchOutcomes\"");
    static final Pattern WRITE = Pattern.compile("setVariable\\(\\s*([^,]+?)\\s*,\\s*Vars\\.toJson\\(");

    @Test
    void growingJsonValuesAreStoredAsDocuments() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher m = WRITE.matcher(src);
                while (m.find()) {
                    if (!BOUNDED.contains(m.group(1).trim())) {
                        offenders.add(root.relativize(f) + ": " + m.group(1));
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "JSON written as an engine string (4,000-char limit): " + offenders);
    }
}
