// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M-092 schema part of {@code sangam upgrade check --schema DATABASE}: the applied product changesets of the running
 * installation ({@code GET /ops/schema-status}) against the product changelog bundled into the CLI of the TARGET
 * release ({@code db/changelog/sangam-master.xml} and the formatted-SQL files it includes, copied from sangam-persistence
 * at build time).
 * <ul>
 *   <li>Applied ids the target does not know: the database was migrated by a newer release. Downgrades are not
 *       supported, so this blocks.</li>
 *   <li>The Liquibase lock is held: a migration is running, or a crashed one left the lock. This blocks.</li>
 *   <li>Target ids not applied yet: the migration Job of the upgrade applies them (information).</li>
 * </ul>
 * Changeset ids are matched per database because some changesets exist for one dialect only ({@code dbms:}).
 */
final class SchemaCheck {

    static final String MASTER = "db/changelog/sangam-master.xml";
    static final Set<String> DATABASES = Set.of("postgresql", "oracle", "mssql", "mysql", "h2");
    private static final Pattern CHANGESET = Pattern.compile("^--changeset\\s+([^:\\s]+):(\\S+)(.*)$");
    private static final Pattern DBMS = Pattern.compile("\\bdbms:(\\S+)");
    private static final int SHOWN = 10;

    private SchemaCheck() {
    }

    static String database(String value) {
        String d = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!DATABASES.contains(d)) {
            throw new IllegalArgumentException("--schema must be one of " + new java.util.TreeSet<>(DATABASES));
        }
        return d;
    }

    /** The product changeset ids of the bundled changelog that apply to {@code database}, in changelog order. */
    static List<String> bundledIds(String database) throws IOException {
        List<String> ids = new ArrayList<>();
        for (String file : includes()) {
            try (InputStream in = open("db/changelog/" + file)) {
                ids.addAll(changesetIds(new InputStreamReader(in, StandardCharsets.UTF_8), database));
            }
        }
        return ids;
    }

    /** The {@code include file=} entries of the master changelog, in order. */
    static List<String> includes() throws IOException {
        try (InputStream in = open(MASTER)) {
            NodeList list = UpgradeCheck.secureParser().parse(in).getElementsByTagNameNS("*", "include");
            List<String> files = new ArrayList<>();
            for (int i = 0; i < list.getLength(); i++) {
                files.add(((Element) list.item(i)).getAttribute("file"));
            }
            return files;
        } catch (SAXException e) {
            throw new IOException("the bundled " + MASTER + " is not well formed: " + e.getMessage(), e);
        }
    }

    /** Ids of the {@code --changeset author:id [dbms:a,b]} lines that apply to {@code database}. */
    static List<String> changesetIds(Reader sql, String database) throws IOException {
        List<String> ids = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(sql)) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                Matcher m = CHANGESET.matcher(line.trim());
                if (m.matches() && appliesTo(m.group(3), database)) {
                    ids.add(m.group(2));
                }
            }
        }
        return ids;
    }

    static boolean appliesTo(String attributes, String database) {
        Matcher m = DBMS.matcher(attributes);
        if (!m.find()) {
            return true;
        }
        boolean anyPositive = false;
        for (String d : m.group(1).toLowerCase(Locale.ROOT).split(",")) {
            if (d.equals("!" + database)) {
                return false;
            }
            if (!d.startsWith("!")) {
                anyPositive = true;
                if (d.equals(database) || d.equals("all")) {
                    return true;
                }
            }
        }
        return !anyPositive;
    }

    /** Adds the findings of the comparison; {@code status} is the server's {@code SchemaStatus}. */
    static void compare(List<String> bundled, JsonNode status, List<UpgradeCheck.Finding> findings) {
        Set<String> target = new LinkedHashSet<>(bundled);
        Set<String> applied = new LinkedHashSet<>();
        status.path("product").path("ids").forEach(n -> applied.add(n.asText()));
        List<String> unknown = applied.stream().filter(id -> !target.contains(id)).toList();
        List<String> pending = target.stream().filter(id -> !applied.contains(id)).toList();
        if (status.path("liquibaseLocked").asBoolean(false)) {
            findings.add(new UpgradeCheck.Finding("BLOCKING", "schema", "DATABASECHANGELOGLOCK",
                    "the Liquibase lock is held by " + status.path("lockedBy").asText("unknown") + " since "
                            + status.path("lockGrantedAt").asText("unknown")
                            + ": wait for that migration, or release a stale lock only after checking it is not running"));
        }
        if (!unknown.isEmpty()) {
            findings.add(new UpgradeCheck.Finding("BLOCKING", "schema", "DATABASECHANGELOG",
                    unknown.size() + " applied changeset(s) are not in this release (" + shown(unknown)
                            + "): a newer release migrated this database, and downgrades are not supported"));
        }
        if (!pending.isEmpty()) {
            findings.add(new UpgradeCheck.Finding("INFO", "schema", "DATABASECHANGELOG",
                    pending.size() + " changeset(s) will be applied by the upgrade's migration Job (" + shown(pending) + ")"));
        }
    }

    private static String shown(List<String> ids) {
        return ids.size() <= SHOWN ? String.join(", ", ids)
                : String.join(", ", ids.subList(0, SHOWN)) + " and " + (ids.size() - SHOWN) + " more";
    }

    private static InputStream open(String resource) throws IOException {
        InputStream in = SchemaCheck.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("the CLI build does not contain " + resource);
        }
        return in;
    }
}
