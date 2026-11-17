// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.miwg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Locations of the MIWG reference models, the expected outcomes and the evidence report (module base directory). */
final class MiwgModels {

    private MiwgModels() {
    }

    static Path dir() {
        return Path.of("src", "test", "resources", "miwg");
    }

    static Path reference() {
        return dir().resolve("reference");
    }

    static Path expectedFile() {
        return dir().resolve("expected.tsv");
    }

    static Path evidence() {
        return Path.of("..", "deploy", "evidence");
    }

    /** Model names (file name without .bpmn), in MIWG order: class, then major, then minor number. */
    static List<String> names() {
        try (Stream<Path> s = Files.list(reference())) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".bpmn"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted(MiwgModels::compare).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int c = x[0].compareTo(y[0]);
        for (int i = 1; c == 0 && i < Math.min(x.length, y.length); i++) {
            c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
        }
        return c;
    }

    static byte[] bytes(String model) {
        try {
            return Files.readAllBytes(reference().resolve(model + ".bpmn"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** model -> expected row (the columns after the model name, tab-joined); comment lines start with #. */
    static Map<String, String> expected() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(expectedFile())) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(expectedFile(), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                out.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
