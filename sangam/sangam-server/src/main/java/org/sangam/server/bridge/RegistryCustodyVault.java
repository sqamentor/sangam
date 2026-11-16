// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Objects;
import java.util.Optional;

import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.support.HmacIdentifierVault;

/**
 * Decision 389: the registry identifier vault with clear-value custody. Matching keeps the registry's keyed hash
 * ({@link HmacIdentifierVault}, so lookups and existing hashes are unchanged); a value the identifier type vaults is held
 * by the compliance national-identifier vault ({@code EncryptedNationalIdVault}, table {@code CMP_NATIONAL_ID_VAULT}),
 * encrypted with the tenant's data key, and the registry row keeps only its reference key. So there is one custody
 * store, and every vaulting and every reveal is audited on the tenant's compliance chain (with the actor and purpose
 * of the reveal). A reveal needs both an explicit actor and a purpose; there is no ambient-context fallback.
 */
public final class RegistryCustodyVault implements RegistryPorts.IdentifierVault, RegistryPorts.DevelopmentOnly {

    private final HmacIdentifierVault hashes;
    private final CompliancePorts.NationalIdVault custody;

    public RegistryCustodyVault(HmacIdentifierVault hashes, CompliancePorts.NationalIdVault custody) {
        this.hashes = Objects.requireNonNull(hashes, "hashes");
        this.custody = Objects.requireNonNull(custody, "custody");
    }

    @Override
    public RegistryPorts.ProtectedValue protect(String tenant, String idType, String normalisedValue,
                                                boolean storeInVault) {
        RegistryPorts.ProtectedValue hashed = hashes.protect(tenant, idType, normalisedValue, false);
        if (!storeInVault) {
            return hashed;
        }
        String ref = custody.referenceKey(tenant, idType, normalisedValue);
        return new RegistryPorts.ProtectedValue(hashed.hashHex(), ref, hashed.masked());
    }

    @Override
    public Optional<String> reveal(String tenant, String vaultRef, String purpose, String actor) {
        RegistryPorts.IdentifierVault.requireRevealReason(purpose, actor);
        return custody.reveal(tenant, vaultRef, purpose, actor);
    }

    /** Production-safe exactly when the hash pepper is configured (the custody store is durable). */
    @Override
    public String developmentOnlyReason() {
        return hashes.developmentOnlyReason();
    }
}
