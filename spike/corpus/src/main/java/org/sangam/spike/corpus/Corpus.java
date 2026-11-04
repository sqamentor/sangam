package org.sangam.spike.corpus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the spike corpus models (SANGAM.md XI.3) from the classpath.
 *
 * <p>Models live under {@code models/}. A model is either shared ({@code <name>.<ext>}) or has one
 * variant per engine ({@code <name>.operaton.<ext>} and {@code <name>.flowable.<ext>}); every variant
 * is recorded in {@code corpus/VARIANTS.md}. {@link #bpmn(String, String)} returns the engine's variant
 * when one exists, otherwise the shared file.
 *
 * <p>The engine argument is {@code "flowable"} or {@code "operaton"} (case-insensitive); an engine id
 * such as {@code "flowable-8.0.0"} is also accepted (the part before the first '-' is used).
 */
public final class Corpus {

    /** Classpath folder of the models. */
    public static final String BASE = "models/";

    public static final String FLOWABLE = "flowable";
    public static final String OPERATON = "operaton";
    public static final List<String> ENGINES = List.of(FLOWABLE, OPERATON);

    /** Logical BPMN models (process id == name). */
    public static final List<String> BPMN_MODELS = List.of(
            "wf1-bill",
            "wf2-file",
            "wf3-licence",
            "gap-link-events",
            "gap-signal-end",
            "gap-message-end",
            "gap-message-throw",
            "gap-conditional-start",
            "gap-compensation-event-subprocess",
            "gap-adhoc-subprocess",
            "gap-io-mapping");

    /** Logical DMN models (decision id == name). */
    public static final List<String> DMN_MODELS = List.of("wf1-delegation-of-powers");

    /** Logical CMMN models (case id == name). */
    public static final List<String> CMMN_MODELS = List.of("wf3-licence-case");

    private Corpus() {
    }

    public static byte[] bpmn(String name, String engine) {
        return load(name, "bpmn", engine);
    }

    public static byte[] dmn(String name, String engine) {
        return load(name, "dmn", engine);
    }

    public static byte[] cmmn(String name, String engine) {
        return load(name, "cmmn", engine);
    }

    /** Returns the bytes of the variant for {@code engine} if it exists, else of the shared file. */
    public static byte[] load(String name, String extension, String engine) {
        String path = resolve(name, extension, engine);
        try (InputStream in = classLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Resolved corpus resource vanished: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read corpus resource " + path, e);
        }
    }

    /**
     * Returns the classpath path that {@link #load} would read, e.g. {@code models/wf1-bill.flowable.bpmn}
     * or {@code models/wf2-file.bpmn}.
     *
     * @throws IllegalArgumentException if neither a variant nor a shared file exists, or the engine is unknown
     */
    public static String resolve(String name, String extension, String engine) {
        String e = normalizeEngine(engine);
        String variant = BASE + name + "." + e + "." + extension;
        if (exists(variant)) {
            return variant;
        }
        String shared = BASE + name + "." + extension;
        if (exists(shared)) {
            return shared;
        }
        throw new IllegalArgumentException("No corpus model '" + name + "." + extension + "' (looked for "
                + variant + " and " + shared + ")");
    }

    /** True if {@code name} has an engine-specific variant for {@code engine}. */
    public static boolean hasVariant(String name, String extension, String engine) {
        return exists(BASE + name + "." + normalizeEngine(engine) + "." + extension);
    }

    /**
     * Resource name to deploy under, identical on both engines whichever file was loaded
     * (e.g. {@code wf1-bill.bpmn}), so deployments and reports compare one-to-one.
     */
    public static String deploymentResourceName(String name, String extension) {
        return name + "." + extension;
    }

    /**
     * Builds the resources map for {@code EngineAdapter.deploy}, keyed by deployment resource name.
     *
     * @param files logical file names such as {@code "wf1-bill.bpmn"} and {@code "wf1-delegation-of-powers.dmn"}
     */
    public static Map<String, byte[]> resources(String engine, String... files) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String file : files) {
            int dot = file.lastIndexOf('.');
            if (dot <= 0 || dot == file.length() - 1) {
                throw new IllegalArgumentException("Expected <name>.<bpmn|dmn|cmmn>, got: " + file);
            }
            String name = file.substring(0, dot);
            String extension = file.substring(dot + 1);
            result.put(deploymentResourceName(name, extension), load(name, extension, engine));
        }
        return result;
    }

    /** Maps "Flowable", "flowable-8.0.0", "OPERATON" etc. to {@link #FLOWABLE} or {@link #OPERATON}. */
    public static String normalizeEngine(String engine) {
        if (engine == null || engine.isBlank()) {
            throw new IllegalArgumentException("engine must be 'flowable' or 'operaton'");
        }
        String e = engine.trim().toLowerCase(Locale.ROOT);
        int dash = e.indexOf('-');
        if (dash > 0) {
            e = e.substring(0, dash);
        }
        if (!ENGINES.contains(e)) {
            throw new IllegalArgumentException("Unknown engine '" + engine + "'; expected 'flowable' or 'operaton'");
        }
        return e;
    }

    private static boolean exists(String path) {
        return classLoader().getResource(path) != null;
    }

    private static ClassLoader classLoader() {
        ClassLoader cl = Corpus.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }
}
