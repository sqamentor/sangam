// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * D-12 on the server: a call activity without a binding binds to its own deployment, and every pack is its own
 * deployment ({@code pack:<id>@<version>}). A call into another pack (the core blocks, a called pack) must therefore
 * be pinned to the callee pack's version by {@code versionTag}; otherwise it fails at run time with "no processes
 * deployed with key ... in deployment" (found by IfmsPacksIT on PostgreSQL). Calls inside one pack keep the deployment
 * binding.
 */
class CrossPackCallBindingTest {

    private static final Pattern PROCESS = Pattern.compile("<process\\b[^>]*\\bid=\"([^\"]+)\"[^>]*operaton:versionTag=\"([^\"]+)\"");
    private static final Pattern CALL = Pattern.compile("<callActivity\\b[^>]*>");
    private static final Pattern CALLED = Pattern.compile("\\bcalledElement=\"([^\"]+)\"");

    @Test
    void everyCrossPackCallIsPinnedToTheCalleePackVersion() throws Exception {
        Path root = Path.of("src/main/resources/packs");
        Map<String, String> packOf = new HashMap<>();
        Map<String, String> versionOf = new HashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".bpmn")).toList();
        }
        for (Path f : files) {
            Matcher m = PROCESS.matcher(Files.readString(f));
            while (m.find()) {
                packOf.put(m.group(1), root.relativize(f).getName(0).toString());
                versionOf.put(m.group(1), m.group(2));
            }
        }
        List<String> problems = new ArrayList<>();
        int cross = 0;
        for (Path f : files) {
            String pack = root.relativize(f).getName(0).toString();
            Matcher c = CALL.matcher(Files.readString(f));
            while (c.find()) {
                Matcher called = CALLED.matcher(c.group());
                if (!called.find() || !packOf.containsKey(called.group(1)) || pack.equals(packOf.get(called.group(1)))) {
                    continue;
                }
                cross++;
                String want = "operaton:calledElementBinding=\"versionTag\" operaton:calledElementVersionTag=\""
                        + versionOf.get(called.group(1)) + "\"";
                if (!c.group().contains(want)) {
                    problems.add(root.relativize(f) + ": " + c.group());
                }
            }
        }
        assertTrue(cross > 0, "the packs call the core blocks");
        assertEquals(List.of(), problems, "cross-pack calls without a versionTag pin");
    }
}
