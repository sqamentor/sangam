// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.breach.BreachIncidentService;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.ChildDataGuard;
import org.sangam.compliance.consent.ConsentNoticeService;
import org.sangam.compliance.consent.ConsentService;
import org.sangam.compliance.consent.VerifiableParentalConsentService;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.ports.CompliancePorts.PrincipalCategory;
import org.sangam.core.engine.SangamClockHolder;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PortalCompliancePort;
import org.sangam.rest.port.PortalPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;

/**
 * The citizen portal's view of {@link SangamCompliance} ({@link PortalCompliancePort}), over the module's public
 * services only: the published notice ({@code ConsentNoticeService}), the consent ({@code ConsentService.give}), the
 * parent's verifiable consent ({@code VerifiableParentalConsentService.requireValid}), the child safeguards
 * ({@code ChildDataGuard}), the right SLAs of the jurisdiction pack ({@code ComplianceConfig}) and the breach notices
 * ({@code BreachIncidentService} subject-notice listener). Every refusal of the module keeps its stable code and status
 * ({@link ComplianceException} to {@link ApiProblem}).
 */
public final class PortalComplianceAdapter implements PortalCompliancePort {

    /** Age declarations of {@link PortalPort.PortalConsent#ageDeclaration()}. */
    public static final String ADULT = "ADULT";
    public static final String CHILD = "CHILD";

    /** REST_DOCUMENT kind of a notice in a principal's portal account (encrypted at rest, owner = the principal). */
    public static final String KIND_NOTICE = "portal:notice";

    /** The principal's portal account: where a notice to her is kept and read back. */
    public interface Inbox {
        void add(String tenantId, String principalRef, PortalNotification notification);

        List<PortalNotification> list(String tenantId, String principalRef);

        /** The REST document store ({@value #KIND_NOTICE}), written in the caller's transaction. */
        static Inbox documents(DocumentStore store) {
            Objects.requireNonNull(store, "store");
            return new Inbox() {
                @Override
                public void add(String tenantId, String principalRef, PortalNotification n) {
                    store.insert(tenantId, KIND_NOTICE, n.id(), n.reference(), n.kind(), principalRef, n.reference(), n,
                            "system");
                }

                @Override
                public List<PortalNotification> list(String tenantId, String principalRef) {
                    return store.all(tenantId, KIND_NOTICE, DocumentStore.Filter.owner(principalRef)).stream()
                            .map(d -> d.as(PortalNotification.class)).toList();
                }
            };
        }
    }

    private final SangamCompliance compliance;
    private final Inbox inbox;

    /** Registers the breach subject-notice listener: each notice sent to a principal lands in her portal account. */
    public PortalComplianceAdapter(SangamCompliance compliance, Inbox inbox) {
        this.compliance = Objects.requireNonNull(compliance, "compliance");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        compliance.breaches.addSubjectNoticeListener(this::breachNoticeSent);
    }

    private void breachNoticeSent(Connection c, String tenantId, String breachId, String principalRef, String deliveryRef,
                                  String subject, JurisdictionPack.Language language,
                                  List<BreachIncidentService.SubjectNoticeItem> items) {
        List<NoticeItem> out = items.stream().map(i -> new NoticeItem(i.key(), i.label(), i.citation(), i.text())).toList();
        inbox.add(tenantId, principalRef, new PortalNotification(DocumentStore.newId(), "BREACH_NOTICE", breachId,
                SangamClockHolder.now(), subject, out));
    }

    // ---------------------------------------------------------------- support

    private <T> T tx(Function<Connection, T> work) {
        try {
            return compliance.tx.inTransaction(work);
        } catch (ComplianceException e) {
            throw problem(e);
        }
    }

    static ApiProblem problem(ComplianceException e) {
        return new ApiProblem(e.httpStatus(), e.code().name(), e.getMessage());
    }

    private ComplianceConfig cfg(String tenantId) {
        try {
            return compliance.config.forTenant(tenantId);
        } catch (ComplianceException e) {
            throw problem(e);
        }
    }

    private static JurisdictionPack.Language language(ComplianceConfig cfg, String code) {
        if (code == null || code.isBlank()) {
            return cfg.defaultLanguage;
        }
        return cfg.pack.language(code.trim()).orElseThrow(() -> ApiProblem.unprocessable("LANGUAGE_NOT_CONFIGURED",
                "language " + code + " is not a language of jurisdiction pack " + cfg.pack.code()));
    }

    private static Integer childAgeYears(ComplianceConfig cfg) {
        return cfg.hasLimit(ComplianceConfig.CHILD_AGE) ? cfg.limit(ComplianceConfig.CHILD_AGE, 0) : null;
    }

    private static ApiProblem notCurrent(String detail) {
        return ApiProblem.conflict("NOTICE_NOT_CURRENT", detail);
    }

    // ---------------------------------------------------------------- notice

    @Override
    public PortalNotice notice(RequestContext ctx, String noticeKey, String language) {
        ComplianceConfig cfg = cfg(ctx.tenantId());
        JurisdictionPack.Language lang = language(cfg, language);
        return tx(c -> {
            ConsentNoticeService.RenderedNotice n = compliance.notices.get(c, ctx.tenantId(), noticeKey, null, lang);
            String code = n.language().code();
            List<NoticePurpose> purposes = new ArrayList<>();
            for (String p : new TreeSet<>(n.purposeCodes())) {
                PurposeDefinition d = compliance.purposes.require(c, ctx.tenantId(), p);
                purposes.add(new NoticePurpose(p, d.name(code), d.description(code)));
            }
            return new PortalNotice(n.noticeKey(), n.version(), code, n.text(), n.html(), n.contentHash(),
                    List.copyOf(purposes), childAgeYears(cfg));
        });
    }

    // ---------------------------------------------------------------- consent

    @Override
    public PortalPort.ConsentRecord recordConsent(RequestContext ctx, String serviceKey, String serviceNoticeKey,
                                                  PortalPort.PortalConsent consent) {
        Objects.requireNonNull(serviceNoticeKey, "serviceNoticeKey");
        if (consent == null) {
            throw ApiProblem.forbidden("CONSENT_REQUIRED", "service " + serviceKey + " takes the applicant's consent on notice "
                    + serviceNoticeKey + " with the submission; none was given");
        }
        if (!serviceNoticeKey.equals(consent.noticeKey())) {
            throw notCurrent("the consent was given on notice " + consent.noticeKey() + "; service " + serviceKey
                    + " takes consent on notice " + serviceNoticeKey);
        }
        if (consent.purposeCodes() == null || consent.purposeCodes().isEmpty()) {
            throw ApiProblem.forbidden("CONSENT_REQUIRED", "tick at least one purpose of notice " + serviceNoticeKey);
        }
        ComplianceConfig cfg = cfg(ctx.tenantId());
        String age = ageDeclaration(cfg, consent.ageDeclaration());
        JurisdictionPack.Language lang = language(cfg, consent.language());
        String tenant = ctx.tenantId();
        String principal = ctx.principalId();
        return tx(c -> {
            int current;
            try {
                current = compliance.notices.latestVersion(c, tenant, serviceNoticeKey);
            } catch (ComplianceException e) {
                if (e.code() == ComplianceException.Code.NOT_FOUND) {
                    throw notCurrent("notice " + serviceNoticeKey + " has no published version");
                }
                throw e;
            }
            if (consent.noticeVersion() == null || consent.noticeVersion() != current) {
                throw notCurrent("the consent was given on version " + consent.noticeVersion() + " of notice "
                        + serviceNoticeKey + "; the current published version is " + current);
            }
            String givenBy = principal;
            String verificationId = null;
            if (CHILD.equals(age)) {
                VerifiableParentalConsentService.Verification v = childConsent(c, cfg, tenant, principal, consent);
                if (v != null) {
                    givenBy = v.guardianRef();
                    verificationId = v.verificationId();
                }
            }
            ConsentService.Consent k = compliance.consents.give(c, tenant, new ConsentService.GiveConsent(principal,
                    serviceNoticeKey, current, lang, Set.copyOf(consent.purposeCodes()), true, "portal:" + serviceKey,
                    givenBy, verificationId, null, null));
            return new PortalPort.ConsentRecord(k.consentId(), age, verificationId);
        });
    }

    /** ADULT or CHILD; required whenever the pack sets an age of majority (owner decision 343). */
    private static String ageDeclaration(ComplianceConfig cfg, String declared) {
        String d = declared == null ? null : declared.trim().toUpperCase(Locale.ROOT);
        if (d == null || d.isEmpty()) {
            if (childAgeYears(cfg) != null) {
                throw ApiProblem.unprocessable("AGE_DECLARATION_REQUIRED", "declare whether the applicant is under "
                        + childAgeYears(cfg) + " (" + ADULT + " or " + CHILD + ")");
            }
            return null;
        }
        if (!d.equals(ADULT) && !d.equals(CHILD)) {
            throw ApiProblem.unprocessable("AGE_DECLARATION_REQUIRED", "ageDeclaration must be " + ADULT + " or " + CHILD);
        }
        return d;
    }

    /**
     * A declared child: every purpose passes the child safeguards, and unless every purpose is exempt from the
     * guardian-consent requirement by a pack child exemption, the consent needs a valid verification of the parent's
     * verifiable consent for this child (fail closed: {@code GUARDIAN_CONSENT_REQUIRED}).
     *
     * @return the verification, or null when every purpose is exempt
     */
    private VerifiableParentalConsentService.Verification childConsent(Connection c, ComplianceConfig cfg, String tenant,
                                                                        String principal, PortalPort.PortalConsent consent) {
        List<PurposeDefinition> defs = new ArrayList<>();
        for (String code : new TreeSet<>(consent.purposeCodes())) {
            PurposeDefinition d = compliance.purposes.require(c, tenant, code);
            compliance.children.check(c, cfg.pack, tenant, principal, PrincipalCategory.CHILD, d, Set.of());
            defs.add(d);
        }
        if (defs.stream().allMatch(d -> ChildDataGuard.guardianConsentExempt(cfg.pack, d))) {
            return null;
        }
        String vid = consent.guardianVerificationId();
        if (vid == null || vid.isBlank()) {
            throw new ComplianceException(ComplianceException.Code.GUARDIAN_CONSENT_REQUIRED,
                    "a child's consent needs the verifiable consent of the parent: give the guardian verification");
        }
        VerifiableParentalConsentService.Verification v = compliance.guardians.requireValid(c, tenant, principal, vid.trim());
        if (v.kind() != VerifiableParentalConsentService.Kind.CHILD) {
            throw new ComplianceException(ComplianceException.Code.GUARDIAN_CONSENT_REQUIRED,
                    "the verification is not a verification of the parent of a child");
        }
        return v;
    }

    // ---------------------------------------------------------------- grievance period

    @Override
    public OptionalInt grievanceDueDays(RequestContext ctx) {
        ComplianceConfig cfg;
        try {
            cfg = compliance.config.forTenant(ctx.tenantId());
        } catch (ComplianceException e) {
            if (e.code() == ComplianceException.Code.JURISDICTION_NOT_SET) {
                return OptionalInt.empty();
            }
            throw problem(e);
        }
        Instant now = SangamClockHolder.now();
        return cfg.pack.rights().stream().filter(r -> "GRIEVANCE".equals(r.category())).findFirst()
                .map(r -> OptionalInt.of(cfg.rightSlaDays(r.code(), now))).orElse(OptionalInt.empty());
    }

    // ---------------------------------------------------------------- notifications

    @Override
    public List<PortalNotification> notifications(RequestContext ctx) {
        return inbox.list(ctx.tenantId(), ctx.principalId()).stream()
                .sorted(Comparator.comparing(PortalNotification::at, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }
}
