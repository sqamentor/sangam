// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.onboarding.api.OnboardingRequest;
import org.sangam.onboarding.api.OnboardingRun.ReadinessReport;
import org.sangam.onboarding.api.Problem;
import org.sangam.onboarding.deps.PackDependencyResolver;
import org.sangam.onboarding.port.OnboardingPorts;
import org.sangam.onboarding.support.OnboardingJson;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;
import org.yaml.snakeyaml.Yaml;

/**
 * SL-14: onboarding defines the tenant's org levels (request, else the profile default {@code security.orgLevels})
 * before it seeds pack seats, and the dry run refuses a seat whose level is missing or unknown instead of letting the
 * SECURITY step fail with {@code unknown level type ...}.
 */
class OrgLevelsTest {

    private static final List<String> DEFAULT = List.of("HEAD_OFFICE", "REGIONAL_OFFICE", "FIELD_OFFICE");

    private static OnboardingRequest request(String securityJson) throws Exception {
        return OnboardingJson.MAPPER.readValue("{\"tenant\":{\"id\":\"T-LEVELS\"},\"security\":" + securityJson + "}",
                OnboardingRequest.class);
    }

    private static ResolvedProfile profile(Map<String, Object> data) {
        return new ResolvedProfile("T-LEVELS", null, LocalDate.now(), data, List.of("INTL", "T-LEVELS"), "b", Map.of(),
                List.of());
    }

    private static PackDescriptor pack(PackDescriptor.SeedSeat... seats) {
        return new PackDescriptor("test.pack", "1.0.0", Map.of("en", "t"), "test", "", List.of(), List.of(), List.of(),
                List.of(seats), List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private static PackDescriptor.SeedSeat seat(String id, String office, String level) {
        return new PackDescriptor.SeedSeat(id, "R", office, level, null, Map.of("en", id));
    }

    private static List<String> codes(List<Problem> ps) {
        return ps.stream().filter(Problem::isError).map(Problem::code).toList();
    }

    @Test
    void requestLevelsWinOverTheProfileDefault() throws Exception {
        OrgLevels l = OrgLevels.resolve(request("{\"orgLevels\":[\"STATE\",\"DISTRICT\"]}"),
                profile(Map.of("security", Map.of("orgLevels", DEFAULT))));
        assertEquals(List.of("STATE", "DISTRICT"), l.levels());
        assertEquals("REQUEST", l.source());
        assertEquals(List.of(), codes(l.check(List.of(pack(seat("S1", "X", "DISTRICT"))), List.of(), false)));
    }

    @Test
    void profileDefaultIsUsedWhenTheRequestNamesNone() throws Exception {
        OrgLevels l = OrgLevels.resolve(request("{}"), profile(Map.of("security", Map.of("orgLevels", DEFAULT))));
        assertEquals(DEFAULT, l.levels());
        assertEquals("PROFILE", l.source());
        assertEquals(List.of(), codes(l.check(List.of(pack(seat("S1", "X", "FIELD_OFFICE"))), List.of(), false)));
    }

    @Test
    void unknownSeatLevelIsAnError() throws Exception {
        OrgLevels l = OrgLevels.resolve(request("{\"orgLevels\":[\"STATE\"]}"), null);
        assertEquals(List.of("SEAT_LEVEL_UNKNOWN"),
                codes(l.check(List.of(pack(seat("S1", "X", "OFFICE"))), List.of(), false)));
    }

    @Test
    void seatsWithoutAnyHierarchyAreAnError() throws Exception {
        OrgLevels l = OrgLevels.resolve(request("{}"), profile(Map.of()));
        assertEquals("NONE", l.source());
        assertEquals(List.of("ORG_LEVELS_MISSING"), codes(l.check(List.of(pack(seat("S1", "X", "A"))), List.of(), false)));
        // no seats: nothing to define, nothing wrong
        assertEquals(List.of(), codes(l.check(List.of(pack()), List.of(), false)));
    }

    @Test
    void seatWithoutLevelBlankOrDuplicateLevelsAndOfficeConflictsAreErrors() throws Exception {
        OrgLevels l = OrgLevels.resolve(request("{\"orgLevels\":[\"A\",\"B\",\"A\",\" \"]}"), null);
        List<String> c = codes(l.check(List.of(pack(seat("S1", "X", null), seat("S2", "Y", "A"), seat("S3", "Y", "B"))),
                List.of(), false));
        assertEquals(List.of("ORG_LEVELS_INVALID", "ORG_LEVELS_INVALID", "SEAT_LEVEL_MISSING", "OFFICE_LEVEL_CONFLICT"), c);
    }

    @Test
    void levelsFixedByExistingUnitsCannotChange() throws Exception {
        OrgLevels l = OrgLevels.resolve(request("{\"orgLevels\":[\"A\",\"B\"]}"), null);
        assertEquals(List.of("ORG_LEVELS_FIXED"), codes(l.check(List.of(), List.of("A"), true)));
        assertEquals(List.of(), codes(l.check(List.of(), List.of("A", "B"), true)), "same levels: idempotent");
        assertEquals(List.of(), codes(l.check(List.of(), List.of("A"), false)), "no units yet: redefinable");
    }

    /** Every shipped pack's seat names a level of the shipped platform default (INTL {@code security.orgLevels}). */
    @Test
    @SuppressWarnings("unchecked")
    void shippedPackSeatsNameLevelsOfTheShippedDefault() throws Exception {
        Map<String, Object> intl;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("profiles/intl/INTL.yaml")) {
            intl = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions())).load(in);
        }
        List<String> shipped = (List<String>) ((Map<String, Object>) intl.get("security")).get("orgLevels");
        assertEquals(DEFAULT, shipped);
        List<PackDescriptor> packs = new ArrayList<>();
        PackRegistry.discover(getClass().getClassLoader()).all().forEach(p -> packs.add(p.descriptor()));
        assertTrue(packs.stream().filter(p -> !p.seats().isEmpty()).count() > 10, "packs with seats are on the class path");
        List<Problem> ps = OrgLevels.resolve(request("{}"), profile(intl)).check(packs, List.of(), false);
        assertEquals(List.of(), ps.stream().filter(Problem::isError).map(p -> p.code() + " " + p.subject()).toList());
    }

    /** The dry run's readiness report carries the check, so a wrong hierarchy blocks before any run starts. */
    @Test
    void readinessReportsUnknownSeatLevel() throws Exception {
        SangamPack shipped = PackRegistry.discover(getClass().getClassLoader()).all().stream()
                .filter(p -> !p.descriptor().seats().isEmpty()).findFirst().orElseThrow();
        ReadinessChecker checker = new ReadinessChecker(port(OnboardingPorts.ConnectorPort.class),
                port(OnboardingPorts.SecurityPort.class), port(OnboardingPorts.LanguagePort.class),
                port(OnboardingPorts.TenantPort.class));
        PackDependencyResolver.Resolution none = new PackDependencyResolver.Resolution(List.of(), Map.of(), List.of(),
                Map.of(), Map.of(), List.of(), List.of());
        ResolvedProfile p = profile(Map.of("security", Map.of("orgLevels", DEFAULT)));

        ReadinessReport bad = checker.check(request("{\"orgLevels\":[\"ONLY_LEVEL\"]}"), p, none, List.of(shipped),
                List.of(), "h", 1, Instant.now());
        assertEquals("FAIL", bad.checks().get("org-levels"));
        assertTrue(codes(bad.problems()).contains("SEAT_LEVEL_UNKNOWN"), String.valueOf(bad.problems()));

        ReadinessReport good = checker.check(request("{}"), p, none, List.of(shipped), List.of(), "h", 1, Instant.now());
        assertEquals("PASS", good.checks().get("org-levels"));
    }

    /** A port whose every call answers "nothing" (empty, absent, false). */
    @SuppressWarnings("unchecked")
    private static <T> T port(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, m, args) -> {
            Class<?> r = m.getReturnType();
            if (r == List.class) {
                return List.of();
            }
            if (r == Set.class) {
                return Set.of();
            }
            if (r == Map.class) {
                return Map.of();
            }
            if (r == Optional.class) {
                return Optional.empty();
            }
            if (r == boolean.class) {
                return false;
            }
            if (r == int.class || r == long.class) {
                return 0;
            }
            return null;
        });
    }
}
