// SPDX-License-Identifier: Apache-2.0
// Ported from spike/simulators org.sangam.spike.sim.pfms.FileTransport. Modified by Sangam.
package org.sangam.integration.connector.file;

import java.io.IOException;
import java.util.List;

/**
 * Minimal file-exchange contract (PFMS/IFMIS file interfaces, SFTP file drop, R58). {@link LocalDirectoryTransport}
 * uses local folders (shared mount or the air-gapped simulator); {@link SftpTransport} uses SFTP.
 *
 * <p>Directories are logical names ({@code outbound}, {@code inbound}, ...), not paths, mapped by the transport.
 * {@link #write} must be atomic: a reader never sees a half-written file.
 */
public interface FileTransport extends AutoCloseable {

    /** File names in {@code dir}, sorted by name. Empty if the directory does not exist. */
    List<String> list(String dir) throws IOException;

    byte[] read(String dir, String name) throws IOException;

    /** Atomically create or replace {@code dir/name}. */
    void write(String dir, String name, byte[] content) throws IOException;

    /** Move {@code fromDir/name} to {@code toDir/name}, replacing an existing target. */
    void move(String fromDir, String name, String toDir) throws IOException;

    boolean exists(String dir, String name) throws IOException;

    @Override
    default void close() throws IOException {
    }

    /** Rejects names that could escape the logical directory. */
    static String safeName(String name) {
        if (name == null || name.isEmpty() || name.contains("..") || name.contains("/") || name.contains("\\")
                || name.startsWith(".")) {
            throw new IllegalArgumentException("plain file name expected: " + name);
        }
        return name;
    }
}
