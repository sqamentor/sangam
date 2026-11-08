// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * E10 platform fit, lite (spike E10PlatformFitTest) on the real server: the server's engine deploys and runs a process
 * on this JDK + database, and the licence exclusions LIC-1..4 (no GraalJS, Oracle, MySQL or Db2 driver; ADR-D-17)
 * hold in the shipped Boot jar (BOOT-INF/lib and BOOT-INF/classes), not merely on the test class path. The spike's dependency-tree files ({@code target/e10/}) are produced by a
 * manual build step and are not ported; the class-path check covers the same artifacts at run time.
 */
class E10PlatformFitIT extends EngineScenarioSupport {

    private static final String[][] FORBIDDEN_CLASSES = {
            {"LIC-1 GraalJS", "org.graalvm.polyglot.Context"},
            {"LIC-2 Oracle JDBC", "oracle.jdbc.OracleDriver"},
            {"LIC-3 MySQL JDBC", "com.mysql.cj.jdbc.Driver"},
            {"LIC-4 Db2 JDBC", "com.ibm.db2.jcc.DB2Driver"}};

    @Test
    void platform() {
        scenario("E10-sangam", ctx -> {
            ctx.detail("javaVersion", System.getProperty("java.version"));
            adapter.deploy(null, "e10-" + RUN, Map.of("e10.bpmn", ("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                    + " targetNamespace=\"e10\"><process id=\"e10\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                    + "<sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/><endEvent id=\"e\"/></process></definitions>")
                    .getBytes(StandardCharsets.UTF_8)));
            String pid = adapter.startProcess(null, "e10", key("e10"), Map.of());
            ctx.check("engine deploys and runs a process on JDK " + System.getProperty("java.version"), ended(pid));

            // LIC-1..4 are measured on what ships: BOOT-INF/lib of the repackaged Boot jar built by this reactor
            // (package runs before integration-test), not on the test class path.
            java.nio.file.Path bootJar = shippedBootJar();
            ctx.detail("bootJar", bootJar.toString());
            TreeSet<String> jars = new TreeSet<>();
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(bootJar.toFile())) {
                jf.stream().map(java.util.jar.JarEntry::getName)
                        .filter(n -> n.startsWith("BOOT-INF/lib/") && n.endsWith(".jar"))
                        .forEach(n -> jars.add(n.substring("BOOT-INF/lib/".length())));
            }
            ctx.check("shipped Boot jar has BOOT-INF/lib entries", !jars.isEmpty());
            List<String> forbidden = new ArrayList<>(jars.stream().filter(j -> j.contains("ojdbc") || j.contains("mysql")
                    || j.contains("db2jcc") || j.matches("(polyglot|js|js-scriptengine)-\\d.*")).toList());
            ctx.detail("bootJarLibCount", jars.size());
            for (String[] f : FORBIDDEN_CLASSES) {
                ctx.check(f[0] + " absent from the shipped Boot jar (" + f[1] + ")", !shipped(bootJar, f[1]));
            }
            ctx.check("no forbidden jar in the shipped Boot jar BOOT-INF/lib " + forbidden, forbidden.isEmpty());
        });
    }

    private static java.nio.file.Path shippedBootJar() throws java.io.IOException {
        // the build directory (${sangam.target}) is the parent of this class's test-classes folder
        java.nio.file.Path target;
        try {
            target = java.nio.file.Path.of(E10PlatformFitIT.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI()).getParent();
        } catch (java.net.URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
        try (var s = java.nio.file.Files.list(target)) {
            return s.filter(p -> p.getFileName().toString().matches("sangam-server-.*\\.jar")
                            && !p.getFileName().toString().endsWith(".original"))
                    .filter(p -> {
                        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(p.toFile())) {
                            return jf.getEntry("BOOT-INF/lib/") != null || jf.stream().anyMatch(en -> en.getName().startsWith("BOOT-INF/lib/"));
                        } catch (java.io.IOException ex) {
                            return false;
                        }
                    })
                    .findFirst().orElseThrow(() -> new IllegalStateException("no repackaged Boot jar in " + target));
        }
    }

    /** True when the class is in BOOT-INF/classes or in any nested BOOT-INF/lib jar of the shipped Boot jar. */
    private static boolean shipped(java.nio.file.Path bootJar, String className) throws java.io.IOException {
        String entry = className.replace('.', '/') + ".class";
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(bootJar.toFile())) {
            if (jf.getEntry("BOOT-INF/classes/" + entry) != null) {
                return true;
            }
            for (var it = jf.entries(); it.hasMoreElements();) {
                java.util.jar.JarEntry je = it.nextElement();
                if (!je.getName().startsWith("BOOT-INF/lib/") || !je.getName().endsWith(".jar")) {
                    continue;
                }
                try (java.util.jar.JarInputStream in = new java.util.jar.JarInputStream(jf.getInputStream(je))) {
                    for (java.util.jar.JarEntry n = in.getNextJarEntry(); n != null; n = in.getNextJarEntry()) {
                        if (entry.equals(n.getName())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
