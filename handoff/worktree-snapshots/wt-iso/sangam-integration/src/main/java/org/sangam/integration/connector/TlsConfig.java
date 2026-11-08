// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * TLS settings of a connector: optional client certificate (mTLS, required by most government gateways) and an
 * optional private trust store (State Data Centre CAs). Key material is referenced by path; passwords come from the
 * {@link SecretResolver}, never from model variables.
 *
 * @param keyStore           PKCS#12 (or JKS) with the client key + certificate chain; null = no client certificate
 * @param keyStorePassword   password of the key store
 * @param trustStore         trust store; null = the JDK default trust anchors
 * @param trustStorePassword password of the trust store
 * @param protocols          enabled protocols, default TLSv1.3 and TLSv1.2
 */
public record TlsConfig(Path keyStore, char[] keyStorePassword, Path trustStore, char[] trustStorePassword,
                        String[] protocols) {

    public static TlsConfig systemDefault() {
        return new TlsConfig(null, null, null, null, null);
    }

    public static TlsConfig mutual(Path keyStore, char[] keyStorePassword, Path trustStore, char[] trustStorePassword) {
        return new TlsConfig(keyStore, keyStorePassword, trustStore, trustStorePassword, null);
    }

    public boolean isMutual() {
        return keyStore != null;
    }

    public SSLContext sslContext() {
        try {
            KeyManagerFactory kmf = null;
            if (keyStore != null) {
                KeyStore ks = load(keyStore, keyStorePassword);
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, keyStorePassword);
            }
            TrustManagerFactory tmf = null;
            if (trustStore != null) {
                KeyStore ts = load(trustStore, trustStorePassword);
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("cannot build TLS context: " + e.getMessage(), e);
        }
    }

    public SSLParameters sslParameters() {
        SSLParameters p = new SSLParameters();
        p.setProtocols(protocols == null ? new String[]{"TLSv1.3", "TLSv1.2"} : protocols);
        p.setEndpointIdentificationAlgorithm("HTTPS");
        return p;
    }

    private static KeyStore load(Path path, char[] password) throws Exception {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        KeyStore ks = KeyStore.getInstance(name.endsWith(".jks") ? "JKS" : "PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, password);
        }
        return ks;
    }
}
