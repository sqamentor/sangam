// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every column a {@link ResourceDescriptor} names for a Sangam-owned table (id, tenant, scope and attribute columns) exists
 * in that table as the changelog generator ({@code sangam-persistence/tools/generate_changelogs.py}, the single source of
 * the schema) declares it. {@link PolicyCompiler} and {@link AuthorizedQueryBuilder} put these names into SQL, so a wrong
 * name fails every object check and list of that type at run time. The registry once named {@code item_id} for
 * {@code RECONCILIATION_ITEM} and {@code link_id} for {@code INSTANCE_LINK}, whose primary key is {@code id} in both.
 * Engine tables ({@code ACT_*}) belong to Operaton's own schema and are out of scope here.
 * Written for the test phase (not run here).
 */
class ResourceRegistryColumnsTest {

    static final Path GENERATOR = Path.of("..", "sangam-persistence", "tools", "generate_changelogs.py");
    /** One generator entry: ("0002-02", "description", "TABLE", [ ("col", "{type}", "constraint"), ... ], ...). */
    static final Pattern ENTRY = Pattern.compile("\\(\"\\d{4}-\\d{2}\",\\s*\"[^\"]*\",\\s*\"([A-Z0-9_]+)\",\\s*\\[(.*?)\\]",
            Pattern.DOTALL);
    static final Pattern COLUMN = Pattern.compile("\\(\"([A-Za-z0-9_]+)\",\\s*\"\\{");

    static Set<String> generatorColumns(String source, String table) {
        Set<String> cols = new HashSet<>();
        Matcher e = ENTRY.matcher(source);
        while (e.find()) {
            if (e.group(1).equals(table)) {
                Matcher c = COLUMN.matcher(e.group(2));
                while (c.find()) {
                    cols.add(c.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return cols;
    }

    static List<String> namedColumns(ResourceDescriptor d) {
        List<String> names = new ArrayList<>(List.of(d.idColumn(), d.tenantColumn()));
        d.scopes().forEach(s -> names.add(s.column()));
        names.addAll(d.attributes().values());
        return names;
    }

    @Test
    void everySangamTableDescriptorNamesColumnsTheGeneratorDeclares() throws IOException {
        String source = Files.readString(GENERATOR, StandardCharsets.UTF_8);
        ResourceRegistry registry = new ResourceRegistry();
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (String type : registry.types()) {
            ResourceDescriptor d = registry.get(type);
            if (d.table() == null || d.table().startsWith("ACT_")) {
                continue;
            }
            Set<String> cols = generatorColumns(source, d.table());
            assertFalse(cols.isEmpty(), type + ": table " + d.table() + " is not declared by the changelog generator");
            for (String name : namedColumns(d)) {
                if (!cols.contains(name.toLowerCase(Locale.ROOT))) {
                    missing.add(type + " -> " + d.table() + "." + name);
                }
            }
            checked++;
        }
        assertTrue(checked >= 3, "expected the AUDIT, INSTANCE_LINK and RECONCILIATION_ITEM descriptors, checked " + checked);
        assertTrue(missing.isEmpty(), "descriptor columns missing from the schema: " + missing);
    }

    @Test
    void reconciliationItemsAndInstanceLinksUseTheirPrimaryKey() {
        ResourceRegistry registry = new ResourceRegistry();
        assertTrue("id".equals(registry.get(ResourceRegistry.RECONCILIATION_ITEM).idColumn()));
        assertTrue("id".equals(registry.get(ResourceRegistry.INSTANCE_LINK).idColumn()));
    }
}
