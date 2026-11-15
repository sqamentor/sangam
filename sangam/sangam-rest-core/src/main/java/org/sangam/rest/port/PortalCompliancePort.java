// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

/**
 * The citizen portal's view of the data-protection module (sangam-compliance), bound by the host when that module is
 * installed with a jurisdiction pack. The portal never keeps a legal rule of its own: the notice, the purposes, the age of
 * majority, the guardian verification, the grievance period and the breach notice all come from the module and its pack.
 * <ul>
 *   <li>{@link #notice}: the current published version of a consent notice with its purposes by name (owner decisions
 *       339, 341) and the pack's age of majority (decision 343);</li>
 *   <li>{@link #recordConsent}: records the applicant's consent against that notice in the caller's transaction, refusing
 *       a notice that is not the current published one ({@code NOTICE_NOT_CURRENT}), a submission without consent
 *       ({@code CONSENT_REQUIRED}), a missing age declaration ({@code AGE_DECLARATION_REQUIRED}) and a declared child
 *       without a valid verification of the parent's verifiable consent ({@code GUARDIAN_CONSENT_REQUIRED}, decision 343);</li>
 *   <li>{@link #grievanceDueDays}: the published grievance period of the jurisdiction pack (decision 350);</li>
 *   <li>{@link #notifications}: the breach notices sent to the caller, each with the items the pack requires (IN: DPDP
 *       Rules r.7(1)(a)-(e), decision 347).</li>
 * </ul>
 */
public interface PortalCompliancePort {

    /** A purpose of a notice, named in the notice's language. */
    record NoticePurpose(String code, String name, String description) {
    }

    /**
     * The current published version of a notice: its text and HTML as published, the SHA-256 of the text, its purposes in
     * notice order and the pack's age of majority ({@code childAgeYears}, null when the pack sets none).
     */
    record PortalNotice(String noticeKey, int version, String language, String text, String html, String contentHash,
                        List<NoticePurpose> purposes, Integer childAgeYears) {
    }

    /** One item of a notice to a data principal, in pack order: the pack's key, label and citation, and the text sent. */
    record NoticeItem(String key, String label, String citation, String text) {
    }

    /** A notice in the data principal's portal account ({@code kind} BREACH_NOTICE: {@code reference} is the breach id). */
    record PortalNotification(String id, String kind, String reference, Instant at, String subject, List<NoticeItem> items) {
    }

    PortalNotice notice(RequestContext ctx, String noticeKey, String language);

    /**
     * Records the caller's consent on {@code serviceNoticeKey} for a submission to {@code serviceKey}, in the caller's
     * transaction, so it commits or rolls back with the application.
     */
    PortalPort.ConsentRecord recordConsent(RequestContext ctx, String serviceKey, String serviceNoticeKey,
                                           PortalPort.PortalConsent consent);

    /** The grievance period in days of the tenant's jurisdiction pack; empty when the tenant has no jurisdiction. */
    OptionalInt grievanceDueDays(RequestContext ctx);

    /** The notices sent to the caller, newest first. */
    List<PortalNotification> notifications(RequestContext ctx);
}
