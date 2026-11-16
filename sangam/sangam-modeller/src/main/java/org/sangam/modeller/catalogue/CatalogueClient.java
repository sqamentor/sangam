// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.catalogue;

import org.sangam.modeller.catalogue.ExtensionCatalogue.CatalogueException;
import org.sangam.modeller.catalogue.ExtensionCatalogue.Entry;

import java.security.PublicKey;
import java.util.List;
import java.util.Set;

/**
 * The consumer side of the platform-wide catalogue (owner decision 380): reads the signed index from its source,
 * checks it against the trusted keys, and hands out an artefact only after every check passed. Fails closed: a bad
 * signature, a code kind, a licence outside the allow-list (an empty allow-list allows none), a newer platform or a
 * checksum mismatch each refuse with a named code.
 */
public final class CatalogueClient {

    /** An entry and its artefact bytes, which passed every check. */
    public record Verified(Entry entry, byte[] artifact) {
    }

    private final CatalogueSource source;
    private final List<PublicKey> trusted;
    private final Set<String> allowedLicences;
    private final String platformVersion;

    public CatalogueClient(CatalogueSource source, List<PublicKey> trusted, Set<String> allowedLicences, String platformVersion) {
        if (trusted == null || trusted.isEmpty()) {
            throw new CatalogueException("CATALOGUE_KEYS_MISSING", "no trusted catalogue public key");
        }
        this.source = source;
        this.trusted = List.copyOf(trusted);
        this.allowedLicences = Set.copyOf(allowedLicences);
        this.platformVersion = platformVersion;
    }

    /** Every entry of the verified index. */
    public List<Entry> entries() {
        return ExtensionCatalogue.read(source.index(), source.signature(), trusted);
    }

    /** The entry {@code id}@{@code version} with its artefact, for an install of kind {@code package} only. */
    public Verified forInstall(String id, String version) {
        Entry e = entries().stream().filter(x -> x.id().equals(id) && x.version().equals(version)).findFirst()
                .orElseThrow(() -> new CatalogueException("CATALOGUE_ENTRY_NOT_FOUND", id + "@" + version));
        if (!ExtensionCatalogue.KIND_PACKAGE.equals(e.kind())) {
            throw new CatalogueException("CATALOGUE_KIND_NOT_INSTALLABLE",
                    "kind " + e.kind() + " is code and is never installed from the catalogue; only kind package is");
        }
        byte[] artifact = source.artifact(e.artifactPath());
        List<String> refusals = ExtensionCatalogue.check(e, artifact, allowedLicences, platformVersion);
        if (!refusals.isEmpty()) {
            throw new CatalogueException("CATALOGUE_CHECK_FAILED", String.join("; ", refusals));
        }
        return new Verified(e, artifact);
    }
}
