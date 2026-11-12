// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.sangam.packs.spi.PackDescriptor.PackResource;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * SL-54: a pack version is one immutable unit. Every process and decision a pack ships carries
 * {@code operaton:versionTag} = the pack version, and every form names it, so a version bump (packgen
 * {@code releases.py}) reaches every artifact; together with {@link CrossPackCallBindingTest} (pins = the callee's
 * versionTag) every cross-pack call names the version the callee pack ships.
 */
class PackVersionConsistencyTest {

    private static final Pattern PROCESS = Pattern.compile("<process\\b[^>]*>");
    private static final Pattern DECISION = Pattern.compile("<decision\\b[^>]*>");
    private static final Pattern ID = Pattern.compile("\\bid=\"([^\"]+)\"");
    private static final Pattern TAG = Pattern.compile("\\boperaton:versionTag=\"([^\"]*)\"");
    private static final Pattern FORM_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"");

    @Test
    void everyArtifactOfAPackCarriesThePackVersion() throws IOException {
        List<String> problems = new ArrayList<>();
        int packs = 0;
        for (SangamPack p : PackRegistry.discover().all()) {
            packs++;
            String version = p.descriptor().version();
            for (PackResource r : p.descriptor().resources()) {
                Pattern element = switch (r.kind()) {
                    case BPMN -> PROCESS;
                    case DMN -> DECISION;
                    default -> null;
                };
                if (element == null && r.kind() != PackResource.Kind.FORM) {
                    continue;
                }
                String text;
                try (InputStream in = p.open(r.path())) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (element == null) { // a form: its top-level "version" is the first one in the file
                    Matcher v = FORM_VERSION.matcher(text);
                    if (!v.find() || !v.group(1).equals(version)) {
                        problems.add(p.descriptor().id() + "@" + version + " " + r.path() + ": form version "
                                + (v.find(0) ? v.group(1) : "missing"));
                    }
                    continue;
                }
                Matcher m = element.matcher(text);
                while (m.find()) {
                    Matcher tag = TAG.matcher(m.group());
                    String found = tag.find() ? tag.group(1) : null;
                    if (!version.equals(found)) {
                        Matcher id = ID.matcher(m.group());
                        problems.add(p.descriptor().id() + "@" + version + " " + r.path() + ": "
                                + (id.find() ? id.group(1) : m.group()) + " versionTag " + found);
                    }
                }
            }
        }
        assertTrue(packs > 0, "packs on the class path");
        assertEquals(List.of(), problems, "artifacts whose version is not their pack's version");
    }
}
