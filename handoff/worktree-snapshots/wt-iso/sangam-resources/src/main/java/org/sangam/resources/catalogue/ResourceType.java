// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.catalogue;

import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A resource type defined in configuration (R78 data object): rooms, beds, theatres, halls, vehicles, staff, slots,
 * plots, quarters, units — the module knows none of these names; each is a row of data. The definition carries the
 * attribute schema, how the type is booked, the booking windows and the DMN decision keys for entitlement, priority,
 * tariff and cancellation-refund.
 *
 * @param typeId               stable id, e.g. {@code room}
 * @param name                 display name
 * @param kind                 free classification used by packs and UI ({@code SPACE}, {@code EQUIPMENT}, {@code PERSON}, ...)
 * @param bookingMode          {@link BookingMode}
 * @param slotMinutes          claim granularity for {@code SLOT} bookings (e.g. 15, 60, 1440 for nightly)
 * @param alignMinutes         start alignment (bookings must start on a multiple of this from midnight in the calendar zone)
 * @param holdMinutes          hold expiry before confirmation
 * @param minNoticeMinutes     minimum lead time
 * @param maxAdvanceDays       booking window
 * @param minDurationMinutes   minimum duration
 * @param maxDurationMinutes   maximum duration (0 = unlimited)
 * @param noShowGraceMinutes   after start, an unchecked-in booking becomes NO_SHOW
 * @param defaultCapacity      capacity units of a new resource
 * @param unitLabel            label of one capacity unit (seat, bed, berth ...)
 * @param requiresApproval     confirm only through the approval process
 * @param approvalProcessKey   BPMN process key started for approval
 * @param allowBump            a higher priority booking may bump a lower one (always audited with reason and authority)
 * @param entitlementDecision  DMN key: who may book which class, for how long, at what tariff
 * @param priorityDecision     DMN key: waitlist priority
 * @param tariffDecision       DMN key: tariff code / amount inputs
 * @param cancellationDecision DMN key: refund percent by notice
 * @param defaultTariffCode    tariff when no decision applies
 * @param currency             ISO currency of charges
 * @param refundTiers          fallback cancellation policy: notice hours → refund percent (highest notice first)
 * @param allowedParentTypes   hierarchy rule (empty = any parent)
 * @param attributes           attribute schema
 * @param extra                any further data used by packs
 */
public record ResourceType(String typeId, String name, String kind, BookingMode bookingMode, int slotMinutes,
                           int alignMinutes, int holdMinutes, int minNoticeMinutes, int maxAdvanceDays,
                           int minDurationMinutes, int maxDurationMinutes, int noShowGraceMinutes, int defaultCapacity,
                           String unitLabel, boolean requiresApproval, String approvalProcessKey, boolean allowBump,
                           String entitlementDecision, String priorityDecision, String tariffDecision,
                           String cancellationDecision, String defaultTariffCode, String currency,
                           List<RefundTier> refundTiers, Set<String> allowedParentTypes,
                           List<AttributeDef> attributes, Map<String, Object> extra) {

    /** How a type is booked. */
    public enum BookingMode {
        /** Time-slot booking with slot-granular claims (rooms per night, theatres per 15 minutes, test slots, halls). */
        SLOT,
        /** Long-term allotment with valid-from/valid-to under the resource lock (quarters, plots, seats, flats). */
        ALLOTMENT,
        /** Both: short stays as slots and long occupancy as allotments. */
        BOTH,
        /** Not bookable (a container such as a building or a campus). */
        NONE
    }

    public enum AttrType { STRING, NUMBER, BOOLEAN, ENUM, DATE, LIST }

    public record AttributeDef(String name, AttrType type, boolean required, boolean indexed, List<String> values,
                               Object defaultValue) {
    }

    /** Cancellation with at least {@code minNoticeHours} before start refunds {@code refundPercent}. */
    public record RefundTier(int minNoticeHours, BigDecimal refundPercent) {
    }

    public ResourceType {
        if (typeId == null || typeId.isBlank()) {
            throw ResourcesException.invalid("TYPE_ID", "resource type id is required");
        }
        bookingMode = bookingMode == null ? BookingMode.SLOT : bookingMode;
        refundTiers = refundTiers == null ? List.of() : refundTiers.stream()
                .sorted((a, b) -> Integer.compare(b.minNoticeHours(), a.minNoticeHours())).toList();
        allowedParentTypes = allowedParentTypes == null ? Set.of() : Set.copyOf(allowedParentTypes);
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
        if (slotMinutes <= 0) {
            slotMinutes = 60;
        }
        if (alignMinutes <= 0) {
            alignMinutes = slotMinutes;
        }
        if (holdMinutes <= 0) {
            holdMinutes = 15;
        }
        if (defaultCapacity <= 0) {
            defaultCapacity = 1;
        }
    }

    public boolean slotBookable() {
        return bookingMode == BookingMode.SLOT || bookingMode == BookingMode.BOTH;
    }

    public boolean allotable() {
        return bookingMode == BookingMode.ALLOTMENT || bookingMode == BookingMode.BOTH;
    }

    /** Parses a type definition from configuration (YAML/JSON map). */
    public static ResourceType fromMap(Map<String, Object> m) {
        List<RefundTier> tiers = new ArrayList<>();
        for (Object o : Sql.asList(m.get("refundTiers"))) {
            Map<String, Object> t = Sql.asMap(o);
            tiers.add(new RefundTier(Sql.intOr(t.get("minNoticeHours"), 0), Sql.decimalOrNull(t.get("refundPercent"))));
        }
        List<AttributeDef> attrs = new ArrayList<>();
        for (Object o : Sql.asList(m.get("attributes"))) {
            Map<String, Object> a = Sql.asMap(o);
            List<String> values = Sql.asList(a.get("values")).stream().map(String::valueOf).toList();
            attrs.add(new AttributeDef(Sql.str(a.get("name")),
                    a.get("type") == null ? AttrType.STRING : AttrType.valueOf(a.get("type").toString().toUpperCase()),
                    Sql.bool(a.get("required"), false), Sql.bool(a.get("indexed"), false), values, a.get("default")));
        }
        Set<String> parents = Set.copyOf(Sql.asList(m.get("allowedParentTypes")).stream().map(String::valueOf).toList());
        Object mode = m.get("bookingMode");
        Map<String, Object> extra = Sql.asMap(m.get("extra"));
        return new ResourceType(Sql.str(m.get("typeId")), Sql.str(m.getOrDefault("name", m.get("typeId"))),
                Sql.str(m.getOrDefault("kind", "GENERIC")),
                mode == null ? BookingMode.SLOT : BookingMode.valueOf(mode.toString().toUpperCase()),
                Sql.intOr(m.get("slotMinutes"), 60), Sql.intOr(m.get("alignMinutes"), 0), Sql.intOr(m.get("holdMinutes"), 15),
                Sql.intOr(m.get("minNoticeMinutes"), 0), Sql.intOr(m.get("maxAdvanceDays"), 365),
                Sql.intOr(m.get("minDurationMinutes"), 0), Sql.intOr(m.get("maxDurationMinutes"), 0),
                Sql.intOr(m.get("noShowGraceMinutes"), 0), Sql.intOr(m.get("defaultCapacity"), 1),
                Sql.str(m.get("unitLabel")), Sql.bool(m.get("requiresApproval"), false), Sql.str(m.get("approvalProcessKey")),
                Sql.bool(m.get("allowBump"), false), Sql.str(m.get("entitlementDecision")), Sql.str(m.get("priorityDecision")),
                Sql.str(m.get("tariffDecision")), Sql.str(m.get("cancellationDecision")), Sql.str(m.get("defaultTariffCode")),
                Sql.str(m.getOrDefault("currency", "XXX")), tiers, parents, attrs, extra);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("typeId", typeId);
        m.put("name", name);
        m.put("kind", kind);
        m.put("bookingMode", bookingMode.name());
        m.put("slotMinutes", slotMinutes);
        m.put("alignMinutes", alignMinutes);
        m.put("holdMinutes", holdMinutes);
        m.put("minNoticeMinutes", minNoticeMinutes);
        m.put("maxAdvanceDays", maxAdvanceDays);
        m.put("minDurationMinutes", minDurationMinutes);
        m.put("maxDurationMinutes", maxDurationMinutes);
        m.put("noShowGraceMinutes", noShowGraceMinutes);
        m.put("defaultCapacity", defaultCapacity);
        m.put("unitLabel", unitLabel);
        m.put("requiresApproval", requiresApproval);
        m.put("approvalProcessKey", approvalProcessKey);
        m.put("allowBump", allowBump);
        m.put("entitlementDecision", entitlementDecision);
        m.put("priorityDecision", priorityDecision);
        m.put("tariffDecision", tariffDecision);
        m.put("cancellationDecision", cancellationDecision);
        m.put("defaultTariffCode", defaultTariffCode);
        m.put("currency", currency);
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (RefundTier t : refundTiers) {
            tiers.add(Map.of("minNoticeHours", t.minNoticeHours(), "refundPercent", t.refundPercent().toPlainString()));
        }
        m.put("refundTiers", tiers);
        m.put("allowedParentTypes", List.copyOf(allowedParentTypes));
        List<Map<String, Object>> attrs = new ArrayList<>();
        for (AttributeDef a : attributes) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("name", a.name());
            am.put("type", a.type().name());
            am.put("required", a.required());
            am.put("indexed", a.indexed());
            am.put("values", a.values());
            if (a.defaultValue() != null) {
                am.put("default", a.defaultValue());
            }
            attrs.add(am);
        }
        m.put("attributes", attrs);
        m.put("extra", extra);
        return m;
    }

    /** Validates and normalises attribute values against the schema (unknown attributes are kept as free data). */
    public Map<String, Object> validate(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>(values == null ? Map.of() : values);
        for (AttributeDef a : attributes) {
            Object v = out.get(a.name());
            if (v == null && a.defaultValue() != null) {
                v = a.defaultValue();
                out.put(a.name(), v);
            }
            if (v == null) {
                if (a.required()) {
                    throw ResourcesException.invalid("ATTRIBUTE_REQUIRED", "attribute '" + a.name() + "' is required for " + typeId);
                }
                continue;
            }
            switch (a.type()) {
                case NUMBER -> {
                    try {
                        out.put(a.name(), new BigDecimal(v.toString()).stripTrailingZeros().toPlainString());
                    } catch (NumberFormatException e) {
                        throw ResourcesException.invalid("ATTRIBUTE_TYPE", "attribute '" + a.name() + "' must be a number");
                    }
                }
                case BOOLEAN -> out.put(a.name(), Boolean.parseBoolean(v.toString()));
                case ENUM -> {
                    if (!a.values().isEmpty() && !a.values().contains(v.toString())) {
                        throw ResourcesException.invalid("ATTRIBUTE_VALUE", "attribute '" + a.name() + "' must be one of " + a.values());
                    }
                }
                case DATE -> {
                    try {
                        java.time.LocalDate.parse(v.toString());
                    } catch (RuntimeException e) {
                        throw ResourcesException.invalid("ATTRIBUTE_TYPE", "attribute '" + a.name() + "' must be an ISO date");
                    }
                }
                case LIST -> {
                    if (!(v instanceof List<?>)) {
                        out.put(a.name(), List.of(v.toString()));
                    }
                }
                default -> { }
            }
        }
        return out;
    }

    /** Names of attributes copied to {@code RES_RESOURCE_ATTR} for indexed search. */
    public List<String> indexedAttributes() {
        return attributes.stream().filter(AttributeDef::indexed).map(AttributeDef::name).toList();
    }
}
