// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * SG-33: the Operaton FEEL engine closure (SG-27) pins jackson-module-scala 2.22.3, which refuses at run time any
 * jackson-databind outside its own minor line ({@code >= 2.22.0 and < 2.23.0}). A consumer whose parent pom manages an
 * older or newer jackson-databind wins over the imported sangam-bom (inherited dependencyManagement beats imports), and
 * the FEEL engine then fails deep inside Scala module registration. {@link SangamFeel#install} calls {@link #check()}
 * before the FEEL engine is built, so that build fails fast with the supported range and the fix. Versions are read
 * reflectively so the check itself never links against a mismatched Jackson.
 */
public final class JacksonCompatibilityGuard {

    /** The jackson-databind minor line Sangam is built and tested with (SUPPORT-MATRIX.md). */
    public static final String SUPPORTED = "2.22";
    /** The Jackson version Sangam pins (sangam-bom, root pom version.jackson). */
    public static final String PINNED = "2.22.3";

    private static final String SCALA_POM =
            "META-INF/maven/com.fasterxml.jackson.module/jackson-module-scala_2.13/pom.properties";

    private JacksonCompatibilityGuard() {
    }

    /** @throws IllegalStateException when the runtime jackson-databind cannot work with the FEEL engine closure */
    public static void check() {
        String databind;
        try {
            Class<?> pv = Class.forName("com.fasterxml.jackson.databind.cfg.PackageVersion");
            databind = String.valueOf(pv.getField("VERSION").get(null));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("jackson-databind is not on the class path; the Sangam FEEL engine needs "
                    + "jackson-databind " + SUPPORTED + ".x (import org.sangam:sangam-bom)", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read the jackson-databind version (SG-33 check)", e);
        }
        check(databind, scalaModuleVersion());
    }

    /**
     * Checks explicit versions; public for tests.
     *
     * @param databind    runtime jackson-databind version, for example {@code 2.21.7}
     * @param scalaModule runtime jackson-module-scala version, or {@code null} when it is not on the class path
     */
    public static void check(String databind, String scalaModule) {
        String line = minorLine(databind);
        if (!SUPPORTED.equals(line)) {
            throw new IllegalStateException(message(databind, scalaModule));
        }
        if (scalaModule != null && !line.equals(minorLine(scalaModule))) {
            throw new IllegalStateException(message(databind, scalaModule));
        }
    }

    private static String message(String databind, String scalaModule) {
        return "jackson-databind " + databind + " is on the class path, but the Sangam FEEL engine (Operaton DMN, "
                + "jackson-module-scala " + (scalaModule == null ? PINNED : scalaModule) + ") requires jackson-databind "
                + ">= " + SUPPORTED + ".0 and < 2.23.0 (supported: " + SUPPORTED + ".x, SG-33). Your build's own "
                + "dependency management overrides Sangam's pin (inherited dependencyManagement wins over an imported "
                + "BOM): import org.sangam:sangam-bom and set jackson.version=" + PINNED + " (Spring Boot) or import "
                + "com.fasterxml.jackson:jackson-bom:" + PINNED + " in your own parent pom.";
    }

    /** {@code "2.22.3"} -> {@code "2.22"}; {@code null} when the version is not {@code major.minor[...]}. */
    static String minorLine(String version) {
        if (version == null) {
            return null;
        }
        String[] p = version.trim().split("[.\\-]");
        if (p.length < 2 || !p[0].matches("\\d+") || !p[1].matches("\\d+")) {
            return null;
        }
        return Integer.parseInt(p[0]) + "." + Integer.parseInt(p[1]);
    }

    private static String scalaModuleVersion() {
        ClassLoader cl = JacksonCompatibilityGuard.class.getClassLoader();
        try (InputStream in = cl == null ? null : cl.getResourceAsStream(SCALA_POM)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // fall through to the package version
        }
        try {
            Package pkg = Class.forName("com.fasterxml.jackson.module.scala.DefaultScalaModule").getPackage();
            return pkg == null ? null : pkg.getImplementationVersion();
        } catch (ClassNotFoundException e) {
            return null; // no Scala module: only the databind line is checked
        }
    }
}
