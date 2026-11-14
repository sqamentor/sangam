// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Owner decision 165: the signing keys the loopback agent offers. The private key stays in the token: callers get
 * certificates and signatures, never key material.
 */
public interface DscToken {

    /** One signing key: its certificate chain (signer first) and the SHA-256 of the signer certificate. */
    record Entry(String alias, List<X509Certificate> chain, String certificateSha256) {
        public Entry {
            chain = List.copyOf(chain);
        }

        public X509Certificate signer() {
            return chain.get(0);
        }
    }

    /** The keys usable for signing (an entry with a private key and an X.509 certificate). */
    List<Entry> entries() throws GeneralSecurityException;

    /** Signs {@code data} with the key of the certificate {@code certificateSha256}, using JCA {@code algorithm}. */
    byte[] sign(String certificateSha256, String algorithm, byte[] data) throws GeneralSecurityException;

    /**
     * A token backed by a {@link KeyStore}: the JDK's {@code SunPKCS11} key store for a hardware token
     * ({@link Pkcs11Tokens}), or any other store (a software store in tests).
     *
     * @param signatureProvider the provider that must perform the signature (the PKCS#11 provider, so the operation runs
     *                          on the token); null = the key store's own provider
     */
    static DscToken of(KeyStore store, Provider signatureProvider) {
        Objects.requireNonNull(store, "store");
        return new DscToken() {
            @Override
            public List<Entry> entries() throws GeneralSecurityException {
                List<Entry> out = new ArrayList<>();
                for (String alias : Collections.list(store.aliases())) {
                    if (!store.isKeyEntry(alias)) {
                        continue;
                    }
                    Certificate[] chain = store.getCertificateChain(alias);
                    if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
                        continue;
                    }
                    List<X509Certificate> certs = new ArrayList<>();
                    for (Certificate c : chain) {
                        if (c instanceof X509Certificate x) {
                            certs.add(x);
                        }
                    }
                    out.add(new Entry(alias, certs, sha256(certs.get(0))));
                }
                return out;
            }

            @Override
            public byte[] sign(String certificateSha256, String algorithm, byte[] data) throws GeneralSecurityException {
                for (Entry e : entries()) {
                    if (e.certificateSha256().equals(certificateSha256)) {
                        // the key store was opened with its protection (a PIN prompt for a token); no password here
                        if (!(store.getKey(e.alias(), null) instanceof PrivateKey key)) {
                            throw new GeneralSecurityException("no private key for the chosen certificate");
                        }
                        Signature s = signatureProvider == null ? Signature.getInstance(algorithm)
                                : Signature.getInstance(algorithm, signatureProvider);
                        s.initSign(key);
                        s.update(data);
                        return s.sign();
                    }
                }
                throw new GeneralSecurityException("the chosen certificate is not on the token");
            }
        };
    }

    static String sha256(X509Certificate c) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
    }
}
