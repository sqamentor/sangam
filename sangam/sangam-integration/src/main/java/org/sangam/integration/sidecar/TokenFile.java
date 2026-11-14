// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.nio.file.Path;
import java.time.Duration;

/**
 * A token file kept current for rotation (owner decision 153): it is read again when its modification time or size
 * changes, and at least every {@link #MAX_AGE} in any case, so a rotated or revoked token takes effect without a
 * restart. The previous content is never kept as a fallback: an unreadable or invalid file makes the holder not ready.
 */
final class TokenFile {

    static final Duration MAX_AGE = Duration.ofSeconds(10);

    private final Path file;
    private SidecarTokens.Snapshot snapshot;
    private long readAtNanos;

    TokenFile(Path file) {
        this.file = file;
    }

    Path path() {
        return file;
    }

    synchronized SidecarTokens.Snapshot current() {
        long now = System.nanoTime();
        if (snapshot == null || now - readAtNanos > MAX_AGE.toNanos() || SidecarTokens.changed(file, snapshot)) {
            snapshot = SidecarTokens.read(file);
            readAtNanos = now;
        }
        return snapshot;
    }
}
