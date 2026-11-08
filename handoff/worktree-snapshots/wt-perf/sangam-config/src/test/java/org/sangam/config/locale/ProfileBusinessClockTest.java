// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import org.junit.jupiter.api.Test;
import org.sangam.config.api.ConfigConnector;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.MasterStore;
import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProfileBusinessClockTest {

    /** 2026-04-01T19:00Z = 2026-04-02 00:30 at +05:30. */
    static final Clock AT = Clock.fixed(Instant.parse("2026-04-01T19:00:00Z"), ZoneOffset.UTC);
    static final LocalDate D = LocalDate.of(2026, 4, 2);

    static ProfileDocument doc(String id, String version, LocalDate from, LocalDate to, String zone, String marker) {
        Map<String, Object> locale = zone == null ? Map.of() : Map.of("zone", zone);
        return new ProfileDocument(id, "base", version, from, to, null, null, null, null, null, null,
                Map.of("locale", locale, "marker", marker));
    }

    /** EAST at +05:30 with a new version from 2026-04-02; ZERO in UTC with the same split; NOZONE without locale.zone. */
    static ProfileResolver resolver() {
        List<ProfileDocument> docs = new ArrayList<>();
        docs.add(doc("EAST", "1", null, D, "Asia/Kolkata", "old"));
        docs.add(doc("EAST", "2", D, null, "Asia/Kolkata", "new"));
        docs.add(doc("ZERO", "1", null, D, "UTC", "old"));
        docs.add(doc("ZERO", "2", D, null, "UTC", "new"));
        docs.add(doc("NOZONE", "1", null, null, null, "x"));
        return new ProfileResolver(InMemoryProfileRepository.active(docs), LayerModel.open());
    }

    @Test
    void tenantTodayComesFromTheProfileZone() {
        ProfileBusinessClock b = new ProfileBusinessClock(resolver(), AT);
        assertEquals(ZoneId.of("Asia/Kolkata"), b.zone("EAST"));
        assertEquals(LocalDate.of(2026, 4, 2), b.today("EAST"));
        assertEquals(LocalDate.of(2026, 4, 1), b.today("ZERO"));
        assertEquals(LocalDate.of(2026, 4, 1), b.today("NOZONE"), "locale.zone defaults to UTC");
        assertEquals(LocalDate.of(2026, 4, 1), b.today("MISSING"), "no profile: UTC fallback");
        assertEquals(ZoneOffset.UTC, b.zone(null));
    }

    @Test
    void zoneChangeOnTheTenantsLocalDayIsTakenFromThatDay() {
        // Until 2026-04-02 the tenant is in UTC; from its 2026-04-02 it is at +05:30. At 19:00Z on 04-01 the UTC-date
        // profile says UTC (local 04-01), so no re-resolution happens: still 04-01 in the old zone.
        List<ProfileDocument> docs = List.of(doc("MOVE", "1", null, D, "UTC", "a"), doc("MOVE", "2", D, null, "Asia/Kolkata", "b"));
        ProfileResolver r = new ProfileResolver(InMemoryProfileRepository.active(docs), LayerModel.open());
        assertEquals(LocalDate.of(2026, 4, 1), new ProfileBusinessClock(r, AT).today("MOVE"));
        // The reverse change (+05:30 until 04-02, UTC from 04-02): the UTC-date zone (+05:30) puts the tenant on
        // 04-02, where the profile says UTC, which would move it back to 04-01: no fixed point, keep the +05:30 zone.
        List<ProfileDocument> back = List.of(doc("BACK", "1", null, D, "Asia/Kolkata", "a"), doc("BACK", "2", D, null, "UTC", "b"));
        ProfileResolver rb = new ProfileResolver(InMemoryProfileRepository.active(back), LayerModel.open());
        assertEquals(LocalDate.of(2026, 4, 2), new ProfileBusinessClock(rb, AT).today("BACK"));
        // +05:30 until 04-02 and +05:45 from 04-02: re-resolved on the local day, the new zone is consistent.
        List<ProfileDocument> shift = List.of(doc("SHIFT", "1", null, D, "Asia/Kolkata", "a"),
                doc("SHIFT", "2", D, null, "Asia/Kathmandu", "b"));
        ProfileResolver rs = new ProfileResolver(InMemoryProfileRepository.active(shift), LayerModel.open());
        assertEquals(ZoneId.of("Asia/Kathmandu"), new ProfileBusinessClock(rs, AT).zone("SHIFT"));
    }

    @Test
    void configConnectorDefaultEffectiveDateIsTheTenantsToday() {
        ProfileResolver r = resolver();
        ConfigConnector c = new ConfigConnector(r, new ClassificationRegistry(new MasterStore.InMemory()),
                new NumberSeries.InMemory(), new ProfileBusinessClock(r, AT));
        assertEquals("new", marker(c, "EAST"), "00:30 local on 2026-04-02: the version effective from that day");
        assertEquals("old", marker(c, "ZERO"), "19:00 UTC on 2026-04-01: the earlier version");
    }

    @SuppressWarnings("unchecked")
    static Object marker(ConfigConnector c, String tenant) {
        ConnectorResult res = c.invoke(new ConnectorRequest(tenant, UUID.randomUUID(), "a", 1, null, null, "profile.get",
                Map.of("paths", List.of("marker")), null));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, res);
        return ((Map<String, Object>) s.output().get("values")).get("marker");
    }
}
