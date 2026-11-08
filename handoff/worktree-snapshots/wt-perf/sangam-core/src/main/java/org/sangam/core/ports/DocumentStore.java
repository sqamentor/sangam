// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.ports;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Binary object store SPI behind the content service (FLOWABLE-INTO-OPERATON FC-40, EC-18, R74). Implementations: file system and
 * S3-compatible (sangam-flowable-ports {@code org.sangam.ports.content}). Keys are opaque, tenant-prefixed and
 * immutable: a new document version is a new key, never an overwrite.
 */
public interface DocumentStore {

    /** Stored object facts returned by {@link #put}. */
    record StoredObject(String key, long sizeBytes, String sha256Hex) {
    }

    /** Short type name for configuration and logs ("fs", "s3"). */
    String type();

    /** Stores the stream under {@code key}; fails if the key exists. Computes size and SHA-256 while streaming. */
    StoredObject put(String key, InputStream content, String contentType) throws IOException;

    /** Opens the object, or empty when it does not exist. The caller closes the stream. */
    Optional<InputStream> get(String key) throws IOException;

    boolean exists(String key) throws IOException;

    /** Removes the object (used by erase/retention flows; normal deletes are logical). */
    void delete(String key) throws IOException;
}
