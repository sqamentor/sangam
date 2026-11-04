package org.sangam.spike.sim.pfms;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/** {@link FileTransport} over local directories under one root (stand-in for an SFTP server). */
public final class LocalDirectoryTransport implements FileTransport {

    private final Path root;

    public LocalDirectoryTransport(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path path(String dir) {
        if (dir.contains("..") || dir.contains("/") || dir.contains("\\")) {
            throw new IllegalArgumentException("logical directory name expected: " + dir);
        }
        return root.resolve(dir);
    }

    private Path file(String dir, String name) {
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("plain file name expected: " + name);
        }
        return path(dir).resolve(name);
    }

    @Override
    public List<String> list(String dir) throws IOException {
        Path d = path(dir);
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith(".")).sorted().toList();
        }
    }

    @Override
    public byte[] read(String dir, String name) throws IOException {
        return Files.readAllBytes(file(dir, name));
    }

    @Override
    public void write(String dir, String name, byte[] content) throws IOException {
        Path target = file(dir, name);
        Files.createDirectories(target.getParent());
        // Hidden temp name: list() skips dot-files, so readers never pick up a partial file.
        Path tmp = target.resolveSibling("." + name + ".part");
        Files.write(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void move(String fromDir, String name, String toDir) throws IOException {
        Path target = file(toDir, name);
        Files.createDirectories(target.getParent());
        Files.move(file(fromDir, name), target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public boolean exists(String dir, String name) {
        return Files.isRegularFile(file(dir, name));
    }
}
