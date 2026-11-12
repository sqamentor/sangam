// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.plugandplay;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.sangam.core.integration.SangamConnector;

/**
 * AT-6 set-up, automated (was the manual step "install the signed example plugin in lib-ext/plugins"): the example
 * plugin of {@code examples/plugin-connector-example} is compiled from its sources against the platform API, packed as
 * {@code plugin-connector-example-1.0.0.jar}, and signed exactly as its README tells an operator to: a detached
 * Ed25519 signature over the jar's bytes in {@code <jar>.sig}, the public key in {@code trusted-keys/}. The key pair
 * is generated per run (never a committed secret), and nothing in the server's verification is relaxed: the server
 * finds a signed jar and a trusted key, as in production, and the profile is not a development one.
 */
final class SignedExamplePlugin {

    static final String EXAMPLE = "examples/plugin-connector-example";
    static final String JAR = "plugin-connector-example-1.0.0.jar";
    static final String KEY = "at6-run.pub";

    private static Path folder;

    private SignedExamplePlugin() {
    }

    /** The plugin folder for {@code sangam.plugins.folder}; built once per JVM. */
    static synchronized String folder() {
        if (folder == null) {
            try {
                folder = build(locate(Path.of(System.getProperty("user.dir")).toAbsolutePath()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AT-6: cannot sign the example plugin", e);
            }
        }
        return folder.toString();
    }

    /** The example's directory: {@code examples/plugin-connector-example} in this directory or a parent. */
    static Path locate(Path from) {
        for (Path p = from; p != null; p = p.getParent()) {
            Path candidate = p.resolve(EXAMPLE);
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("AT-6: " + EXAMPLE + " not found above " + from);
    }

    static Path build(Path example) throws IOException, GeneralSecurityException {
        Path work = Files.createTempDirectory("sangam-at6-build");
        Path classes = Files.createDirectories(work.resolve("classes"));
        compile(example.resolve("src/main/java"), classes);
        Path plugins = Files.createTempDirectory("sangam-at6-plugins");
        Path jar = plugins.resolve(JAR);
        try (OutputStream out = Files.newOutputStream(jar)) {
            Manifest mf = new Manifest();
            mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            try (JarOutputStream j = new JarOutputStream(out, mf)) {
                add(j, classes);
                add(j, example.resolve("src/main/resources"));
            }
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(pair.getPrivate());
        s.update(Files.readAllBytes(jar));
        Files.writeString(plugins.resolve(JAR + ".sig"), Base64.getEncoder().encodeToString(s.sign()),
                StandardCharsets.US_ASCII);
        Path trusted = Files.createDirectories(plugins.resolve("trusted-keys"));
        Files.writeString(trusted.resolve(KEY), "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n", StandardCharsets.US_ASCII);
        return plugins;
    }

    /** Compiles every source of the example against the platform API (the example declares it {@code provided}). */
    private static void compile(Path sources, Path out) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("AT-6 builds the example plugin and needs a JDK, not a JRE");
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(sources)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        String api;
        try {
            api = Path.of(SangamConnector.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        String cp = api + java.io.File.pathSeparator + System.getProperty("java.class.path");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of("-d", out.toString(), "-classpath", cp, "-proc:none",
                    "-encoding", "UTF-8"));
            boolean ok = javac.getTask(null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromPaths(files)).call();
            if (!ok) {
                throw new IllegalStateException("AT-6: the example plugin does not compile: " + diagnostics.getDiagnostics());
            }
        }
    }

    private static void add(JarOutputStream j, Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path f : files) {
            j.putNextEntry(new JarEntry(root.relativize(f).toString().replace('\\', '/')));
            j.write(Files.readAllBytes(f));
            j.closeEntry();
        }
    }
}
