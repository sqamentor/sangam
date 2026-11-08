// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.evidence;

import org.sangam.field.FieldException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Evidence blob storage port. Content-addressed: the key is tenant + SHA-256, so a re-upload is idempotent and the
 * stored bytes can always be re-hashed against the evidence row. The integrator may bind an object store; the
 * default writes to a local directory (air-gapped).
 */
public interface EvidenceBlobStore {

    /** Stores the bytes and returns a storage reference. Must be idempotent for the same (tenant, sha256). */
    String put(String tenantId, String sha256, String mediaType, byte[] bytes);

    Optional<byte[]> get(String tenantId, String storageRef);

    /** Local-directory store: {@code <root>/<tenant>/<sha[0..2]>/<sha>}; write-to-temp then atomic rename. */
    final class FileSystem implements EvidenceBlobStore {
        private final Path root;

        public FileSystem(Path root) {
            this.root = root;
        }

        @Override
        public String put(String tenantId, String sha256, String mediaType, byte[] bytes) {
            if (!sha256.matches("[0-9a-f]{64}") || tenantId.contains("/") || tenantId.contains("\\") || tenantId.contains("..")) {
                throw new FieldException("INVALID_REQUEST", "invalid evidence key");
            }
            String ref = tenantId + "/" + sha256.substring(0, 2) + "/" + sha256;
            Path target = root.resolve(ref);
            try {
                if (Files.exists(target)) {
                    return ref;
                }
                Files.createDirectories(target.getParent());
                Path tmp = Files.createTempFile(target.getParent(), sha256, ".part");
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return ref;
            } catch (IOException e) {
                throw new FieldException("STORAGE_FAILED", "cannot store evidence: " + e.getMessage(), e);
            }
        }

        @Override
        public Optional<byte[]> get(String tenantId, String storageRef) {
            if (!storageRef.startsWith(tenantId + "/") || storageRef.contains("..")) {
                return Optional.empty();
            }
            Path p = root.resolve(storageRef);
            try {
                return Files.exists(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
            } catch (IOException e) {
                throw new FieldException("STORAGE_FAILED", "cannot read evidence: " + e.getMessage(), e);
            }
        }
    }
}
