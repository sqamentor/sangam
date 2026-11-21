// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * The office a test case of a pack is started for (P-6 Step 2, SANGAM.md IX.5 rule 7). A REST or start-form start
 * names the case office ({@code officeId}) or takes the office of the starter's seat; the test starters use a
 * synthetic seat that is in no seed, so the start names the pack's own office: the office its acceptance scenario
 * starts a case in, else the lowest office of its seed that holds seats. A start that already names an office, or that
 * starts from a seat of the pack's seed, is left unchanged. Nothing outside the pack's own descriptor and seed is
 * used: no other pack's office and no role pool.
 */
public final class PackStartOffice {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile PackRegistry shipped;

    private PackStartOffice() {
    }

    /**
     * {@link #apply(SangamPack, Map)} for the shipped pack whose main process is {@code processKey}, on a copy of
     * {@code vars} (which may be immutable). A key of no pack's main process is left unchanged.
     */
    public static Map<String, Object> apply(String processKey, Map<String, Object> vars) {
        if (shipped == null) {
            shipped = PackRegistry.discover();
        }
        Map<String, Object> out = new HashMap<>(vars);
        shipped.all().stream().filter(p -> p.descriptor().mainProcesses().contains(processKey)).findFirst()
                .ifPresent(p -> apply(p, out));
        return out;
    }

    /** Puts the pack's office as {@code officeId} unless the start names an office or starts from a seed seat. */
    static Map<String, Object> apply(SangamPack pack, Map<String, Object> vars) {
        PackDescriptor p = pack.descriptor();
        Object given = vars.get("officeId");
        Object seat = vars.get("actingSeat");
        boolean seedSeat = seat != null && p.seats().stream().anyMatch(s -> s.id().equals(seat.toString()));
        if (!seedSeat && (given == null || given.toString().isBlank())) {
            String office = of(pack);
            if (office != null) {
                vars.put("officeId", office);
            }
        }
        return vars;
    }

    /**
     * The office the pack's acceptance scenario starts a case in, else the seed's lowest office with seats (no other
     * seat office lies under it; IX.5 rule 2 serves it from that office and its ancestors), the one with most seats on a
     * tie (first in seed order); null when the seed places no seat.
     */
    static String of(SangamPack pack) {
        PackDescriptor p = pack.descriptor();
        for (PackDescriptor.AcceptanceScenario s : p.acceptance()) {
            Object o = s.startVariables().get("officeId");
            if (o != null && !o.toString().isBlank()) {
                return o.toString();
            }
        }
        Map<String, Integer> count = new LinkedHashMap<>();
        for (PackDescriptor.SeedSeat s : p.seats()) {
            if (s.office() != null && !s.office().isBlank()) {
                count.merge(s.office(), 1, Integer::sum);
            }
        }
        Map<String, String> parents = seedParents(pack);
        Set<String> above = new HashSet<>();
        for (String office : count.keySet()) {
            String up = parents.get(office);
            for (int guard = 0; up != null && !up.isBlank() && guard < 64; guard++) {
                above.add(up);
                up = parents.get(up);
            }
        }
        // max keeps the first of equal counts (seed order)
        return count.entrySet().stream().filter(e -> !above.contains(e.getKey()))
                .max(Comparator.comparingInt(Map.Entry<String, Integer>::getValue))
                .map(Map.Entry::getKey).orElse(null);
    }

    /** The office parents of the pack's seed files ({@code offices[].parent}). */
    static Map<String, String> seedParents(SangamPack pack) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PackDescriptor.PackResource r : pack.descriptor().resources(PackDescriptor.PackResource.Kind.SEED)) {
            try (InputStream in = pack.open(r.path())) {
                for (JsonNode o : JSON.readTree(in).path("offices")) {
                    out.put(o.path("id").asText(), o.path("parent").asText(""));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }
}
