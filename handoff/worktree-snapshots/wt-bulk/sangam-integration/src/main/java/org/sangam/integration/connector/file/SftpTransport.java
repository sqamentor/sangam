// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.file;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.sangam.integration.connector.EgressPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link FileTransport} over SFTP with Apache MINA SSHD (Apache-2.0), for PFMS/IFMIS file interfaces and generic file
 * drops (R58, R71 "SFTP/file").
 *
 * <ul>
 *   <li>Host key pinning: the server key's SHA-256 fingerprint must equal the configured one; there is no
 *       trust-on-first-use.</li>
 *   <li>Authentication by private key (OpenSSH/PEM, optional passphrase) or password; credentials are resolved by the
 *       caller from secret references.</li>
 *   <li>Atomic write: upload to a hidden {@code .name.part} then rename with overwrite, so the counterparty never reads
 *       a partial file.</li>
 *   <li>Logical directories ({@code outbound}, {@code inbound}, ...) map to remote paths through {@code dirs}.</li>
 *   <li>Egress (R27): the host is checked against the tenant's allow-list before connecting.</li>
 * </ul>
 * One session is opened lazily and reused; {@link #close()} ends it.
 */
public final class SftpTransport implements FileTransport {

    public record Config(String tenantId, URI server, String username, Path privateKey, char[] passphrase, char[] password,
                         String hostKeySha256, Map<String, String> dirs, Duration timeout) {
        public Config {
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(hostKeySha256, "hostKeySha256 (pinned server key fingerprint) is required");
            dirs = dirs == null ? Map.of() : Map.copyOf(dirs);
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    private final Config config;
    private final EgressPolicy egress;
    private SshClient client;
    private ClientSession session;
    private SftpClient sftp;

    public SftpTransport(Config config, EgressPolicy egress) {
        this.config = config;
        this.egress = egress;
    }

    private synchronized SftpClient sftp() throws IOException {
        if (sftp != null && sftp.isOpen() && session != null && session.isOpen()) {
            return sftp;
        }
        closeQuietly();
        try {
            egress.check(config.tenantId(), config.server());
        } catch (EgressPolicy.EgressDeniedException e) {
            throw new IOException(e.getMessage(), e);
        }
        client = SshClient.setUpDefaultClient();
        String expected = config.hostKeySha256().startsWith("SHA256:") ? config.hostKeySha256() : "SHA256:" + config.hostKeySha256();
        client.setServerKeyVerifier((clientSession, remote, serverKey) -> expected.equals(KeyUtils.getFingerPrint(serverKey)));
        client.start();
        int port = config.server().getPort() == -1 ? 22 : config.server().getPort();
        long ms = config.timeout().toMillis();
        session = client.connect(config.username(), config.server().getHost(), port).verify(ms).getSession();
        if (config.privateKey() != null) {
            try (InputStream in = Files.newInputStream(config.privateKey())) {
                Iterable<KeyPair> keys = SecurityUtils.loadKeyPairIdentities(session, NamedResource.ofName(config.privateKey().toString()),
                        in, config.passphrase() == null ? FilePasswordProvider.EMPTY : FilePasswordProvider.of(new String(config.passphrase())));
                for (KeyPair kp : keys) {
                    session.addPublicKeyIdentity(kp);
                }
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException("cannot load SFTP private key: " + e.getMessage(), e);
            }
        }
        if (config.password() != null) {
            session.addPasswordIdentity(new String(config.password()));
        }
        session.auth().verify(ms);
        sftp = SftpClientFactory.instance().createSftpClient(session);
        return sftp;
    }

    private String dir(String logical) {
        if (logical.contains("..") || logical.contains("/") || logical.contains("\\")) {
            throw new IllegalArgumentException("logical directory name expected: " + logical);
        }
        String d = config.dirs().getOrDefault(logical, logical);
        return d.endsWith("/") ? d.substring(0, d.length() - 1) : d;
    }

    private String file(String dir, String name) {
        return dir(dir) + "/" + FileTransport.safeName(name);
    }

    @Override
    public List<String> list(String dir) throws IOException {
        List<String> out = new ArrayList<>();
        try {
            for (SftpClient.DirEntry e : sftp().readDir(dir(dir))) {
                String n = e.getFilename();
                if (!n.startsWith(".") && e.getAttributes().isRegularFile()) {
                    out.add(n);
                }
            }
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                return List.of();
            }
            throw e;
        }
        Collections.sort(out);
        return out;
    }

    @Override
    public byte[] read(String dir, String name) throws IOException {
        try (InputStream in = sftp().read(file(dir, name))) {
            return in.readAllBytes();
        }
    }

    @Override
    public void write(String dir, String name, byte[] content) throws IOException {
        SftpClient c = sftp();
        String target = file(dir, name);
        String tmp = dir(dir) + "/." + FileTransport.safeName(name) + ".part";
        try (OutputStream out = c.write(tmp)) {
            out.write(content);
        }
        c.rename(tmp, target, SftpClient.CopyMode.Overwrite);
    }

    @Override
    public void move(String fromDir, String name, String toDir) throws IOException {
        sftp().rename(file(fromDir, name), file(toDir, name), SftpClient.CopyMode.Overwrite);
    }

    @Override
    public boolean exists(String dir, String name) throws IOException {
        try {
            return sftp().stat(file(dir, name)).isRegularFile();
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (sftp != null) {
                sftp.close();
            }
        } catch (IOException ignored) {
            // closing
        }
        try {
            if (session != null) {
                session.close();
            }
        } catch (IOException ignored) {
            // closing
        }
        if (client != null) {
            client.stop();
        }
        sftp = null;
        session = null;
        client = null;
    }
}
