// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.meeting;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.casefile.CaseFileException;
import org.sangam.casefile.CaseFileSchema;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-166 (6): {@code POST /meeting-notices/{id}/ack} answered 204 for any id. The acknowledgement is recorded once (a
 * repeat keeps the first time and still succeeds); an id the tenant does not have, including another tenant's notice,
 * is NOT_FOUND (404) and writes nothing. Owner decision 66: only the notice's recipient ({@code recipient_ref}) may
 * acknowledge it; anyone else is ACCESS_DENIED (403) and writes nothing.
 */
class MeetingNoticeAckH2Test {

    static final SecurityIdentity MEMBER = person("member-1", Set.of());
    static final SecurityIdentity CLERK = person("clerk", Set.of());

    static SecurityIdentity person(String personId, Set<String> seats) {
        return new SecurityIdentity("T1", personId, personId, Set.of(), Set.of(), seats, Set.of(), "token", null, null, false, Map.of());
    }

    private static Connection db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        Connection c = ds.getConnection();
        CaseFileSchema.create(c, Dialect.POSTGRESQL);
        return c;
    }

    private static void notice(Connection c, String tenant, String id, String recipientRef) {
        Jdbc.update(c, "INSERT INTO CF_NOTICE(tenant_id, notice_id, meeting_id, notice_kind, recipient_ref, channel, content_hash,"
                + " delivery_ref, sent_by, sent_at) VALUES (?,?,?,?,?,?,?,?,?,?)", Db.tenant(tenant), id, "M-1", "NOTICE",
                recipientRef, null, "h", null, "clerk", Timestamp.from(Instant.parse("2026-11-12T10:00:00Z")));
    }

    private static void notice(Connection c, String tenant, String id) {
        notice(c, tenant, id, "user:member-1");
    }

    private static Optional<Instant> acked(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT acknowledged_at FROM CF_NOTICE WHERE tenant_id=? AND notice_id=? AND acknowledged_at IS NOT NULL",
                rs -> Jdbc.instant(rs, "acknowledged_at"), Db.tenant(tenant), id);
    }

    private static MeetingService meetings() {
        return new MeetingService(Dialect.POSTGRESQL, null, null, null, null, null, null);
    }

    @Test
    void theFirstAcknowledgementIsRecordedAndARepeatKeepsIt() throws Exception {
        try (Connection c = db()) {
            notice(c, "T1", "N-1");
            MeetingService m = meetings();
            m.acknowledgeNotice(c, "T1", "N-1", MEMBER);
            Instant first = acked(c, "T1", "N-1").orElseThrow();
            m.acknowledgeNotice(c, "T1", "N-1", MEMBER);
            assertEquals(first, acked(c, "T1", "N-1").orElseThrow(), "a repeat keeps the first acknowledgement");
        }
    }

    @Test
    void anUnknownIdIsNotFound() throws Exception {
        try (Connection c = db()) {
            CaseFileException e = assertThrows(CaseFileException.class, () -> meetings().acknowledgeNotice(c, "T1", "N-404", MEMBER));
            assertEquals(CaseFileException.Code.NOT_FOUND, e.code());
            assertEquals(404, e.status());
        }
    }

    @Test
    void anotherTenantsNoticeIsNotFoundAndStaysUnacknowledged() throws Exception {
        try (Connection c = db()) {
            notice(c, "T2", "N-2");
            CaseFileException e = assertThrows(CaseFileException.class, () -> meetings().acknowledgeNotice(c, "T1", "N-2", MEMBER));
            assertEquals(CaseFileException.Code.NOT_FOUND, e.code());
            assertTrue(acked(c, "T2", "N-2").isEmpty(), "the other tenant's notice is untouched");
        }
    }

    @Test
    void onlyTheRecipientMayAcknowledge() throws Exception {
        try (Connection c = db()) {
            notice(c, "T1", "N-3");
            CaseFileException e = assertThrows(CaseFileException.class, () -> meetings().acknowledgeNotice(c, "T1", "N-3", CLERK));
            assertEquals(CaseFileException.Code.ACCESS_DENIED, e.code());
            assertEquals(403, e.status());
            assertTrue(acked(c, "T1", "N-3").isEmpty(), "a non-recipient writes nothing");
        }
    }

    @Test
    void aSeatNoticeIsAcknowledgedByTheSeatHolder() throws Exception {
        try (Connection c = db()) {
            notice(c, "T1", "N-4", "seat:chair");
            SecurityIdentity delegate = new SecurityIdentity("T1", "deputy", "deputy", Set.of(), Set.of(), Set.of(), Set.of("chair"),
                    "token", null, null, false, Map.of());
            assertThrows(CaseFileException.class, () -> meetings().acknowledgeNotice(c, "T1", "N-4", delegate),
                    "acting for the seat is not holding it");
            meetings().acknowledgeNotice(c, "T1", "N-4", person("someone", Set.of("chair")));
            assertTrue(acked(c, "T1", "N-4").isPresent());
        }
    }

    @Test
    void recipientMatching() {
        assertTrue(MeetingService.isRecipient(MEMBER, "user:member-1"));
        assertFalse(MeetingService.isRecipient(MEMBER, "user:member-10"));
        assertFalse(MeetingService.isRecipient(MEMBER, "member-1"), "an unprefixed reference matches nobody");
        assertFalse(MeetingService.isRecipient(null, "user:member-1"));
        assertFalse(MeetingService.isRecipient(MEMBER, null));
    }
}
