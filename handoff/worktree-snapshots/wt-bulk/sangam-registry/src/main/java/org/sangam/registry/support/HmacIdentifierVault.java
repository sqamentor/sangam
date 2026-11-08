// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.support;

import org.sangam.core.layer.Hashes;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryPorts;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link RegistryPorts.IdentifierVault}: HMAC-SHA256 keyed hashes with a pepper. The hash input is
 * {@code tenant | idType | value}, so equal identifiers of different types or tenants never collide.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Configured</b> ({@link #HmacIdentifierVault(byte[])}, {@link #fromBase64}): a stable pepper from
 *       configuration, so hashes match across restarts. It holds no clear values: asking it to vault a clear value
 *       fails, because an in-memory token would be lost on restart. Bind a real vault service when sensitive
 *       identifiers must be revealable;</li>
 *   <li><b>Ephemeral</b> ({@link #ephemeral()}): a random pepper and an in-memory token map. Hashes and tokens do not
 *       survive a restart, so this is development only and the module refuses it on a non-development profile.</li>
 * </ul>
 */
public final class HmacIdentifierVault implements RegistryPorts.IdentifierVault, RegistryPorts.DevelopmentOnly {

    private final byte[] pepper;
    private final boolean ephemeral;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    /** A configured vault (stable pepper of at least 16 bytes; no clear-value storage). */
    public HmacIdentifierVault(byte[] pepper) {
        this(pepper, false);
    }

    private HmacIdentifierVault(byte[] pepper, boolean ephemeral) {
        if (pepper == null || pepper.length < 16) {
            throw new IllegalArgumentException("pepper must be at least 16 bytes");
        }
        this.pepper = pepper.clone();
        this.ephemeral = ephemeral;
    }

    /** A configured vault from a base64 pepper (e.g. a secret from the environment). */
    public static HmacIdentifierVault fromBase64(String pepperB64) {
        return new HmacIdentifierVault(Base64.getDecoder().decode(pepperB64.trim()));
    }

    /** A vault with a random pepper and in-memory tokens (development only). */
    public static HmacIdentifierVault ephemeral() {
        byte[] p = new byte[32];
        new SecureRandom().nextBytes(p);
        return new HmacIdentifierVault(p, true);
    }

    @Override
    public RegistryPorts.ProtectedValue protect(String tenant, String idType, String value, boolean storeInVault) {
        String hash = hmac(tenant + "|" + idType + "|" + value);
        String ref = null;
        if (storeInVault) {
            if (!ephemeral) {
                throw RegistryException.state("no vault service is configured to hold clear identifier values (type "
                        + idType + "); bind RegistryPorts.IdentifierVault to the vault service");
            }
            ref = "vault:" + hash.substring(0, 32);
            tokens.put(tenant + "|" + ref, value);
        }
        return new RegistryPorts.ProtectedValue(hash, ref, mask(value));
    }

    @Override
    public Optional<String> reveal(String tenant, String vaultRef, String purpose) {
        return Optional.ofNullable(tokens.get(tenant + "|" + vaultRef));
    }

    @Override
    public String developmentOnlyReason() {
        return ephemeral ? "identifier vault: random HMAC pepper and in-memory tokens (hashes and vault tokens are lost"
                + " on restart); configure HmacIdentifierVault.fromBase64(pepper) and bind a vault service" : null;
    }

    /** Keeps the last four characters: {@code XXXXXXXX1234}. */
    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        int keep = Math.min(4, Math.max(0, value.length() - 4));
        return "X".repeat(Math.min(12, value.length() - keep)) + value.substring(value.length() - keep);
    }

    private String hmac(String s) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return Hashes.hex(mac.doFinal(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
