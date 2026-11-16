// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.InboxPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;

/**
 * RM-30 (decision 386(2)): the equivalence scenarios on the host-neutral inbox and audit ports. Like the others they
 * run only through the host's own ports; the audit-tamper scenario alone also writes to the host's database
 * ({@link HostHarness#database()}), as an attacker with database access would, to prove the host detects it.
 */
final class PortScenarios {

    static final String INBOX_SOURCE = "conformance.inbox";

    private PortScenarios() {
    }

    /** One message id is accepted once per tenant; a redelivery is a duplicate; another tenant's id is its own. */
    static BusinessResult inboxDedupe(HostHarness host) {
        RequestContext a = host.administrator(HostHarness.TENANT_A);
        RequestContext b = host.administrator(HostHarness.TENANT_B);
        InboxPort inbox = host.port(InboxPort.class);
        String id = "msg-" + UUID.randomUUID();
        InboxPort.Receipt first = inbox.receive(a, INBOX_SOURCE, id, "corr-1", "{\"amountMinor\":2500}");
        InboxPort.Receipt again = inbox.receive(a, INBOX_SOURCE, id, "corr-1", "{\"amountMinor\":2500}");
        InboxPort.Receipt changed = inbox.receive(a, INBOX_SOURCE, id, "corr-1", "{\"amountMinor\":9999}");
        InboxPort.Receipt otherTenant = inbox.receive(b, INBOX_SOURCE, id, "corr-1", "{\"amountMinor\":2500}");
        String unknown = inbox.state(a, INBOX_SOURCE, "msg-" + UUID.randomUUID());
        return BusinessResult.builder()
                .put("first", first.outcome().name()).put("first.state", first.state())
                .put("redelivery", again.outcome().name())
                .put("changedBody", changed.outcome().name())
                .put("otherTenant", otherTenant.outcome().name())
                .put("unknown.state", unknown == null ? "none" : unknown)
                .put("missingId", EquivalenceScenarios.refusal(() -> inbox.receive(a, INBOX_SOURCE, " ", null, "{}")))
                .build();
    }

    /** Two recorded events verify; after one row is altered in the database the chain fails at that event. */
    static BusinessResult auditTamper(HostHarness host) throws SQLException {
        RequestContext a = host.administrator(HostHarness.TENANT_A);
        AuditPort audit = host.port(AuditPort.class);
        String chain = "conformance-tamper-" + UUID.randomUUID();
        String first = audit.record(a, chain, "CONFORMANCE_RECORDED", Map.of("step", 1, "amountMinor", 2500));
        audit.record(a, chain, "CONFORMANCE_RECORDED", Map.of("step", 2, "amountMinor", 2500));
        AuditPort.ChainVerification before = only(audit.verify(a, chain, null), chain);
        List<AuditPort.AuditEntry> entries = audit.search(a, new AuditPort.AuditFilter(chain, null, null, null, null),
                new Page.PageRequest(0, 10)).items().stream()
                .sorted(Comparator.comparing(AuditPort.AuditEntry::seq)).toList();
        DataSource db = host.database();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE AUDIT_EVENT SET event_type=? WHERE event_id=?")) {
            ps.setString(1, "CONFORMANCE_ALTERED");
            ps.setString(2, first);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("the recorded event " + first + " is not in AUDIT_EVENT");
            }
        }
        AuditPort.ChainVerification after = only(audit.verify(a, chain, null), chain);
        return BusinessResult.builder()
                .put("recorded", entries.size())
                .put("before.ok", before.ok()).put("before.events", before.eventsChecked())
                .put("after.ok", after.ok())
                .put("after.firstBad.isFirstEvent", entries.get(0).seq().equals(after.firstBadSeq()))
                .build();
    }

    private static AuditPort.ChainVerification only(AuditPort.VerifyResult r, String chain) {
        return r.chains().stream().filter(v -> chain.equals(v.chainId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("no verification of chain " + chain + ": " + r));
    }
}
