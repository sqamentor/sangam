// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.org.OrgModel;
import org.sangam.security.org.PostingService;
import org.sangam.security.support.Sql;

/**
 * Register E-31(a), E-31(b), E-32(c): {@code PostingService} on H2, without a server.
 * <ul>
 *   <li>a requested leave ({@code requestLeave}) changes nothing until it is approved by another person
 *       ({@code decideLeave}; AJ-04 step 5 "requester cannot approve"), is decided once, and a rejection gives no seat;</li>
 *   <li>{@code expireDue} writes {@code DELEGATION_EXPIRED} once per ended acting posting (AJ-04 step 4), for every
 *       tenant, and nothing for a regular posting or an acting posting still in force;</li>
 *   <li>{@code regularHolderAt} names the substantive holder, on leave or not.</li>
 * </ul>
 */
class PostingLeaveAndExpiryH2Test {

    static final String T = "t-seat-left";
    static final String T2 = "t-seat-left-2";
    static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");

    JdbcDataSource ds;
    SangamSecurity security;
    Transactions tx;

    @BeforeEach
    void schema() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:seatleft" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(ds);
        tx.inTransaction(c -> {
            for (String t : List.of(T, T2)) {
                Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                        + " VALUES (?,?,?,?,?,?,?)", t, t, "ACTIVE", "{}", "{}", 0, PAST);
                for (String p : List.of("officer", "substitute", "approver", "idle")) {
                    Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status, created_at)"
                            + " VALUES (?,?,?,?,?,?)", t, p, "Name of " + p, "sub-" + p, "ACTIVE", PAST);
                }
                for (String s : List.of("S1", "S2", "S-VACANT")) {
                    Jdbc.update(c, "INSERT INTO SEC_SEAT(tenant_id, seat_id, designation, rank_no, pool_flag, escalation_seat_id, status,"
                            + " created_at, abolished_at) VALUES (?,?,?,?,?,?,?,?,NULL)", t, s, "Officer", 1, 0, null, "ACTIVE", PAST);
                }
            }
            // the officer regularly holds two seats of tenant T
            security.postings().post(c, admin(T), "S1", "officer", OrgModel.PostingKind.REGULAR, PAST, null, "order-1", null);
            security.postings().post(c, admin(T), "S2", "officer", OrgModel.PostingKind.REGULAR, PAST, null, "order-2", null);
            return null;
        });
    }

    static SecurityIdentity admin(String tenant) {
        return SecurityIdentity.system(tenant, "seat-left-admin").withPerson("approver");
    }

    static SecurityIdentity as(String person) {
        return SecurityIdentity.system(T, "seat-left").withPerson(person);
    }

    Instant now() {
        return tx.inTransaction(c -> Sql.dbNow(c, security.dialect()));
    }

    List<String> seatsNow(String person) {
        return tx.inTransaction(c -> security.postings().seatsOf(c, T, person, Sql.dbNow(c, security.dialect())).stream()
                .map(h -> h.seatId() + " " + h.kind()).sorted().toList());
    }

    long events(String tenant, String type) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND event_type=?",
                rs -> rs.getLong(1), tenant, type).orElse(0L));
    }

    String code(Runnable work) {
        return assertThrows(SangamSecurityException.class, work::run).code();
    }

    // ------------------------------------------------------------------ E-31(a) request and decision

    @Test
    void aRequestedLeaveTakesEffectOnlyWhenAnotherPersonApprovesIt() {
        Instant from = now().minusSeconds(60);
        Instant to = from.plusSeconds(3 * 24 * 3600L);
        OrgModel.Leave asked = tx.inTransaction(c -> security.postings().requestLeave(c, as("officer"), from, to, "substitute"));
        // pending: the officer still acts in both seats, the substitute in none
        assertEquals(List.of("S1 REGULAR", "S2 REGULAR"), seatsNow("officer"));
        assertEquals(List.of(), seatsNow("substitute"));
        PostingService.LeaveRecord pending = tx.inTransaction(c -> security.postings().leave(c, T, asked.leaveId())).orElseThrow();
        assertEquals("REQUESTED officer [S1, S2]", pending.status() + " " + pending.requestedBy() + " " + pending.seats());
        assertEquals(0, events(T, "LEAVE_RECORDED"));

        // requester cannot approve: by the officer's own identity, even with the admin right of a system identity
        assertEquals("SOD_VIOLATION", code(() -> tx.inTransaction(c ->
                security.postings().decideLeave(c, as("officer"), asked.leaveId(), true))));
        assertEquals(List.of("S1 REGULAR", "S2 REGULAR"), seatsNow("officer"), "a refused decision changes nothing");

        PostingService.LeaveRecord approved = tx.inTransaction(c -> security.postings().decideLeave(c, admin(T), asked.leaveId(), true));
        assertEquals("APPROVED approver", approved.status() + " " + approved.leave().approvedBy());
        // effective: the substitute acts for every seat the officer regularly holds, the officer for none
        assertEquals(List.of(), seatsNow("officer"));
        assertEquals(List.of("S1 LEAVE_SUBSTITUTE", "S2 LEAVE_SUBSTITUTE"), seatsNow("substitute"));
        assertEquals(1, events(T, "LEAVE_RECORDED"));
        assertEquals(2, events(T, "DELEGATION_GRANTED"));

        // decided once
        assertEquals("INVALID_TRANSITION", code(() -> tx.inTransaction(c ->
                security.postings().decideLeave(c, admin(T), asked.leaveId(), false))));
        assertEquals(List.of("S1 LEAVE_SUBSTITUTE", "S2 LEAVE_SUBSTITUTE"), seatsNow("substitute"));
    }

    @Test
    void aRejectedRequestChangesNothingAndCannotBeApprovedLater() {
        Instant from = now().minusSeconds(60);
        OrgModel.Leave asked = tx.inTransaction(c -> security.postings().requestLeave(c, as("officer"), from, from.plusSeconds(86_400), "substitute"));
        PostingService.LeaveRecord rejected = tx.inTransaction(c -> security.postings().decideLeave(c, admin(T), asked.leaveId(), false));
        assertEquals("REJECTED approver", rejected.status() + " " + rejected.leave().approvedBy());
        assertEquals(List.of("S1 REGULAR", "S2 REGULAR"), seatsNow("officer"));
        assertEquals(List.of(), seatsNow("substitute"));
        assertEquals(0, events(T, "LEAVE_RECORDED"));
        assertEquals("INVALID_TRANSITION", code(() -> tx.inTransaction(c ->
                security.postings().decideLeave(c, admin(T), asked.leaveId(), true))));
    }

    @Test
    void aRequestIsRefusedWhenItCannotStand() {
        Instant from = now().minusSeconds(60);
        Instant to = from.plusSeconds(86_400);
        assertEquals("SOD_VIOLATION", code(() -> tx.inTransaction(c -> security.postings().requestLeave(c, as("officer"), from, to, "officer"))),
            "own substitute");
        assertEquals("NOT_POSTED", code(() -> tx.inTransaction(c -> security.postings().requestLeave(c, as("idle"), from, to, "substitute"))),
            "a person who holds no seat");
        assertEquals("UNKNOWN_PERSON", code(() -> tx.inTransaction(c -> security.postings().requestLeave(c, as("officer"), from, to, "nobody"))));
        assertEquals("INVALID_RANGE", code(() -> tx.inTransaction(c -> security.postings().requestLeave(c, as("officer"), to, from, "substitute"))));
        assertEquals("UNKNOWN_LEAVE", code(() -> tx.inTransaction(c -> security.postings().decideLeave(c, admin(T), "no-such-leave", true))));
        // a leave of another tenant is not found
        OrgModel.Leave asked = tx.inTransaction(c -> security.postings().requestLeave(c, as("officer"), from, to, "substitute"));
        assertEquals("UNKNOWN_LEAVE", code(() -> tx.inTransaction(c -> security.postings().decideLeave(c, admin(T2), asked.leaveId(), true))));
        assertEquals(0L, tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM SEC_LEAVE WHERE tenant_id=? AND status<>'REQUESTED'",
                rs -> rs.getLong(1), T).orElse(0L)));
    }

    // ------------------------------------------------------------------ E-32(c) the officer acted for

    @Test
    void theRegularHolderIsNamedAlsoWhileOnLeave() {
        Instant from = now().minusSeconds(60);
        tx.inTransaction(c -> security.postings().recordLeave(c, admin(T), "officer", from, from.plusSeconds(86_400), "substitute", "officer"));
        assertEquals(Optional.of("officer"), tx.inTransaction(c -> security.postings().regularHolderAt(c, T, "S1", Sql.dbNow(c, security.dialect()))));
        assertEquals(Optional.empty(), tx.inTransaction(c -> security.postings().regularHolderAt(c, T, "S-VACANT", Sql.dbNow(c, security.dialect()))));
        assertEquals(Optional.empty(), tx.inTransaction(c -> security.postings().regularHolderAt(c, T2, "S1", Sql.dbNow(c, security.dialect()))),
                "another tenant's seat of the same id");
    }

    // ------------------------------------------------------------------ E-31(b) the timer at valid-to

    @Test
    void anEndedActingPostingIsReportedOnceAndOnlyThat() {
        Instant now = now();
        String[] ended = new String[1];
        tx.inTransaction(c -> {
            // ended a moment ago, and one still in force for a week; tenant T2 has an ended charge of its own
            ended[0] = security.postings().grantAdditionalCharge(c, admin(T), "S-VACANT", "substitute", PAST, now.minusSeconds(5), "charge-1", null).postingId();
            security.postings().grantAdditionalCharge(c, admin(T), "S-VACANT", "idle", now.minusSeconds(5), now.plusSeconds(7 * 86_400), "charge-2", null);
            security.postings().grantAdditionalCharge(c, admin(T2), "S-VACANT", "substitute", PAST, now.minusSeconds(5), "charge-3", null);
            // a regular posting that ended is not a delegation
            String regular = security.postings().post(c, admin(T2), "S1", "officer", OrgModel.PostingKind.REGULAR, PAST, null, "order-9", null).postingId();
            security.postings().endPosting(c, admin(T2), regular, now.minusSeconds(5));
            return null;
        });
        assertEquals(1, tx.inTransaction(c -> security.postings().expireDue(c, T)), "tenant T: the one ended charge");
        assertEquals(1, events(T, "DELEGATION_EXPIRED"));
        assertEquals(0, tx.inTransaction(c -> security.postings().expireDue(c, T)), "reported once");
        assertEquals(0, events(T2, "DELEGATION_EXPIRED"), "the sweep of one tenant writes nothing for another");
        // the sweep over every tenant picks up what is left, once
        assertEquals(1, tx.inTransaction(c -> security.postings().expireDue(c)));
        assertEquals(0, tx.inTransaction(c -> security.postings().expireDue(c)));
        assertEquals(1, events(T, "DELEGATION_EXPIRED"));
        assertEquals(1, events(T2, "DELEGATION_EXPIRED"));
        assertTrue(tx.inTransaction(c -> Jdbc.one(c, "SELECT expiry_notified FROM SEC_POSTING WHERE tenant_id=? AND posting_id=?",
                rs -> rs.getInt(1), T, ended[0]).orElse(0)) == 1);
    }
}
