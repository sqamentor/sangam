// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.engine;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * E10 platform fit, lite (spike E10PlatformFitTest) on the real server: the server's engine deploys and runs a process
 * on this JDK + database, and the licence exclusions LIC-1..4 (no GraalJS, Oracle, MySQL or Db2 driver; ADR-D-17)
 * hold on the server's test class path. The spike's dependency-tree files ({@code target/e10/}) are produced by a
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

            TreeSet<String> jars = new TreeSet<>();
            Enumeration<URL> e = ClassLoader.getSystemClassLoader().getResources("META-INF/MANIFEST.MF");
            while (e.hasMoreElements()) {
                String u = e.nextElement().toString();
                int bang = u.indexOf("!/");
                if (u.startsWith("jar:") && bang > 0) {
                    String p = u.substring(4, bang);
                    jars.add(p.substring(p.lastIndexOf('/') + 1));
                }
            }
            List<String> forbidden = new ArrayList<>(jars.stream().filter(j -> j.contains("ojdbc") || j.contains("mysql")
                    || j.contains("db2jcc") || j.matches("(polyglot|js|js-scriptengine)-\\d.*")).toList());
            ctx.detail("classPathJarCount", jars.size());
            for (String[] f : FORBIDDEN_CLASSES) {
                ctx.check(f[0] + " absent from the class path (" + f[1] + ")", !present(f[1]));
            }
            ctx.check("no forbidden jar on the class path " + forbidden, forbidden.isEmpty());
        });
    }

    private static boolean present(String name) {
        try {
            Class.forName(name, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
