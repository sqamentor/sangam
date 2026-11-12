// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.sangam.integration.connector.SecretResolver;

/**
 * Secrets from a mounted directory (M-075): Kubernetes Secret volumes and Docker/Compose secrets. The reference
 * {@code secret://a/b} is the file {@code <root>/a/b}; Kubernetes mounts one Secret per directory with one file per key,
 * so {@code <root>/<secret name>/<key>}. No network call.
 *
 * <ul>
 *   <li>The file must lie inside the root after every symbolic link is followed (Kubernetes uses {@code ..data}
 *       links inside the mount, which stay inside); a link out of the root is refused.</li>
 *   <li>The content is returned exactly as stored, UTF-8, at most {@value #MAX_BYTES} bytes. It is never trimmed: a
 *       trailing line break is refused where the secret is used (SL-65), so a wrongly written file is found, not
 *       hidden.</li>
 *   <li>A missing file is "not found" (the next resolver of the chain is asked); an unreadable or oversized file is an
 *       error.</li>
 * </ul>
 */
public final class MountedFileSecretResolver implements SecretResolver {

    public static final int MAX_BYTES = 64 * 1024;

    private final Path root;

    public MountedFileSecretResolver(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    @Override
    public Optional<String> resolve(String reference) {
        Optional<SecretRef> ref = SecretRef.parse(reference);
        if (ref.isEmpty() || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        Path file = root;
        for (String s : ref.get().segments()) {
            file = file.resolve(s);
        }
        file = file.normalize();
        if (!file.startsWith(root)) {
            return Optional.empty();
        }
        try {
            Path real = file.toRealPath();
            if (!real.startsWith(root.toRealPath()) || !Files.isRegularFile(real)) {
                return Optional.empty();
            }
            try (InputStream in = Files.newInputStream(real)) {
                byte[] b = in.readNBytes(MAX_BYTES + 1);
                if (b.length > MAX_BYTES) {
                    throw new IllegalStateException("secret file for " + reference + " is larger than " + MAX_BYTES
                            + " bytes");
                }
                return Optional.of(new String(b, StandardCharsets.UTF_8));
            }
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("secret file for " + reference + " cannot be read: "
                    + e.getClass().getSimpleName(), e);
        }
    }
}
