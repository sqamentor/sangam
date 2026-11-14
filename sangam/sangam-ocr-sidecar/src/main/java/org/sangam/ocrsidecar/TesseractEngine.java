// SPDX-License-Identifier: Apache-2.0
package org.sangam.ocrsidecar;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The Tesseract command-line program as the OCR engine (owner decision 167): one child process per image, no shell,
 * fixed arguments, one thread ({@code OMP_THREAD_LIMIT=1}), the image in a fresh private temporary folder that is
 * deleted afterwards. The caller's time limit ({@code SidecarServer.process}) interrupts the waiting thread; the process
 * is then killed. Output larger than the limit is an error, not a truncated text.
 */
final class TesseractEngine implements OcrEngine {

    private static final long PROBE_SECONDS = 30;

    private final String executable;
    private final Path tempRoot;
    private final long maxOutputBytes;
    private final String name;

    /**
     * Probes the program once ({@code --version}); a program that does not run stops the start-up.
     *
     * @param executable the program, an absolute path or a name on the PATH
     * @param tempRoot   where the per-request folders are made (a size-limited tmpfs in the image)
     */
    TesseractEngine(String executable, Path tempRoot, long maxOutputBytes) throws IOException, InterruptedException {
        if (executable == null || executable.isBlank() || maxOutputBytes < 1) {
            throw new IllegalArgumentException("an executable and a positive output limit are required");
        }
        this.executable = executable.strip();
        this.tempRoot = tempRoot;
        this.maxOutputBytes = maxOutputBytes;
        List<String> version = probe("--version");
        String first = version.isEmpty() ? "" : version.get(0).strip();
        if (!first.toLowerCase(java.util.Locale.ROOT).startsWith("tesseract")) {
            throw new IOException("the program " + this.executable + " did not report a Tesseract version");
        }
        this.name = first;
    }

    @Override
    public String name() {
        return name;
    }

    /** The language data installed for the program ({@code --list-langs}). */
    List<String> installedLanguages() throws IOException, InterruptedException {
        List<String> out = new ArrayList<>();
        for (String line : probe("--list-langs")) {
            String l = line.strip();
            if (!l.isEmpty() && !l.contains(" ") && !l.endsWith(":")) {
                out.add(l);
            }
        }
        return out;
    }

    @Override
    public String recognise(byte[] image, List<String> languages)
            throws Unreadable, IOException, InterruptedException {
        Path dir = Files.createTempDirectory(tempRoot, "ocr-");
        Process p = null;
        try {
            Path in = Files.write(dir.resolve("input"), image);
            Path out = dir.resolve("output.txt");
            ProcessBuilder pb = new ProcessBuilder(executable, in.toString(), "stdout", "-l", String.join("+", languages))
                    .directory(dir.toFile())
                    .redirectOutput(out.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.environment().put("OMP_THREAD_LIMIT", "1");
            p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new Unreadable("the engine could not read the image (exit " + exit + ")");
            }
            if (Files.size(out) > maxOutputBytes) {
                throw new IOException("the engine produced more than " + maxOutputBytes + " bytes of text");
            }
            return Files.readString(out, StandardCharsets.UTF_8);
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            delete(dir);
        }
    }

    private List<String> probe(String option) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(executable, option).redirectErrorStream(true).start();
        try {
            byte[] bytes;
            try (InputStream in = p.getInputStream()) {
                bytes = in.readNBytes(64 * 1024);
            }
            if (!p.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("the program " + executable + " " + option + " did not finish");
            }
            if (p.exitValue() != 0) {
                throw new IOException("the program " + executable + " " + option + " failed (exit " + p.exitValue() + ")");
            }
            return new String(bytes, StandardCharsets.UTF_8).lines().toList();
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path f : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            System.getLogger(TesseractEngine.class.getName()).log(System.Logger.Level.WARNING,
                    "a temporary OCR folder could not be deleted: " + e.getClass().getSimpleName());
        }
    }
}
