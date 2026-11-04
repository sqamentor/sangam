// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.content;

import org.sangam.core.ports.DocumentStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * File-system document store (FC-40). Objects are written to a temp file, fsync'd, then atomically moved into place,
 * so a crash never leaves a partial object under a live key. Keys are validated (no {@code ..}, no absolute paths).
 */
public class FileSystemDocumentStore implements DocumentStore {

    private final Path root;

    public FileSystemDocumentStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @Override
    public String type() {
        return "fs";
    }

    @Override
    public StoredObject put(String key, InputStream content, String contentType) throws IOException {
        Path target = resolve(key);
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException(key);
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        MessageDigest md = sha256();
        long size;
        try (OutputStream raw = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
             DigestOutputStream out = new DigestOutputStream(raw, md)) {
            size = content.transferTo(out);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return new StoredObject(key, size, HexFormat.of().formatHex(md.digest()));
    }

    @Override
    public Optional<InputStream> get(String key) throws IOException {
        Path p = resolve(key);
        return Files.exists(p) ? Optional.of(Files.newInputStream(p)) : Optional.empty();
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    Path resolve(String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.startsWith("/") || key.contains("\\") || key.contains(":")) {
            throw new IllegalArgumentException("invalid document key: " + key);
        }
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("invalid document key: " + key);
        }
        return p;
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
