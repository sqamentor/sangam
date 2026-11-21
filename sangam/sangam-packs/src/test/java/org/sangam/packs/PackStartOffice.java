// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.packs.spi.PackDescriptor;

/**
 * The office a test case of a pack is started for (P-6 Step 2, SANGAM.md IX.5 rule 7). A REST or start-form start
 * names the case office ({@code officeId}) or takes the office of the starter's seat; the test starters use a
 * synthetic seat that is not in any seed, so the start names the office the pack's own seed places its seats in (the
 * office most of its seats are in; first in seed order on a tie). A start that already names an office or a seed seat
 * is left unchanged. Nothing outside the pack's seed is used: no other pack's office and no role pool.
 */
public final class PackStartOffice {

    private PackStartOffice() {
    }

    /** The seed office of the pack, or null when its seed places no seat. */
    public static String of(PackDescriptor p) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (PackDescriptor.SeedSeat s : p.seats()) {
            if (s.office() != null && !s.office().isBlank()) {
                count.merge(s.office(), 1, Integer::sum);
            }
        }
        // maxBy keeps the first of equal counts (seed order)
        return count.entrySet().stream().max(Comparator.comparingInt(Map.Entry<String, Integer>::getValue))
                .map(Map.Entry::getKey).orElse(null);
    }

    private static volatile org.sangam.packs.spi.PackRegistry shipped;

    /**
     * {@link #apply(PackDescriptor, Map)} for the shipped pack whose main process is {@code processKey}, on a copy of
     * {@code vars} (which may be immutable).
     */
    public static Map<String, Object> apply(String processKey, Map<String, Object> vars) {
        if (shipped == null) {
            shipped = org.sangam.packs.spi.PackRegistry.discover();
        }
        return apply(shipped, processKey, new java.util.HashMap<>(vars));
    }

    /** {@link #apply(PackDescriptor, Map)} for the pack whose main process is {@code processKey}. */
    public static Map<String, Object> apply(org.sangam.packs.spi.PackRegistry registry, String processKey,
                                            Map<String, Object> vars) {
        registry.all().stream().map(org.sangam.packs.spi.SangamPack::descriptor)
                .filter(d -> d.mainProcesses().contains(processKey)).findFirst()
                .ifPresent(d -> apply(d, vars));
        return vars;
    }

    /** Puts the pack's seed office as {@code officeId} unless the start names an office already. */
    public static Map<String, Object> apply(PackDescriptor p, Map<String, Object> vars) {
        Object given = vars.get("officeId");
        Object seat = vars.get("actingSeat");
        boolean seedSeat = seat != null && p.seats().stream().anyMatch(s -> s.id().equals(seat.toString()));
        if (!seedSeat && (given == null || given.toString().isBlank())) {
            String office = of(p);
            if (office != null) {
                vars.put("officeId", office);
            }
        }
        return vars;
    }
}
