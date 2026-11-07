// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.deps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a pack needs beyond its {@code dependsOn} list: platform and engine versions, other modules, pack version
 * ranges, connectors, profile keys, conflicts and upgrade paths. Read from the optional {@code requires},
 * {@code conflicts} and {@code upgrades} blocks of a pack manifest, merged over data rules
 * ({@value PackRequirementsCatalog#RULES}); nothing here is specific to a domain.
 *
 * <pre>
 * "requires": {
 *   "sangam": "&gt;=0.1.0-0", "engine": "&gt;=2.2.0 &lt;3.0.0",
 *   "modules": {"sangam-config": "^0.1.0-0"},
 *   "packs": {"sangam.core": "^1.0.0"},
 *   "connectors": ["notify.sms"], "optionalConnectors": ["notify.inapp"],
 *   "profileKeys": ["locale.currency", "documentTypes"]
 * },
 * "conflicts": {"other.pack": "*"},
 * "upgrades": [{"from": "&gt;=1.0.0 &lt;1.1.0", "migration": "activity ids stable; PackMigrator", "breaking": false}]
 * </pre>
 *
 * @param packId             pack id
 * @param sangam             platform version range
 * @param engine             engine version range
 * @param modules            required module (artifactId) to version range
 * @param packs              required pack id to version range (includes every {@code dependsOn} id, range {@code *})
 * @param connectors         connector ids the pack calls and that must be bound before go-live
 * @param optionalConnectors connector ids the pack can run without (only reported)
 * @param profileKeys        profile paths the pack reads; each must resolve and must not be a placeholder
 * @param conflicts          packs that must not be enabled together with this one, with the conflicting range
 * @param upgrades           allowed upgrade paths into this version
 */
public record PackRequirements(String packId, VersionRange sangam, VersionRange engine, Map<String, VersionRange> modules,
                               Map<String, VersionRange> packs, Set<String> connectors, Set<String> optionalConnectors,
                               List<String> profileKeys, Map<String, VersionRange> conflicts, List<UpgradePath> upgrades) {

    public PackRequirements {
        sangam = sangam == null ? VersionRange.ANY : sangam;
        engine = engine == null ? VersionRange.ANY : engine;
        modules = modules == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(modules));
        packs = packs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(packs));
        connectors = connectors == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(connectors));
        optionalConnectors = optionalConnectors == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(optionalConnectors));
        profileKeys = profileKeys == null ? List.of() : List.copyOf(profileKeys);
        conflicts = conflicts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(conflicts));
        upgrades = upgrades == null ? List.of() : List.copyOf(upgrades);
    }

    /**
     * An allowed upgrade into this version.
     *
     * @param from      installed versions this path applies to
     * @param migration what the operator must know (running cases stay on their version, or migrate with PackMigrator)
     * @param breaking  true when the upgrade changes behaviour of running cases or needs data changes
     */
    public record UpgradePath(VersionRange from, String migration, boolean breaking) {
    }

    public static PackRequirements empty(String packId) {
        return new PackRequirements(packId, null, null, null, null, null, null, null, null, null);
    }

    /** {@code this} with {@code over} applied: ranges of {@code over} win, lists and sets are unions. */
    public PackRequirements merge(PackRequirements over) {
        if (over == null) {
            return this;
        }
        Map<String, VersionRange> m = new LinkedHashMap<>(modules);
        m.putAll(over.modules);
        Map<String, VersionRange> p = new LinkedHashMap<>(packs);
        over.packs.forEach((k, v) -> {
            if (!v.isAny() || !p.containsKey(k)) {
                p.put(k, v);
            }
        });
        Set<String> c = new LinkedHashSet<>(connectors);
        c.addAll(over.connectors);
        Set<String> oc = new LinkedHashSet<>(optionalConnectors);
        oc.addAll(over.optionalConnectors);
        c.removeAll(oc);
        List<String> keys = new ArrayList<>(profileKeys);
        over.profileKeys.stream().filter(k -> !keys.contains(k)).forEach(keys::add);
        Map<String, VersionRange> cf = new LinkedHashMap<>(conflicts);
        cf.putAll(over.conflicts);
        List<UpgradePath> up = new ArrayList<>(upgrades);
        up.addAll(over.upgrades);
        return new PackRequirements(packId, over.sangam.isAny() ? sangam : over.sangam,
                over.engine.isAny() ? engine : over.engine, m, p, c, oc, keys, cf, up);
    }
}
