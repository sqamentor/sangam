// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.calendar;

import org.sangam.config.locale.BusinessCalendar;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.catalogue.ResourceCatalogue;
import org.sangam.resources.catalogue.ResourceType;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;

import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Availability calendars (N01): opening hours bound to business calendars, blackouts, maintenance windows, reserved
 * periods and slot templates. Blackouts on an ancestor (a building) apply to every resource below it (its rooms).
 */
public final class CalendarService {

    /** Kinds of closure. All make the resource unavailable; the kind is reported and drives notices. */
    public enum BlackoutKind { BLACKOUT, MAINTENANCE, HOLIDAY, RESERVED }

    public record Blackout(String blackoutId, String calendarId, String resourceId, BlackoutKind kind, Instant startsAt,
                           Instant endsAt, String reason, String createdBy) {
        public Interval interval() {
            return new Interval(startsAt, endsAt);
        }
    }

    /** A blackout plus the active bookings and allotments it now overlaps (to be moved, bumped or confirmed). */
    public record BlackoutResult(Blackout blackout, List<String> affectedBookingIds) {
    }

    /** A recurring slot pattern of a resource (OPD sessions, test slots, hearing sessions). */
    public record SlotTemplate(String templateId, String resourceId, Set<DayOfWeek> weekdays, LocalTime from,
                               LocalTime to, int slotMinutes, int unitsPerSlot, LocalDate validFrom, LocalDate validTo,
                               Map<String, Object> attributes) {
    }

    /** One generated slot. */
    public record Slot(String resourceId, Instant startsAt, Instant endsAt, int units, String templateId) {
    }

    private final ResourcesContext ctx;
    private final ResourceCatalogue catalogue;

    public CalendarService(ResourcesContext ctx, ResourceCatalogue catalogue) {
        this.ctx = ctx;
        this.catalogue = catalogue;
    }

    // ------------------------------------------------------------------ calendars

    public AvailabilityCalendar saveCalendar(Connection c, SecurityIdentity actor, AvailabilityCalendar cal) {
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_ADMIN, "calendar:" + cal.calendarId());
        String t = actor.tenantId();
        Instant now = ctx.now(c);
        int n = Jdbc.update(c, "UPDATE RES_CALENDAR SET name=?, zone_id=?, business_cal_id=?, hours_json=?, closed_on_holidays=?,"
                        + " updated_at=? WHERE tenant_id=? AND calendar_id=?", cal.name(), cal.zone().getId(),
                cal.businessCalendarId(), Sql.toJson(cal.hoursJson()), cal.closedOnHolidays(), now, t, cal.calendarId());
        if (n == 0) {
            Jdbc.update(c, "INSERT INTO RES_CALENDAR(tenant_id, calendar_id, name, zone_id, business_cal_id, hours_json,"
                            + " closed_on_holidays, updated_at) VALUES (?,?,?,?,?,?,?,?)", t, cal.calendarId(), cal.name(),
                    cal.zone().getId(), cal.businessCalendarId(), Sql.toJson(cal.hoursJson()), cal.closedOnHolidays(), now);
        }
        ctx.event(c, actor, "CALENDAR", cal.calendarId(), "CALENDAR_SAVED", null, null, Map.of("hours", cal.hoursJson()));
        return cal;
    }

    public Optional<AvailabilityCalendar> findCalendar(Connection c, String tenantId, String calendarId) {
        if (calendarId == null) {
            return Optional.empty();
        }
        return Jdbc.one(c, "SELECT name, zone_id, business_cal_id, hours_json, closed_on_holidays FROM RES_CALENDAR"
                + " WHERE tenant_id=? AND calendar_id=?", rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("zone", rs.getString("zone_id"));
                    m.put("businessCalendarId", rs.getString("business_cal_id"));
                    m.put("hours", Sql.mapJson(Sql.text(rs, "hours_json")));
                    m.put("closedOnHolidays", rs.getInt("closed_on_holidays") == 1);
                    return AvailabilityCalendar.fromMap(calendarId, m);
                }, tenantId, calendarId);
    }

    /** The calendar that governs a resource: its own, else the nearest ancestor's; empty = open around the clock. */
    public Optional<AvailabilityCalendar> calendarOf(Connection c, String tenantId, Resource r) {
        if (r.calendarId() != null) {
            return findCalendar(c, tenantId, r.calendarId());
        }
        List<String> lineage = r.lineage();
        for (int i = lineage.size() - 2; i >= 0; i--) {
            Optional<Resource> a = catalogue.find(c, tenantId, lineage.get(i));
            if (a.isPresent() && a.get().calendarId() != null) {
                return findCalendar(c, tenantId, a.get().calendarId());
            }
        }
        return Optional.empty();
    }

    public ZoneId zoneOf(Connection c, String tenantId, Resource r) {
        return calendarOf(c, tenantId, r).map(AvailabilityCalendar::zone).orElse(ZoneId.of("UTC"));
    }

    // ------------------------------------------------------------------ blackouts and maintenance windows

    public BlackoutResult addBlackout(Connection c, SecurityIdentity actor, String calendarId, String resourceId,
                                      BlackoutKind kind, Instant from, Instant to, String reason) {
        if ((calendarId == null) == (resourceId == null)) {
            throw ResourcesException.invalid("BLACKOUT_SCOPE", "a blackout applies to exactly one calendar or one resource");
        }
        Interval iv = new Interval(from, to);
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_MODIFY,
                resourceId != null ? resourceId : "calendar:" + calendarId);
        String t = actor.tenantId();
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO RES_BLACKOUT(tenant_id, blackout_id, calendar_id, resource_id, kind, starts_at, ends_at,"
                        + " reason, created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)", t, id, calendarId, resourceId,
                kind.name(), iv.from(), iv.to(), reason, actor.personId(), ctx.now(c));
        Blackout b = new Blackout(id, calendarId, resourceId, kind, iv.from(), iv.to(), reason, actor.personId());
        List<String> affected = affectedBookings(c, t, b);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", kind.name());
        p.put("from", iv.from().toString());
        p.put("to", iv.to().toString());
        p.put("affected", affected);
        ctx.event(c, actor, resourceId != null ? "RESOURCE" : "CALENDAR", resourceId != null ? resourceId : calendarId,
                "BLACKOUT_ADDED", reason, null, p);
        return new BlackoutResult(b, affected);
    }

    public void removeBlackout(Connection c, SecurityIdentity actor, String blackoutId, String reason) {
        String t = actor.tenantId();
        Blackout b = Jdbc.one(c, "SELECT * FROM RES_BLACKOUT WHERE tenant_id=? AND blackout_id=?", rs -> mapBlackout(rs), t, blackoutId)
                .orElseThrow(() -> ResourcesException.notFound("blackout", blackoutId));
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_MODIFY,
                b.resourceId() != null ? b.resourceId() : "calendar:" + b.calendarId());
        Jdbc.update(c, "DELETE FROM RES_BLACKOUT WHERE tenant_id=? AND blackout_id=?", t, blackoutId);
        ctx.event(c, actor, "BLACKOUT", blackoutId, "BLACKOUT_REMOVED", reason, null, Map.of());
    }

    /** Blackouts that apply to a resource in a range: its own, its ancestors' and its calendar's. */
    public List<Blackout> blackouts(Connection c, String tenantId, Resource r, Interval range) {
        List<Blackout> out = new ArrayList<>();
        for (String id : r.lineage()) {
            out.addAll(Jdbc.query(c, "SELECT * FROM RES_BLACKOUT WHERE tenant_id=? AND resource_id=? AND starts_at < ? AND ends_at > ?"
                    + " ORDER BY starts_at", CalendarService::mapBlackout, tenantId, id, range.to(), range.from()));
        }
        Optional<AvailabilityCalendar> cal = calendarOf(c, tenantId, r);
        if (cal.isPresent()) {
            out.addAll(Jdbc.query(c, "SELECT * FROM RES_BLACKOUT WHERE tenant_id=? AND calendar_id=? AND starts_at < ? AND ends_at > ?"
                    + " ORDER BY starts_at", CalendarService::mapBlackout, tenantId, cal.get().calendarId(), range.to(), range.from()));
        }
        return out;
    }

    /** Open intervals of a resource in a range: opening hours (and business days) minus every applicable blackout. */
    public List<Interval> availableIntervals(Connection c, String tenantId, Resource r, Interval range) {
        Optional<AvailabilityCalendar> cal = calendarOf(c, tenantId, r);
        List<Interval> open = cal.map(a -> a.openIntervals(range, business(tenantId, a))).orElse(List.of(range));
        List<Interval> cut = blackouts(c, tenantId, r, range).stream().map(Blackout::interval).toList();
        return Interval.subtract(open, cut);
    }

    /** Whether the resource is open for the whole interval. */
    public boolean isOpen(Connection c, String tenantId, Resource r, Interval iv) {
        return Interval.covered(availableIntervals(c, tenantId, r, iv), iv);
    }

    private BusinessCalendar business(String tenantId, AvailabilityCalendar a) {
        return ctx.businessCalendar(tenantId, a.businessCalendarId()).orElse(null);
    }

    private List<String> affectedBookings(Connection c, String t, Blackout b) {
        List<String> ids = new ArrayList<>();
        if (b.resourceId() != null) {
            Resource r = catalogue.get(c, t, b.resourceId());
            ids.addAll(Jdbc.query(c, "SELECT b.booking_id FROM RES_BOOKING b JOIN RES_RESOURCE r ON r.tenant_id=b.tenant_id"
                            + " AND r.resource_id=b.resource_id WHERE b.tenant_id=? AND (r.resource_id=? OR r.tree_path LIKE ? ESCAPE '!')"
                            + " AND b.state IN ('HELD','PENDING_APPROVAL','CONFIRMED') AND b.starts_at < ? AND b.ends_at > ?",
                    rs -> rs.getString(1), t, r.resourceId(), escapeLike(r.treePath()) + "/%", b.endsAt(), b.startsAt()));
        } else {
            ids.addAll(Jdbc.query(c, "SELECT b.booking_id FROM RES_BOOKING b JOIN RES_RESOURCE r ON r.tenant_id=b.tenant_id"
                            + " AND r.resource_id=b.resource_id WHERE b.tenant_id=? AND r.calendar_id=?"
                            + " AND b.state IN ('HELD','PENDING_APPROVAL','CONFIRMED') AND b.starts_at < ? AND b.ends_at > ?",
                    rs -> rs.getString(1), t, b.calendarId(), b.endsAt(), b.startsAt()));
        }
        return ids;
    }

    // ------------------------------------------------------------------ slot templates

    public SlotTemplate saveSlotTemplate(Connection c, SecurityIdentity actor, SlotTemplate st) {
        String t = actor.tenantId();
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_MODIFY, st.resourceId());
        catalogue.get(c, t, st.resourceId());
        if (st.slotMinutes() <= 0 || st.unitsPerSlot() <= 0 || st.weekdays().isEmpty()) {
            throw ResourcesException.invalid("SLOT_TEMPLATE", "slot minutes, units and weekdays are required");
        }
        String id = st.templateId() == null ? Sql.newId() : st.templateId();
        String days = String.join(",", st.weekdays().stream().map(DayOfWeek::name).sorted().toList());
        Jdbc.update(c, "DELETE FROM RES_SLOT_TEMPLATE WHERE tenant_id=? AND template_id=?", t, id);
        Jdbc.update(c, "INSERT INTO RES_SLOT_TEMPLATE(tenant_id, template_id, resource_id, weekdays, from_time, to_time,"
                        + " slot_minutes, units_per_slot, valid_from, valid_to, attributes_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                t, id, st.resourceId(), days, st.from().toString(), st.to().toString(), st.slotMinutes(), st.unitsPerSlot(),
                st.validFrom() == null ? null : st.validFrom().toString(), st.validTo() == null ? null : st.validTo().toString(),
                Sql.toJson(st.attributes() == null ? Map.of() : st.attributes()));
        ctx.event(c, actor, "RESOURCE", st.resourceId(), "SLOT_TEMPLATE_SAVED", null, null, Map.of("templateId", id));
        return new SlotTemplate(id, st.resourceId(), st.weekdays(), st.from(), st.to(), st.slotMinutes(), st.unitsPerSlot(),
                st.validFrom(), st.validTo(), st.attributes());
    }

    public void deleteSlotTemplate(Connection c, SecurityIdentity actor, String templateId) {
        Jdbc.update(c, "DELETE FROM RES_SLOT_TEMPLATE WHERE tenant_id=? AND template_id=?", actor.tenantId(), templateId);
        ctx.event(c, actor, "SLOT_TEMPLATE", templateId, "SLOT_TEMPLATE_DELETED", null, null, Map.of());
    }

    public List<SlotTemplate> slotTemplates(Connection c, String tenantId, String resourceId) {
        return Jdbc.query(c, "SELECT * FROM RES_SLOT_TEMPLATE WHERE tenant_id=? AND resource_id=? ORDER BY from_time", rs -> {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (String d : rs.getString("weekdays").split(",")) {
                if (!d.isBlank()) {
                    days.add(DayOfWeek.valueOf(d.trim()));
                }
            }
            String vf = rs.getString("valid_from");
            String vt = rs.getString("valid_to");
            return new SlotTemplate(rs.getString("template_id"), rs.getString("resource_id"), days,
                    LocalTime.parse(rs.getString("from_time")), LocalTime.parse(rs.getString("to_time")),
                    rs.getInt("slot_minutes"), rs.getInt("units_per_slot"), vf == null ? null : LocalDate.parse(vf),
                    vt == null ? null : LocalDate.parse(vt), Sql.mapJson(Sql.text(rs, "attributes_json")));
        }, tenantId, resourceId);
    }

    /**
     * The bookable slots of a resource in a range. With slot templates: the template slots that lie inside the open
     * intervals. Without: the type's granularity, aligned in the calendar zone, across the open intervals.
     */
    public List<Slot> slots(Connection c, String tenantId, Resource r, ResourceType type, Interval range) {
        List<Interval> open = availableIntervals(c, tenantId, r, range);
        ZoneId zone = zoneOf(c, tenantId, r);
        List<SlotTemplate> templates = slotTemplates(c, tenantId, r.resourceId());
        List<Slot> out = new ArrayList<>();
        if (!templates.isEmpty()) {
            LocalDate day = range.from().atZone(zone).toLocalDate();
            LocalDate last = range.to().atZone(zone).toLocalDate();
            for (; !day.isAfter(last); day = day.plusDays(1)) {
                for (SlotTemplate st : templates) {
                    if (!st.weekdays().contains(day.getDayOfWeek())
                            || (st.validFrom() != null && day.isBefore(st.validFrom()))
                            || (st.validTo() != null && day.isAfter(st.validTo()))) {
                        continue;
                    }
                    ZonedDateTime s = day.atTime(st.from()).atZone(zone);
                    ZonedDateTime end = day.atTime(st.to()).atZone(zone);
                    for (ZonedDateTime x = s; !x.plusMinutes(st.slotMinutes()).isAfter(end); x = x.plusMinutes(st.slotMinutes())) {
                        Interval iv = new Interval(x.toInstant(), x.plusMinutes(st.slotMinutes()).toInstant());
                        if (range.contains(iv) && Interval.covered(open, iv)) {
                            out.add(new Slot(r.resourceId(), iv.from(), iv.to(), Math.min(st.unitsPerSlot(), Math.max(r.capacity(), 1)),
                                    st.templateId()));
                        }
                    }
                }
            }
            out.sort((a, b) -> a.startsAt().compareTo(b.startsAt()));
            return out;
        }
        Duration step = Duration.ofMinutes(type.slotMinutes());
        for (Interval o : open) {
            Instant x = alignUp(o.from(), zone, type.alignMinutes());
            while (!x.plus(step).isAfter(o.to())) {
                out.add(new Slot(r.resourceId(), x, x.plus(step), Math.max(r.capacity(), 1), null));
                x = x.plus(step);
            }
        }
        return out;
    }

    /** Rounds up to the next multiple of {@code alignMinutes} from local midnight in {@code zone}. */
    public static Instant alignUp(Instant t, ZoneId zone, int alignMinutes) {
        ZonedDateTime z = t.atZone(zone);
        ZonedDateTime midnight = z.toLocalDate().atStartOfDay(zone);
        long minutes = Duration.between(midnight, z).toMinutes();
        boolean exact = Duration.between(midnight, z).toSeconds() % 60 == 0 && minutes % alignMinutes == 0
                && z.getNano() == 0;
        long aligned = exact ? minutes : (minutes / alignMinutes + 1) * alignMinutes;
        return midnight.plusMinutes(aligned).toInstant();
    }

    /** Whether {@code t} is on the alignment grid of {@code zone}. */
    public static boolean aligned(Instant t, ZoneId zone, int alignMinutes) {
        return alignUp(t, zone, alignMinutes).equals(t);
    }

    private static Blackout mapBlackout(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Blackout(rs.getString("blackout_id"), rs.getString("calendar_id"), rs.getString("resource_id"),
                BlackoutKind.valueOf(rs.getString("kind")), Jdbc.instant(rs, "starts_at"), Jdbc.instant(rs, "ends_at"),
                rs.getString("reason"), rs.getString("created_by"));
    }

    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
