// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M-150, second half: the supported build and run need no optional host. Quarkus (and any later optional host
 * framework) is never in the default reactor and never on the baseline host's class path; an optional host is a
 * reactor profile of its own (design note section 5.1).
 */
public final class OptionalHosts {

    /** Group ids of optional host frameworks that the default reactor never declares. */
    public static final List<String> OPTIONAL_HOST_GROUPS = List.of("io.quarkus", "io.micronaut", "io.helidon");

    /**
     * Classes whose presence means an optional host framework is on the class path. Built from the group ids: this is a
     * presence probe by name ({@link #onClassPath}), never a use of the framework, so the kit stays outside
     * {@code HostFrameworkBoundaryTest}'s hosts.
     */
    public static final List<String> OPTIONAL_HOST_CLASSES = List.of(
            OPTIONAL_HOST_GROUPS.get(0) + ".runtime.Quarkus",
            OPTIONAL_HOST_GROUPS.get(0) + ".arc.Arc",
            OPTIONAL_HOST_GROUPS.get(1) + ".context.ApplicationContext",
            OPTIONAL_HOST_GROUPS.get(2) + ".webserver.WebServer");

    private static final Pattern MODULE = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");
    private static final Pattern GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");

    private OptionalHosts() {
    }

    /** The optional-host classes loadable from {@code loader} (empty on a supported baseline run). */
    public static List<String> onClassPath(ClassLoader loader) {
        List<String> out = new ArrayList<>();
        for (String c : OPTIONAL_HOST_CLASSES) {
            try {
                Class.forName(c, false, loader);
                out.add(c);
            } catch (ClassNotFoundException | LinkageError e) {
                // absent, as it should be
            }
        }
        return out;
    }

    /** The reactor root: the nearest folder at or above {@code from} whose pom is {@code sangam-parent}. */
    public static Path reactorRoot(Path from) throws IOException {
        for (Path p = from.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            Path pom = p.resolve("pom.xml");
            if (Files.isRegularFile(pom)
                    && Files.readString(pom, StandardCharsets.UTF_8).contains("<artifactId>sangam-parent</artifactId>")) {
                return p;
            }
        }
        throw new IllegalStateException("no sangam-parent pom at or above " + from);
    }

    /** The modules of the default reactor: the {@code <modules>} of the root pom outside {@code <profiles>}. */
    public static List<String> defaultModules(Path reactorRoot) throws IOException {
        String pom = Files.readString(reactorRoot.resolve("pom.xml"), StandardCharsets.UTF_8);
        String noComments = pom.replaceAll("(?s)<!--.*?-->", "");
        int profiles = noComments.indexOf("<profiles>");
        String head = profiles < 0 ? noComments : noComments.substring(0, profiles);
        int start = head.indexOf("<modules>");
        int end = head.indexOf("</modules>");
        List<String> out = new ArrayList<>();
        if (start < 0 || end < start) {
            return out;
        }
        Matcher m = MODULE.matcher(head.substring(start, end));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** {@code module: groupId} for every default-reactor module pom that declares an optional host framework. */
    public static List<String> defaultReactorOffenders(Path reactorRoot) throws IOException {
        List<String> out = new ArrayList<>();
        for (String module : defaultModules(reactorRoot)) {
            Path pom = reactorRoot.resolve(module).resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                out.add(module + ": no pom.xml");
                continue;
            }
            String text = Files.readString(pom, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
            Matcher m = GROUP.matcher(text);
            while (m.find()) {
                String g = m.group(1);
                for (String banned : OPTIONAL_HOST_GROUPS) {
                    if (g.equals(banned) || g.startsWith(banned + ".")) {
                        out.add(module + ": " + g);
                    }
                }
            }
        }
        return out;
    }
}
