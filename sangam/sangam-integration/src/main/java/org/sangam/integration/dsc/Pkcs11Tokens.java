// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.Objects;

import javax.security.auth.callback.CallbackHandler;

/**
 * Owner decision 165: opens a hardware signing token through the JDK's own {@code SunPKCS11} provider (no vendor
 * library is bundled; the configuration names the token vendor's PKCS#11 module installed on the user's computer).
 * The PIN is asked by the {@link CallbackHandler} (a local dialog of the agent), handed to the token and never stored,
 * logged or sent anywhere.
 *
 * <p>The configuration file is the standard SunPKCS11 one, for example {@code name = Token} and
 * {@code library = <path of the vendor's PKCS#11 module>}.
 */
public final class Pkcs11Tokens {

    private Pkcs11Tokens() {
    }

    /** The token of {@code configFile}, with the PIN asked through {@code pinPrompt} when the key store is opened. */
    public static DscToken open(Path configFile, CallbackHandler pinPrompt) throws GeneralSecurityException {
        Objects.requireNonNull(pinPrompt, "pinPrompt");
        if (configFile == null || !Files.isRegularFile(configFile)) {
            throw new GeneralSecurityException("the PKCS#11 configuration file does not exist: " + configFile);
        }
        Provider base = Security.getProvider("SunPKCS11");
        if (base == null) {
            throw new GeneralSecurityException("this Java runtime has no SunPKCS11 provider");
        }
        Provider p = base.configure(configFile.toString());
        KeyStore store = KeyStore.Builder.newInstance("PKCS11", p, new KeyStore.CallbackHandlerProtection(pinPrompt))
                .getKeyStore();
        return DscToken.of(store, p);
    }
}
