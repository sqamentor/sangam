// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.retention;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionPack.Language;
import org.sangam.compliance.consent.ConsentService;
import org.sangam.compliance.consent.NoticeTemplate;
import org.sangam.compliance.lawful.LegitimateUseRegistry;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.ports.CompliancePorts.ErasureOutcome;
import org.sangam.compliance.ports.CompliancePorts.ErasurePort;
import org.sangam.compliance.ports.CompliancePorts.ErasureStatus;
import org.sangam.compliance.ports.CompliancePorts.LegalHoldPort;
import org.sangam.compliance.ports.CompliancePorts.PersonalDataPort;
import org.sangam.compliance.ports.CompliancePorts.PrincipalDirectory;
import org.sangam.compliance.ports.CompliancePorts.PrincipalMessage;
import org.sangam.compliance.ports.CompliancePorts.PrincipalNotifier;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Purpose-based retention scheduler (table {@code CMP_RETENTION_ITEM}: one item per workflow root and purpose).
 * Periods, minimum retention, retention classes and the pre-erasure notice come from the jurisdiction pack and the
 * purpose registry (IN: DPDP s.8(7)-(8), r.6(1)(e), r.8(1)-(3), Third Schedule; Public Records Act s.8; CMP-025,
 * CMP-026, CMP-058, CMP-064 … CMP-066, CMP-146).
 *
 * <p>It never erases by itself and never bypasses a hold: erasure goes through {@link ErasurePort} (the integration
 * {@code ErasureService}: tombstones, audit-payload scrubbing, its own legal-hold refusal); {@link LegalHoldPort} (the
 * integration {@code LegalHoldStore}) is checked first and a held item becomes {@code HELD}; a tenant-wide applied
 * exemption that disapplies erasure (IN: s.17(4)) or a purpose exemption gives {@code RETAINED}; a root is erased only
 * when every purpose item on it is due. The data subject gets the pre-erasure notice before erasure; any approach in
 * the meantime ({@link #onPrincipalActivity}) restarts a last-interaction clock and cancels the notice.
 */
public final class RetentionScheduler implements ConsentService.WithdrawalListener {

    public enum State { TRACKING, SCHEDULED, NOTIFIED, ERASING, ERASED, HELD, RETAINED, ERROR }

    public record Item(String itemId, String rootId, String principalRef, String purposeCode, State state, Instant anchorAt,
                       Instant lastProcessedAt, Instant lastApproachAt, Instant recordCreatedAt, Instant eraseDueAt,
                       Instant noticeDueAt, Instant noticeSentAt, boolean withdrawn, int attempts) {
    }

    public record CycleResult(int noticesSent, int erased, int held, int retained, int deferred, int failed) {
    }

    private static final Duration STALE_ERASING = Duration.ofHours(1);

    private final Dialect dialect;
    private final Transactions tx;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final LegitimateUseRegistry purposes;
    private final LegalHoldPort holds;
    private final ErasurePort erasure;
    private final PrincipalNotifier notifier;
    private final PrincipalDirectory principals;
    private final PersonalDataPort personalData;
    private final NoticeTemplate.Catalogues templates;

    public RetentionScheduler(Dialect dialect, Transactions tx, ComplianceAudit audit, ComplianceConfig.Provider config,
                              LegitimateUseRegistry purposes, LegalHoldPort holds, ErasurePort erasure,
                              PrincipalNotifier notifier, PrincipalDirectory principals, PersonalDataPort personalData,
                              NoticeTemplate.Catalogues templates) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = audit;
        this.config = config;
        this.purposes = purposes;
        this.holds = holds;
        this.erasure = erasure;
        this.notifier = notifier == null ? PrincipalNotifier.NONE : notifier;
        this.principals = principals == null ? PrincipalDirectory.NONE : principals;
        this.personalData = personalData;
        this.templates = templates;
    }

    // ---------------------------------------------------------------- lifecycle hooks (caller's transaction)

    /** Starts tracking a root for a purpose (at collection / workflow start). Idempotent per (root, purpose). */
    public String track(Connection c, String tenantId, String rootId, String principalRef, String purposeCode) {
        PurposeDefinition p = purposes.require(c, tenantId, purposeCode);
        JurisdictionPack pack = config.forTenant(tenantId).pack;
        Optional<Item> existing = find(c, tenantId, rootId, purposeCode);
        if (existing.isPresent()) {
            return existing.get().itemId();
        }
        Instant now = Sql.now(c, dialect);
        RetentionSchedule.Trigger t = p.retentionSchedule(pack).trigger();
        Instant anchor = t == RetentionSchedule.Trigger.COLLECTION || t == RetentionSchedule.Trigger.LAST_INTERACTION ? now : null;
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_RETENTION_ITEM(tenant_id, item_id, root_id, principal_ref, purpose_code, state, anchor_at,"
                        + " last_processed_at, last_approach_at, record_created_at, erase_due_at, notice_due_at, notice_sent_at,"
                        + " withdrawn, attempts, last_error, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,0,0,NULL,?)",
                tenantId, id, rootId, principalRef, purposeCode, State.TRACKING, anchor, now, now, now, now);
        recompute(c, tenantId, id);
        return id;
    }

    /** Records processing of the root's data (Rule 8(3): one year from the date of processing). */
    public void touchProcessing(Connection c, String tenantId, String rootId) {
        Instant now = Sql.now(c, dialect);
        for (String id : ids(c, "tenant_id=? AND root_id=? AND state IN ('TRACKING','SCHEDULED','NOTIFIED','HELD','ERROR')", tenantId, rootId)) {
            Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET last_processed_at=? WHERE tenant_id=? AND item_id=?", now, tenantId, id);
            recompute(c, tenantId, id);
        }
    }

    /** s.8(7)(b): the specified purpose is served (the workflow completed). */
    public void onPurposeCompleted(Connection c, String tenantId, String rootId, String purposeCodeOrNull) {
        Instant now = Sql.now(c, dialect);
        String where = "tenant_id=? AND root_id=? AND anchor_at IS NULL AND state IN ('TRACKING','SCHEDULED')"
                + (purposeCodeOrNull == null ? "" : " AND purpose_code=?");
        List<String> ids = purposeCodeOrNull == null ? ids(c, where, tenantId, rootId) : ids(c, where, tenantId, rootId, purposeCodeOrNull);
        for (String id : ids) {
            Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET anchor_at=? WHERE tenant_id=? AND item_id=?", now, tenantId, id);
            recompute(c, tenantId, id);
        }
    }

    /** s.8(7)(a): consent withdrawn — erase once no law requires retention (minimum retention still applies). */
    @Override
    public void onWithdrawn(Connection c, String tenantId, String principalRef, String consentId, Set<String> purposeCodes,
                            ConsentService.Consent consent, Instant at) {
        for (String p : purposeCodes) {
            for (String id : ids(c, "tenant_id=? AND principal_ref=? AND purpose_code=? AND state IN ('TRACKING','SCHEDULED','NOTIFIED','ERROR')",
                    tenantId, principalRef, p)) {
                Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET anchor_at=?, withdrawn=1 WHERE tenant_id=? AND item_id=?", at, tenantId, id);
                recompute(c, tenantId, id);
            }
        }
    }

    /** The principal approached the fiduciary or exercised a right: restart last-interaction clocks, cancel notices. */
    public void onPrincipalActivity(Connection c, String tenantId, String principalRef) {
        Instant now = Sql.now(c, dialect);
        for (String id : ids(c, "tenant_id=? AND principal_ref=? AND state IN ('TRACKING','SCHEDULED','NOTIFIED')", tenantId, principalRef)) {
            Item i = get(c, tenantId, id);
            PurposeDefinition p = purposes.require(c, tenantId, i.purposeCode());
            boolean lastInteraction = p.retentionSchedule(config.forTenant(tenantId).pack).trigger() == RetentionSchedule.Trigger.LAST_INTERACTION;
            Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET last_approach_at=?, anchor_at=" + (lastInteraction && !i.withdrawn() ? "?" : "anchor_at")
                            + ", notice_sent_at=NULL, notice_due_at=NULL, state='SCHEDULED' WHERE tenant_id=? AND item_id=?",
                    lastInteraction && !i.withdrawn() ? new Object[]{now, now, tenantId, id} : new Object[]{now, tenantId, id});
            recompute(c, tenantId, id);
        }
    }

    // ---------------------------------------------------------------- scheduler cycle (own transactions)

    /** One cycle for a tenant: pre-erasure notices, then due erasures. Safe to run on several nodes. */
    public CycleResult runCycle(String tenantId, int limit) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        int notices = 0, erased = 0, held = 0, retained = 0, deferred = 0, failed = 0;
        List<String> noticeIds = tx.inTransaction(c -> ids(c, "tenant_id=? AND state='SCHEDULED' AND notice_sent_at IS NULL"
                + " AND notice_due_at IS NOT NULL AND notice_due_at<=" + dialect.now(), tenantId));
        for (String id : noticeIds.subList(0, Math.min(limit, noticeIds.size()))) {
            if (Boolean.TRUE.equals(tx.inTransaction(c -> sendNotice(c, tenantId, id, cfg)))) {
                notices++;
            }
        }
        Instant recheck = tx.inTransaction(c -> Sql.now(c, dialect)).minus(Duration.ofHours(cfg.legalHoldRecheckHours));
        Instant stale = tx.inTransaction(c -> Sql.now(c, dialect)).minus(STALE_ERASING);
        List<String> due = tx.inTransaction(c -> ids(c, "tenant_id=? AND erase_due_at IS NOT NULL AND erase_due_at<=" + dialect.now()
                + " AND (state IN ('SCHEDULED','NOTIFIED','ERROR') OR (state='HELD' AND updated_at<=?) OR (state='ERASING' AND updated_at<=?))",
                tenantId, recheck, stale));
        for (String id : due.subList(0, Math.min(limit, due.size()))) {
            String outcome;
            try {
                outcome = eraseOne(tenantId, id, cfg);
            } catch (RuntimeException e) {
                String msg = e.getClass().getSimpleName();
                tx.inTransaction(c -> Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET state='ERROR', attempts=attempts+1, last_error=?,"
                        + " updated_at=" + dialect.now() + " WHERE tenant_id=? AND item_id=?", msg, tenantId, id));
                outcome = "FAILED";
            }
            switch (outcome) {
                case "ERASED" -> erased++;
                case "HELD" -> held++;
                case "RETAINED" -> retained++;
                case "DEFERRED" -> deferred++;
                case "FAILED" -> failed++;
                default -> {
                }
            }
        }
        return new CycleResult(notices, erased, held, retained, deferred, failed);
    }

    private boolean sendNotice(Connection c, String tenantId, String id, ComplianceConfig cfg) {
        List<String> locked = Jdbc.query(c, dialect.lockingSelect("item_id", "CMP_RETENTION_ITEM",
                "tenant_id=? AND item_id=? AND state='SCHEDULED' AND notice_sent_at IS NULL"), rs -> rs.getString(1), tenantId, id);
        if (locked.isEmpty()) {
            return false;
        }
        Item i = get(c, tenantId, id);
        PurposeDefinition p = purposes.require(c, tenantId, i.purposeCode());
        Instant now = Sql.now(c, dialect);
        Instant eraseAt = i.eraseDueAt();
        Instant earliest = now.plus(Duration.ofHours(cfg.preErasureNoticeHours));
        if (eraseAt.isBefore(earliest)) {
            eraseAt = earliest;
        }
        Language lang = principals.preferredLanguage(c, tenantId, i.principalRef()).orElse(cfg.defaultLanguage);
        NoticeTemplate.Catalogue cat = templates.of(cfg.pack);
        NoticeTemplate t = cat.get(lang.code()).or(() -> cat.get(cfg.defaultLanguage.code())).orElseThrow();
        String body = t.label("erasure.body", cfg.organisationName).replace("{purpose}", p.name(t.language().code()))
                .replace("{date}", eraseAt.atZone(cfg.pack.timeZone()).toLocalDateTime().toString() + " " + cfg.pack.timeZone().getId());
        String ref = notifier.send(c, new PrincipalMessage(tenantId, i.principalRef(), "PRE_ERASURE_NOTICE", t.language(),
                t.label("erasure.subject", cfg.organisationName), body, Map.of("purpose", p.code(), "itemId", id)));
        Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET state='NOTIFIED', notice_sent_at=?, erase_due_at=?, updated_at=?"
                + " WHERE tenant_id=? AND item_id=?", now, eraseAt, now, tenantId, id);
        audit.principal(c, tenantId, i.principalRef(), ComplianceAudit.RETENTION_NOTICE_SENT, Map.of("item", id,
                "purpose", p.code(), "eraseAt", eraseAt.toString(), "delivery", ref == null ? "" : ref));
        return true;
    }

    private String eraseOne(String tenantId, String id, ComplianceConfig cfg) {
        String decision = tx.inTransaction(c -> {
            List<String> locked = Jdbc.query(c, dialect.lockingSelect("item_id", "CMP_RETENTION_ITEM", "tenant_id=? AND item_id=?"),
                    rs -> rs.getString(1), tenantId, id);
            if (locked.isEmpty()) {
                return "GONE";
            }
            Item i = get(c, tenantId, id);
            Instant now = Sql.now(c, dialect);
            PurposeDefinition p = purposes.require(c, tenantId, i.purposeCode());
            if (p.retentionSchedule(cfg.pack).preErasureNotice() && cfg.preErasureNoticeHours > 0 && i.noticeSentAt() == null) {
                Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET state='SCHEDULED', notice_due_at=?, updated_at=? WHERE tenant_id=? AND item_id=?",
                        now, now, tenantId, id);
                return "DEFERRED";
            }
            if (holds.isHeld(c, tenantId, i.rootId())) {
                Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET state='HELD', updated_at=? WHERE tenant_id=? AND item_id=?", now, tenantId, id);
                audit.root(c, tenantId, i.rootId(), ComplianceAudit.RETENTION_HELD, Map.of("item", id, "purpose", p.code(),
                        "holds", holds.activeHoldIds(c, tenantId, i.rootId())));
                return "HELD";
            }
            List<String> tenantExempt = cfg.appliedExemptions.stream().filter(e -> cfg.pack.exemption(e)
                    .map(JurisdictionPack.Exemption::disappliesErasure).orElse(false)).toList();
            boolean stateExempt = !tenantExempt.isEmpty();
            if (stateExempt || p.erasureDisapplied(cfg.pack)) {
                Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET state='RETAINED', updated_at=? WHERE tenant_id=? AND item_id=?", now, tenantId, id);
                audit.root(c, tenantId, i.rootId(), ComplianceAudit.RETENTION_RETAINED, Map.of("item", id, "purpose", p.code(),
                        "basis", stateExempt ? tenantExempt.toString() : p.exemptions().toString()));
                return "RETAINED";
            }
            long pending = Jdbc.one(c, "SELECT COUNT(*) FROM CMP_RETENTION_ITEM WHERE tenant_id=? AND root_id=? AND item_id<>?"
                            + " AND state IN ('TRACKING','SCHEDULED','NOTIFIED','HELD','ERROR') AND (erase_due_at IS NULL OR erase_due_at>?)",
                    rs -> rs.getLong(1), tenantId, i.rootId(), id, now).orElse(0L);
            if (pending > 0) {
                Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET updated_at=? WHERE tenant_id=? AND item_id=?", now, tenantId, id);
                return "DEFERRED";
            }
            Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET state='ERASING', attempts=attempts+1, updated_at=? WHERE tenant_id=? AND item_id=?",
                    now, tenantId, id);
            return "ERASE";
        });
        if (!"ERASE".equals(decision)) {
            return decision;
        }
        Item item = tx.inTransaction(c -> get(c, tenantId, id));
        List<String> keys = personalData == null ? List.of()
                : tx.inTransaction(c -> personalData.correlationKeysOf(c, tenantId, item.principalRef()));
        ErasureOutcome out = erasure.erase(tenantId, item.rootId(), keys, "system:retention",
                "retention: purpose " + item.purposeCode() + (item.withdrawn() ? " (consent withdrawn)" : " served"));
        return tx.inTransaction(c -> {
            Instant now = Sql.now(c, dialect);
            State s = switch (out.status()) {
                case ERASED -> State.ERASED;
                case REFUSED_LEGAL_HOLD -> State.HELD;
                case FAILED -> State.ERROR;
            };
            if (s == State.ERASED) {
                // the whole root tree is scrubbed: every item on it is erased
                Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET state=?, last_error=NULL, updated_at=? WHERE tenant_id=? AND root_id=?"
                        + " AND state IN ('ERASING','SCHEDULED','NOTIFIED','ERROR','TRACKING')", s, now, tenantId, item.rootId());
            } else {
                Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET state=?, last_error=?, updated_at=? WHERE tenant_id=? AND item_id=?",
                        s, out.status() == ErasureStatus.FAILED ? out.detail() : null, now, tenantId, id);
            }
            if (s == State.ERASED) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("item", id);
                ev.put("purpose", item.purposeCode());
                ev.put("withdrawn", item.withdrawn());
                ev.put("scrubbed", out.scrubbed() == null ? Map.of() : new LinkedHashMap<>(out.scrubbed()));
                audit.principal(c, tenantId, item.principalRef(), ComplianceAudit.RETENTION_ERASED, ev);
            }
            return s == State.ERASED ? "ERASED" : s == State.HELD ? "HELD" : "FAILED";
        });
    }

    // ---------------------------------------------------------------- queries

    public Optional<Item> find(Connection c, String tenantId, String rootId, String purposeCode) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND root_id=? AND purpose_code=?", RetentionScheduler::map, tenantId, rootId, purposeCode);
    }

    public Item get(Connection c, String tenantId, String itemId) {
        return Jdbc.one(c, SELECT + " WHERE tenant_id=? AND item_id=?", RetentionScheduler::map, tenantId, itemId)
                .orElseThrow(() -> ComplianceException.notFound("retention item", itemId));
    }

    public List<Item> forPrincipal(Connection c, String tenantId, String principalRef) {
        return Jdbc.query(c, SELECT + " WHERE tenant_id=? AND principal_ref=? ORDER BY item_id", RetentionScheduler::map, tenantId, principalRef);
    }

    public Map<String, Long> stateCounts(Connection c, String tenantId) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (State s : State.values()) {
            m.put(s.name(), 0L);
        }
        Jdbc.query(c, "SELECT state, COUNT(*) FROM CMP_RETENTION_ITEM WHERE tenant_id=? GROUP BY state",
                rs -> m.put(rs.getString(1), rs.getLong(2)), tenantId);
        return m;
    }

    /** Due items that are not processed for more than a day (scheduler not running): a report finding. */
    public long overdue(Connection c, String tenantId) {
        Instant dayAgo = Sql.now(c, dialect).minus(Duration.ofDays(1));
        return Jdbc.one(c, "SELECT COUNT(*) FROM CMP_RETENTION_ITEM WHERE tenant_id=? AND state IN ('SCHEDULED','NOTIFIED','ERROR')"
                + " AND erase_due_at<?", rs -> rs.getLong(1), tenantId, dayAgo).orElse(0L);
    }

    // ---------------------------------------------------------------- internals

    private void recompute(Connection c, String tenantId, String itemId) {
        Item i = get(c, tenantId, itemId);
        ComplianceConfig cfg = config.forTenant(tenantId);
        PurposeDefinition p = purposes.require(c, tenantId, i.purposeCode());
        RetentionSchedule s = p.retentionSchedule(cfg.pack);
        if (i.withdrawn()) {
            s = s.withdrawn();
        }
        Instant due = s.eraseAt(i.anchorAt(), i.lastProcessedAt(), i.lastApproachAt(), i.recordCreatedAt(),
                Duration.ofDays(cfg.minimumRetentionDays));
        Instant noticeAt = due == null || !s.preErasureNotice() || cfg.preErasureNoticeHours <= 0 ? null : due.minus(Duration.ofHours(cfg.preErasureNoticeHours));
        State st = due == null ? State.TRACKING : (i.state() == State.NOTIFIED ? State.NOTIFIED : State.SCHEDULED);
        if (i.state() == State.HELD || i.state() == State.ERROR) {
            st = i.state();
        }
        Jdbc.update(c, "UPDATE CMP_RETENTION_ITEM SET erase_due_at=?, notice_due_at=?, state=?, updated_at=" + dialect.now()
                + " WHERE tenant_id=? AND item_id=?", due, noticeAt, st, tenantId, itemId);
    }

    private List<String> ids(Connection c, String where, Object... params) {
        return Jdbc.query(c, "SELECT item_id FROM CMP_RETENTION_ITEM WHERE " + where + " ORDER BY item_id", rs -> rs.getString(1), params);
    }

    private static final String SELECT = "SELECT item_id, root_id, principal_ref, purpose_code, state, anchor_at, last_processed_at,"
            + " last_approach_at, record_created_at, erase_due_at, notice_due_at, notice_sent_at, withdrawn, attempts FROM CMP_RETENTION_ITEM";

    private static Item map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), State.valueOf(rs.getString(5)),
                Jdbc.instant(rs, "anchor_at"), Jdbc.instant(rs, "last_processed_at"), Jdbc.instant(rs, "last_approach_at"),
                Jdbc.instant(rs, "record_created_at"), Jdbc.instant(rs, "erase_due_at"), Jdbc.instant(rs, "notice_due_at"),
                Jdbc.instant(rs, "notice_sent_at"), Sql.bool(rs.getInt(13)), rs.getInt(14));
    }

    static List<String> copy(List<String> l) {
        return new ArrayList<>(l);
    }
}
