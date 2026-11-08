// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.readiness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.config.profile.ResolvedProfile;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.Problem;
import org.sangam.onboarding.api.StepId;
import org.sangam.packs.spi.PackDescriptor;

/**
 * The tenant's org hierarchy (level types, top first) that onboarding defines before it seeds pack seats (SL-14).
 * Source, in order: the request ({@code security.orgLevels}), else the resolved profile ({@code security.orgLevels},
 * shipped as a platform default in the international layer and overridable by any lower layer). Levels are fixed once
 * the first org unit exists (A02), so a wrong hierarchy must be refused by the dry run, not discovered by a failed run.
 *
 * @param levels the level types, top first; empty when no source names any
 * @param source {@code REQUEST}, {@code PROFILE} or {@code NONE}
 */
public record OrgLevels(List<String> levels, String source) {

    /** Profile key of the default hierarchy. */
    public static final String PROFILE_KEY = "security.orgLevels";

    public OrgLevels {
        levels = List.copyOf(levels);
    }

    public static OrgLevels resolve(OnboardingRequest req, ResolvedProfile profile) {
        List<String> requested = req.security().orgLevels();
        if (!requested.isEmpty()) {
            return new OrgLevels(clean(requested), "REQUEST");
        }
        if (profile != null && profile.get(PROFILE_KEY) instanceof List<?> l && !l.isEmpty()) {
            List<String> out = new ArrayList<>();
            l.forEach(o -> out.add(o == null ? "" : String.valueOf(o)));
            return new OrgLevels(clean(out), "PROFILE");
        }
        return new OrgLevels(List.of(), "NONE");
    }

    private static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<>();
        in.forEach(s -> out.add(s == null ? "" : s.trim()));
        return out;
    }

    /**
     * Problems that would make the SECURITY step fail: an invalid or missing hierarchy, a seat without a level or with
     * a level outside the hierarchy, one office seeded at two levels, or a different hierarchy already fixed by units.
     *
     * @param existing   the tenant's current levels (empty for a new tenant)
     * @param unitsExist the tenant already has org units (its levels can no longer change)
     */
    public List<Problem> check(List<PackDescriptor> packs, List<String> existing, boolean unitsExist) {
        List<Problem> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String l : levels) {
            if (l.isEmpty()) {
                out.add(Problem.error(StepId.SECURITY, "ORG_LEVELS_INVALID", PROFILE_KEY,
                        "a level type is blank (" + source.toLowerCase() + ")"));
            } else if (!seen.add(l)) {
                out.add(Problem.error(StepId.SECURITY, "ORG_LEVELS_INVALID", PROFILE_KEY,
                        "level type " + l + " is listed twice (" + source.toLowerCase() + ")"));
            }
        }
        boolean anySeat = packs.stream().anyMatch(p -> !p.seats().isEmpty());
        if (anySeat && levels.isEmpty()) {
            out.add(Problem.error(StepId.SECURITY, "ORG_LEVELS_MISSING", PROFILE_KEY,
                    "the packs seed seats but no org hierarchy is available: set security.orgLevels in the request or"
                            + " in the profile"));
        }
        if (!existing.isEmpty() && unitsExist && !existing.equals(levels)) {
            out.add(Problem.error(StepId.SECURITY, "ORG_LEVELS_FIXED", PROFILE_KEY, "the tenant's org levels "
                    + existing + " are fixed because org units exist; requested " + levels));
        }
        Map<String, String> officeLevel = new LinkedHashMap<>();
        for (PackDescriptor p : packs) {
            for (PackDescriptor.SeedSeat s : p.seats()) {
                String subject = p.id() + "/" + s.id();
                String level = s.level() == null ? "" : s.level().trim();
                if (level.isEmpty()) {
                    out.add(Problem.error(StepId.SECURITY, "SEAT_LEVEL_MISSING", subject,
                            "the seat seed names no org level"));
                    continue;
                }
                if (!levels.isEmpty() && !seen.contains(level)) {
                    out.add(Problem.error(StepId.SECURITY, "SEAT_LEVEL_UNKNOWN", subject, "level " + level
                            + " is not one of the tenant's org levels " + levels + " (" + source.toLowerCase() + ")"));
                }
                String office = office(s);
                String earlier = officeLevel.putIfAbsent(office, level);
                if (earlier != null && !earlier.equals(level)) {
                    out.add(Problem.error(StepId.SECURITY, "OFFICE_LEVEL_CONFLICT", subject, "office " + office
                            + " is seeded at level " + earlier + " and at level " + level));
                }
            }
        }
        return out;
    }

    /** The office a seat seed is placed in (a blank office means the tenant head-quarters unit {@code HQ}). */
    public static String office(PackDescriptor.SeedSeat s) {
        return s.office() == null || s.office().isBlank() ? "HQ" : s.office();
    }
}
