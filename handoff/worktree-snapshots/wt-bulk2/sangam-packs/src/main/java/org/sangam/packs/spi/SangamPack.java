// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * A deployable workflow pack. Implementations are found two ways by {@link PackRegistry}:
 * <ul>
 *   <li>a JSON manifest listed in {@code META-INF/sangam/packs.idx} (every pack of this module, read by
 *       {@link ManifestPack});</li>
 *   <li>{@link java.util.ServiceLoader} entries for {@code org.sangam.packs.spi.SangamPack} (department-written packs
 *       in their own jars).</li>
 * </ul>
 */
public interface SangamPack {

    PackDescriptor descriptor();

    /** Opens a pack resource by its path in the descriptor. */
    default InputStream open(String path) {
        ClassLoader cl = getClass().getClassLoader();
        InputStream in = cl.getResourceAsStream(path);
        if (in == null) {
            throw new UncheckedIOException(new java.io.FileNotFoundException(
                    "pack " + descriptor().id() + ": resource not found: " + path));
        }
        return in;
    }
}
