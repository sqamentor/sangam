// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * SL-160 guard (evidence pinned): (1) every BPMN / CMMN model the reactor ships (main resources of every module, the
 * compliance packs, the plugin example: 208 files on 2026-11-12) and the frozen IFMS E46 models pass the allow-list,
 * parts A and B (classes, delegate beans, expressions, scripts), with the defaults alone, so nothing that ships breaks;
 * (2) {@link ModelCodeAllowList#SANGAM_CLASSES} is exactly the set of {@code org.sangam}
 * main-source classes that implement a delegate / listener / activity-behaviour interface, so a new Sangam delegate is
 * reviewed onto the list and a removed one leaves it. Run from sangam-engine; the reactor root is the parent folder.
 */
class ModelCodeAllowListGuardTest {

    static final Pattern DELEGATE = Pattern.compile(
            "(?:class)\\s+(\\w+)[^{]*\\bimplements\\s+[^{]*\\b(?:JavaDelegate|ExecutionListener|TaskListener|ActivityBehavior"
                    + "|SignallableActivityBehavior|CaseExecutionListener)\\b");
    static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    static Path root() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    static List<Path> files(Path root, java.util.function.Predicate<String> keep) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return dir.equals(root) || !(name.startsWith("target") || name.equals("node_modules") || name.equals(".git"))
                        ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String r = root.relativize(file).toString().replace('\\', '/');
                if (attrs.isRegularFile() && keep.test(r)) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    @Test
    void everyShippedModelPassesWithTheDefaults() throws IOException {
        List<Path> models = files(root(), r -> r.contains("/src/main/") && ModelCodeAllowList.isModel(r));
        assertTrue(models.size() >= 200, "found the shipped models: " + models.size());
        List<String> refused = new ArrayList<>();
        for (Path p : models) {
            for (ModelCodeAllowList.Finding f : ModelCodeAllowList.check(p.toString(), Files.readAllBytes(p),
                    ModelCodeAllowList.SANGAM_CLASSES, ModelCodeAllowList.SANGAM_BEANS)) {
                refused.add(f.toString());
            }
        }
        assertEquals(List.of(), refused, "shipped models must deploy unchanged");
    }

    /**
     * SL-160 part B proof: the frozen IFMS E46 consumer models (evidence oneifms-sangam, used by E46ConformanceIT and
     * Sg35BillBandRegressionH2Test) pass too, including their {@code language="juel"} script conditions.
     */
    @Test
    void theFrozenIfmsE46ModelsPassWithTheDefaults() throws IOException {
        Path dir = root().resolve("sangam-server/src/test/resources/acceptance/consumer/ifms/e46");
        List<Path> models = files(dir, r -> r.endsWith(".bpmn.model"));
        assertTrue(models.size() >= 8, "found the frozen E46 models: " + models.size());
        List<String> refused = new ArrayList<>();
        for (Path p : models) {
            for (ModelCodeAllowList.Finding f : ModelCodeAllowList.check(p.toString(), Files.readAllBytes(p),
                    ModelCodeAllowList.SANGAM_CLASSES, ModelCodeAllowList.SANGAM_BEANS)) {
                refused.add(f.toString());
            }
        }
        assertEquals(List.of(), refused, "the frozen E46 models must deploy unchanged");
    }

    @Test
    void theOwnClassListIsExactlyTheReviewedSangamDelegates() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path p : files(root(), r -> r.contains("/src/main/java/") && r.endsWith(".java") && !r.startsWith("examples/"))) {
            String text = Files.readString(p);
            Matcher pkg = PACKAGE.matcher(text);
            if (!pkg.find() || !pkg.group(1).startsWith("org.sangam")) {
                continue;
            }
            String outer = p.getFileName().toString().replace(".java", "");
            Matcher m = DELEGATE.matcher(text);
            while (m.find()) {
                String simple = m.group(1);
                found.add(pkg.group(1) + "." + (simple.equals(outer) ? outer : outer + "$" + simple));
            }
        }
        assertEquals(new TreeSet<>(ModelCodeAllowList.SANGAM_CLASSES), found,
                "review a new Sangam delegate onto ModelCodeAllowList.SANGAM_CLASSES (or remove a deleted one)");
    }
}
