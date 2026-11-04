// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import org.sangam.compliance.ports.CompliancePorts;

/**
 * {@link CompliancePorts.LogArchiveStore} on a filesystem in the home country ({@code sangam.compliance.log-archive.root},
 * {@code .country}, default IN): sealed log segments are written once (atomic move) under
 * {@code <root>/<tenant>/<region>/<segmentId>.seg}. Mount a WORM / object-store volume located in the declared country
 * in production; the declared country is what {@code LogRetentionPolicy.requireResidency} checks.
 */
public final class ComplianceLogArchiveStore implements CompliancePorts.LogArchiveStore {

    private final Path root;
    private final String country;

    public ComplianceLogArchiveStore(Path root, String country) {
        this.root = root.toAbsolutePath().normalize();
        this.country = country.toUpperCase(Locale.ROOT);
    }

    @Override
    public URI store(String tenantId, String segmentId, byte[] content, String region) {
        Path dir = root.resolve(safe(tenantId)).resolve(safe(region == null ? country : region));
        Path target = dir.resolve(safe(segmentId) + ".seg");
        try {
            Files.createDirectories(dir);
            if (Files.exists(target)) {
                throw new IllegalStateException("log segment " + segmentId + " already archived (write-once)");
            }
            Path tmp = Files.createTempFile(dir, ".seg-", ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return target.toUri();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot archive log segment " + segmentId, e);
        }
    }

    @Override
    public byte[] read(URI uri) {
        try {
            return Files.readAllBytes(inside(uri));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read archived segment " + uri, e);
        }
    }

    @Override
    public void delete(URI uri) {
        try {
            Files.deleteIfExists(inside(uri));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete archived segment " + uri, e);
        }
    }

    @Override
    public String country() {
        return country;
    }

    private Path inside(URI uri) {
        Path p = Path.of(uri).toAbsolutePath().normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("segment outside the archive root: " + uri);
        }
        return p;
    }

    private static String safe(String s) {
        String v = s == null ? "_" : s.replaceAll("[^A-Za-z0-9._-]", "_");
        return v.isEmpty() || v.startsWith(".") ? "_" + v : v;
    }
}
