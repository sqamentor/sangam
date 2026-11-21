// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.sim;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.packs.port.SeatDirectory;
import org.sangam.packs.port.SeatHolding;

/**
 * In-memory seat model loaded from pack seed files ({@code seed.json}: offices, seats, holdings). A tenant id of
 * {@code null} in a lookup matches the seed's default tenant ({@code demo}).
 */
public class InMemorySeatDirectory implements SeatDirectory {

    public static final String DEFAULT_TENANT = "demo";

    private record Seat(String id, String role, String office, String reportsTo) {
    }

    private final Map<String, Map<String, String>> officeParent = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Seat>> seats = new ConcurrentHashMap<>();
    private final Map<String, List<SeatHolding>> holdings = new ConcurrentHashMap<>();

    private static String t(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
    }

    /** Loads a pack seed file into {@code tenantId}. Repeated loads add or replace by id. */
    public void load(String tenantId, InputStream seedJson) {
        try {
            JsonNode root = new ObjectMapper().readTree(seedJson);
            String tenant = t(tenantId);
            for (JsonNode o : root.path("offices")) {
                officeParent.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
                        .put(o.path("id").asText(), o.path("parent").asText(""));
            }
            for (JsonNode s : root.path("seats")) {
                addSeat(tenant, s.path("id").asText(), s.path("role").asText(), s.path("office").asText(),
                        s.path("reportsTo").asText(null));
            }
            for (JsonNode h : root.path("holdings")) {
                addHolding(tenant, new SeatHolding(h.path("seat").asText(), h.path("person").asText(),
                        SeatHolding.Basis.valueOf(h.path("basis").asText("SUBSTANTIVE")),
                        h.path("ref").asText(null), LocalDate.parse(h.path("from").asText("2000-01-01")),
                        h.hasNonNull("to") ? LocalDate.parse(h.path("to").asText()) : null));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void addOffice(String tenantId, String officeId, String parentId) {
        officeParent.computeIfAbsent(t(tenantId), k -> new ConcurrentHashMap<>()).put(officeId,
                parentId == null ? "" : parentId);
    }

    public void addSeat(String tenantId, String seatId, String role, String office, String reportsTo) {
        seats.computeIfAbsent(t(tenantId), k -> new ConcurrentHashMap<>())
                .put(seatId, new Seat(seatId, role, office, reportsTo == null || reportsTo.isBlank() ? null : reportsTo));
    }

    public void addHolding(String tenantId, SeatHolding h) {
        holdings.computeIfAbsent(t(tenantId), k -> new CopyOnWriteArrayList<>()).add(h);
    }

    @Override
    public Optional<SeatHolding> holding(String tenantId, String personId, String seatId, LocalDate date) {
        return holdingsOf(tenantId, personId, date).stream().filter(h -> h.seatId().equals(seatId))
                .min((a, b) -> Boolean.compare(a.actedFor(), b.actedFor()));
    }

    @Override
    public List<SeatHolding> holdingsOf(String tenantId, String personId, LocalDate date) {
        List<SeatHolding> out = new ArrayList<>();
        for (SeatHolding h : holdings.getOrDefault(t(tenantId), List.of())) {
            if (h.personId().equals(personId) && h.validOn(date)) {
                out.add(h);
            }
        }
        return out;
    }

    @Override
    public Optional<String> holderOf(String tenantId, String seatId, LocalDate date) {
        String acting = null;
        for (SeatHolding h : holdings.getOrDefault(t(tenantId), List.of())) {
            if (h.seatId().equals(seatId) && h.validOn(date)) {
                if (!h.actedFor()) {
                    return Optional.of(h.personId());
                }
                acting = h.personId();
            }
        }
        return Optional.ofNullable(acting);
    }

    @Override
    public Optional<String> roleOf(String tenantId, String seatId) {
        Seat s = seats.getOrDefault(t(tenantId), Map.of()).get(seatId);
        return Optional.ofNullable(s == null ? null : s.role());
    }

    @Override
    public Optional<String> officeOf(String tenantId, String seatId) {
        Seat s = seats.getOrDefault(t(tenantId), Map.of()).get(seatId);
        return Optional.ofNullable(s == null ? null : s.office());
    }

    @Override
    public Optional<String> seatForRole(String tenantId, String roleCode, String officeId) {
        // the case office, then its ancestors up to the root; no office (or none above the root) is no seat, never a
        // seat of the role in another office (IX.5 rules 1 and 2, P-6 finding 4)
        Map<String, Seat> all = seats.getOrDefault(t(tenantId), Map.of());
        Map<String, String> parents = officeParent.getOrDefault(t(tenantId), Map.of());
        String office = officeId == null || officeId.isBlank() ? null : officeId;
        for (int guard = 0; office != null && guard < 64; guard++) {
            for (Seat s : all.values()) {
                if (s.role().equals(roleCode) && s.office().equals(office)) {
                    return Optional.of(s.id());
                }
            }
            String up = parents.getOrDefault(office, "");
            office = up.isBlank() ? null : up;
        }
        return Optional.empty();
    }

    @Override
    public List<String> eligibleSeats(String tenantId, String roleCode, String officeId, LocalDate date) {
        List<String> out = new ArrayList<>();
        if (officeId == null || officeId.isBlank()) {
            return out;   // as PacksSeatDirectory: no office, no candidate (never every seat of the role)
        }
        for (Seat s : seats.getOrDefault(t(tenantId), Map.of()).values()) {
            if (s.role().equals(roleCode) && s.office().equals(officeId)
                    && holderOf(tenantId, s.id(), date).isPresent()) {
                out.add(s.id());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    @Override
    public Optional<String> escalationSeatOf(String tenantId, String seatId) {
        Seat s = seats.getOrDefault(t(tenantId), Map.of()).get(seatId);
        return Optional.ofNullable(s == null ? null : s.reportsTo());
    }
}
