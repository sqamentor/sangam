// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.file;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Hashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic file drop over SFTP or a shared directory (R71 "SFTP/file", R58 file-based government interfaces).
 *
 * <p>Input: {@code content} (text) or {@code contentBase64}, optional {@code fileName} (default
 * {@code <prefix><operationId><suffix>}), {@code dir} (logical directory, default {@code outbound}). The file name is
 * deterministic per operation, so a repeat writes the same file: if a file with that name already exists in the
 * outbound or one of the {@code consumedDirs} with identical content, the call is a duplicate success; with different
 * content it is a business failure ({@code FILE_CONFLICT}). This makes the connector capability class (a), with
 * {@link #lookup} checking presence.
 */
public final class FileDropConnector implements SangamConnector {

    public record Config(String id, String prefix, String suffix, String defaultDir, List<String> consumedDirs,
                         ConnectorProfile profile) {
        public Config {
            prefix = prefix == null ? "" : prefix;
            suffix = suffix == null ? ".dat" : suffix;
            defaultDir = defaultDir == null ? "outbound" : defaultDir;
            consumedDirs = consumedDirs == null ? List.of() : List.copyOf(consumedDirs);
        }
    }

    private final Config config;
    private final FileTransport transport;
    private final ConnectorDescriptor descriptor;

    public FileDropConnector(Config config, FileTransport transport) {
        this.config = config;
        this.transport = transport;
        this.descriptor = new ConnectorDescriptor(config.id(), "sftp", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("drop"), config.profile());
    }

    /** Simulator profile: a temporary local directory stands in for the SFTP server. */
    public static FileDropConnector simulator(String id) {
        try {
            return new FileDropConnector(new Config(id, "DROP_", ".dat", "outbound", List.of("processed"), ConnectorProfile.SIMULATOR),
                    new LocalDirectoryTransport(Files.createTempDirectory("sangam-filedrop-")));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public FileTransport transport() {
        return transport;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    private String name(ConnectorRequest r) {
        return FileTransport.safeName(r.str("fileName", config.prefix() + r.operationId() + config.suffix()));
    }

    private static byte[] content(ConnectorRequest r) {
        if (r.str("contentBase64") != null) {
            return Base64.getDecoder().decode(r.str("contentBase64"));
        }
        String c = r.str("content");
        if (c == null) {
            throw new IllegalArgumentException("content or contentBase64 is required");
        }
        return c.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        String name;
        byte[] data;
        try {
            name = name(r);
            data = content(r);
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
        }
        String dir = r.str("dir", config.defaultDir());
        try {
            for (String d : dirs(dir)) {
                if (transport.exists(d, name)) {
                    byte[] existing = transport.read(d, name);
                    if (Arrays.equals(existing, data)) {
                        return new ConnectorResult.Success(output(name, d, data), name, true);
                    }
                    return ConnectorResult.BusinessFailure.of("FILE_CONFLICT", d + "/" + name + " exists with different content");
                }
            }
        } catch (IOException e) {
            return ConnectorResult.TransientFailure.noEffect("file transport unavailable: " + e.getMessage());
        }
        try {
            transport.write(dir, name, data);
            return ConnectorResult.Success.of(output(name, dir, data), name);
        } catch (IOException e) {
            // The rename may or may not have happened; the deterministic name makes a retry safe (class a).
            return new ConnectorResult.TransientFailure("write failed: " + e.getMessage(), false, null);
        }
    }

    @Override
    public LookupResult lookup(ConnectorRequest r) {
        try {
            String name = name(r);
            for (String d : dirs(r.str("dir", config.defaultDir()))) {
                if (transport.exists(d, name)) {
                    return LookupResult.Found.success(Map.of("fileName", name, "dir", d), name);
                }
            }
            return new LookupResult.NotFound();
        } catch (IOException e) {
            return new LookupResult.Unavailable(e.getMessage());
        } catch (IllegalArgumentException e) {
            return new LookupResult.NotSupported();
        }
    }

    private List<String> dirs(String dir) {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        l.add(dir);
        l.addAll(config.consumedDirs());
        return l;
    }

    private static Map<String, Object> output(String name, String dir, byte[] data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileName", name);
        out.put("dir", dir);
        out.put("sha256", Hashes.hex(Hashes.sha256(data)));
        out.put("bytes", data.length);
        return out;
    }
}
