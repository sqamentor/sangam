package org.sangam.spike.sim.pfms;

import java.io.IOException;
import java.util.List;

/**
 * Minimal file-exchange contract between the product and PFMS. {@link LocalDirectoryTransport}
 * uses local folders; a real SFTP client (e.g. Apache MINA SSHD) can implement the same interface
 * later without touching the simulator or the connector.
 *
 * <p>Directories are logical names ({@code outbound}, {@code inbound}, ...), not paths.
 * {@link #write} must be atomic: a reader never sees a half-written file.
 */
public interface FileTransport {

    /** File names in {@code dir}, sorted by name. Empty if the directory does not exist. */
    List<String> list(String dir) throws IOException;

    byte[] read(String dir, String name) throws IOException;

    /** Atomically create or replace {@code dir/name}. */
    void write(String dir, String name, byte[] content) throws IOException;

    /** Move {@code fromDir/name} to {@code toDir/name}, replacing an existing target. */
    void move(String fromDir, String name, String toDir) throws IOException;

    boolean exists(String dir, String name) throws IOException;
}
