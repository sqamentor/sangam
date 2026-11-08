// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * Guards against a seat id written where a variable is expected (regression: pension {@code ${HOO-P}} parsed as
 * {@code HOO minus P}; death claim {@code ${INVESTIGATOR}} unresolved). Seats are data: a model names a variable that
 * the pack configuration sets to the seat id.
 */
class PackModelLintTest {

    static final Pattern ASSIGN = Pattern.compile("operaton:(candidateGroups|candidateUsers|assignee)=\"\\$\\{([^}]*)}\"");
    static final Pattern SEAT_VAR = Pattern.compile("name=\"seatVar\"><operaton:string>([^<]*)</operaton:string>");
    static final Pattern VARIABLE = Pattern.compile("[a-z][A-Za-z0-9_]*");

    @Test
    void seatsAreReferencedThroughVariables() throws Exception {
        List<String> bad = new ArrayList<>();
        for (SangamPack p : PackRegistry.discover().all()) {
            for (PackDescriptor.PackResource r : p.descriptor().resources(PackDescriptor.PackResource.Kind.BPMN)) {
                String xml;
                try (InputStream in = p.open(r.path())) {
                    xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                Matcher m = ASSIGN.matcher(xml);
                while (m.find()) {
                    String expr = m.group(2).trim();
                    String head = expr.split("[.\\[( ]", 2)[0];
                    if (!VARIABLE.matcher(head).matches()) {
                        bad.add(r.path() + ": " + m.group(1) + "=${" + expr + "}");
                    }
                }
                m = SEAT_VAR.matcher(xml);
                while (m.find()) {
                    if (!VARIABLE.matcher(m.group(1)).matches()) {
                        bad.add(r.path() + ": seatVar " + m.group(1));
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }
}
