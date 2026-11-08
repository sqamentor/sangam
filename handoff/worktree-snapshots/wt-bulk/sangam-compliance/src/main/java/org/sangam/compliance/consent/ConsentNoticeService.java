// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.accountability.ContactDirectory;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionPack.Language;
import org.sangam.compliance.lawful.LegitimateUseRegistry;
import org.sangam.compliance.lawful.ProcessingRegister;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.support.Html;
import org.sangam.compliance.support.Sql;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Notices (IN: DPDP s.5, s.6(3), r.3; CMP-002 … CMP-007, CMP-047 … CMP-049), the intimation for processing on a
 * non-consent basis (IN: Second Schedule (g), CMP-053), the privacy policy (IN: SPDI r.4, GIGW 3.0; CMP-120) and
 * notice deliveries, including notices for consents given before the law's commencement (IN: s.5(2), CMP-003).
 *
 * <p>A notice is published as an immutable, versioned, hashed text per language (table {@code CMP_NOTICE}); consents
 * point at the exact version and language shown, which is the proof of notice. Publishing validates every enabled
 * language of the pack and refuses a missing itemised data list, a missing description, a missing withdrawal / rights /
 * authority-complaint link, a missing published contact, and any clause the pack's lint list flags.
 */
public final class ConsentNoticeService {

    public enum DeliveryKind { CONSENT_REQUEST, LEGACY_NOTICE, NON_CONSENT_INTIMATION, PRIVACY_POLICY }

    public record RenderedNotice(String noticeKey, int version, Language language, Set<String> purposeCodes, String text, String html,
                                 String contentHash, boolean fallback) {
    }

    public record PublishResult(String noticeKey, int version, List<String> published, Map<String, String> missing) {
    }

    private final Dialect dialect;
    private final ComplianceAudit audit;
    private final ComplianceConfig.Provider config;
    private final NoticeTemplate.Catalogues templates;
    private final LegitimateUseRegistry purposes;
    private final ContactDirectory contacts;
    private final ProcessingRegister register;

    public ConsentNoticeService(Dialect dialect, ComplianceAudit audit, ComplianceConfig.Provider config,
                                NoticeTemplate.Catalogues templates, LegitimateUseRegistry purposes, ContactDirectory contacts,
                                ProcessingRegister register) {
        this.dialect = dialect;
        this.audit = audit;
        this.config = config;
        this.templates = templates;
        this.purposes = purposes;
        this.contacts = contacts;
        this.register = register;
    }

    public NoticeTemplate.Catalogue catalogue(String tenantId) {
        return templates.of(config.forTenant(tenantId).pack);
    }

    public PublishResult publish(Connection c, String tenantId, String noticeKey, Set<String> purposeCodes, String actor) {
        Sql.requireText(noticeKey, "noticeKey");
        if (purposeCodes == null || purposeCodes.isEmpty()) {
            throw ComplianceException.invalid("a notice needs at least one purpose");
        }
        ComplianceConfig cfg = config.forTenant(tenantId);
        List<String> linkGaps = new ArrayList<>();
        if (cfg.withdrawConsentUrl.isBlank()) linkGaps.add("withdraw-consent link");
        if (cfg.rightsPortalUrl.isBlank()) linkGaps.add("rights link");
        if (cfg.authorityComplaintUrl.isBlank()) linkGaps.add("authority complaint link");
        if (!linkGaps.isEmpty()) {
            throw new ComplianceException(Code.NOTICE_INVALID, "missing " + String.join(", ", linkGaps));
        }
        ContactDirectory.Contact contact = contacts.publishedContact(c, tenantId).orElseThrow(() ->
                new ComplianceException(Code.NOTICE_INVALID, "no published contact designated"));
        List<PurposeDefinition> defs = new ArrayList<>();
        for (String code : new TreeSet<>(purposeCodes)) {
            PurposeDefinition d = purposes.require(c, tenantId, code);
            if (!d.active()) {
                throw new ComplianceException(Code.NOTICE_INVALID, "purpose " + code + " is inactive");
            }
            defs.add(d);
        }
        int version = Jdbc.one(c, "SELECT MAX(version) FROM CMP_NOTICE WHERE tenant_id=? AND notice_key=?",
                rs -> rs.getInt(1), tenantId, noticeKey).orElse(0) + 1;
        NoticeTemplate.Catalogue cat = templates.of(cfg.pack);
        Map<String, String> missing = new LinkedHashMap<>();
        List<RenderedNotice> rendered = new ArrayList<>();
        for (Language l : cfg.enabledLanguages) {
            Optional<String> why = renderable(cat, l, defs, cfg);
            if (why.isPresent()) {
                missing.put(l.code(), why.get());
            } else {
                rendered.add(render(noticeKey, version, cat.get(l.code()).orElseThrow(), defs, cfg, contact));
            }
        }
        if (rendered.stream().noneMatch(r -> r.language().code().equals(cfg.defaultLanguage.code()))) {
            throw new ComplianceException(Code.NOTICE_INVALID, "the notice cannot be rendered in the default language "
                    + cfg.defaultLanguage.code() + ": " + missing.getOrDefault(cfg.defaultLanguage.code(), "not enabled"));
        }
        Instant now = Sql.now(c, dialect);
        List<String> published = new ArrayList<>();
        for (RenderedNotice r : rendered) {
            Jdbc.update(c, "INSERT INTO CMP_NOTICE(tenant_id, notice_key, version, language, purposes_json, body_text, body_html,"
                            + " content_hash, published_by, published_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    tenantId, noticeKey, version, r.language().code(), Sql.jsonList(r.purposeCodes()), r.text(), r.html(),
                    r.contentHash(), actor, now);
            published.add(r.language().code());
        }
        audit.organisation(c, tenantId, ComplianceAudit.NOTICE_PUBLISHED, Sql.kv("notice", noticeKey, "version", version,
                "purposes", new ArrayList<>(new TreeSet<>(purposeCodes)), "languages", published,
                "missingLanguages", new ArrayList<>(missing.keySet()),
                "hashes", rendered.stream().map(r -> r.language().code() + ":" + r.contentHash()).toList(), "actor", actor));
        return new PublishResult(noticeKey, version, published, missing);
    }

    public RenderedNotice get(Connection c, String tenantId, String noticeKey, Integer version, Language language) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        int v = version != null ? version : latestVersion(c, tenantId, noticeKey);
        Optional<RenderedNotice> r = load(c, tenantId, noticeKey, v, language, false);
        if (r.isPresent()) {
            return r.get();
        }
        if (cfg.allowLanguageFallback) {
            return load(c, tenantId, noticeKey, v, cfg.defaultLanguage, true)
                    .orElseThrow(() -> ComplianceException.notFound("notice", noticeKey + " v" + v));
        }
        throw new ComplianceException(Code.LANGUAGE_NOT_CONFIGURED, "notice " + noticeKey + " v" + v + " is not available in "
                + language.code() + "; add a translation and republish");
    }

    public int latestVersion(Connection c, String tenantId, String noticeKey) {
        return Jdbc.one(c, "SELECT MAX(version) FROM CMP_NOTICE WHERE tenant_id=? AND notice_key=?", rs -> rs.getInt(1),
                tenantId, noticeKey).filter(v -> v > 0).orElseThrow(() -> ComplianceException.notFound("notice", noticeKey));
    }

    public String recordDelivery(Connection c, String tenantId, String principalRef, String noticeKey, int version, Language language,
                                 DeliveryKind kind, String channel, String deliveryRef) {
        String id = Sql.newId();
        Jdbc.update(c, "INSERT INTO CMP_NOTICE_DELIVERY(tenant_id, delivery_id, principal_ref, notice_key, version, language, kind,"
                        + " channel, delivery_ref, delivered_at) VALUES (?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                tenantId, id, principalRef, noticeKey, version, language.code(), kind, channel, deliveryRef);
        audit.principal(c, tenantId, principalRef, ComplianceAudit.NOTICE_SENT, Sql.kv("notice", noticeKey, "version", version,
                "language", language.code(), "kind", kind.name(), "channel", channel));
        return id;
    }

    /** Legacy consents (given before commencement) that have not yet received a notice. */
    public List<String> pendingLegacyNotices(Connection c, String tenantId, int limit) {
        List<String> all = Jdbc.query(c, "SELECT DISTINCT k.principal_ref FROM CMP_CONSENT k WHERE k.tenant_id=? AND k.legacy=1"
                + " AND k.status<>'WITHDRAWN' AND NOT EXISTS (SELECT 1 FROM CMP_NOTICE_DELIVERY d WHERE d.tenant_id=k.tenant_id"
                + " AND d.principal_ref=k.principal_ref AND d.kind='LEGACY_NOTICE') ORDER BY k.principal_ref", rs -> rs.getString(1), tenantId);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    /** Intimation to a data subject whose data is processed on a non-consent basis (IN: s.7(b), Second Schedule (g)). */
    public RenderedNotice nonConsentIntimation(Connection c, String tenantId, String purposeCode, Language language) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        PurposeDefinition d = purposes.require(c, tenantId, purposeCode);
        if (d.consentBased(cfg.pack)) {
            throw new ComplianceException(Code.INVALID_STATE, "an intimation applies to non-consent purposes; publish a notice instead");
        }
        NoticeTemplate t = template(cfg, language);
        ContactDirectory.Contact contact = contacts.publishedContact(c, tenantId).orElseThrow(() ->
                new ComplianceException(Code.NOTICE_INVALID, "no published contact designated"));
        String org = cfg.organisationName;
        String lang = t.language().code();
        StringBuilder txt = new StringBuilder();
        StringBuilder html = open(t.language());
        line(txt, html, "h1", t.label("intimation.title", org));
        line(txt, html, "p", t.label("intimation.intro", org));
        line(txt, html, "h2", d.name(lang) + " (" + citation(cfg.pack, d) + ")");
        line(txt, html, "p", d.description(lang));
        list(txt, html, d.dataItems().stream().map(i -> i.names().getOrDefault(lang, i.names().getOrDefault(cfg.pack.defaultLanguage(), i.code()))).toList());
        linkLine(txt, html, t.label("rights", org), cfg.rightsPortalUrl);
        linkLine(txt, html, t.label("complaint", org), cfg.authorityComplaintUrl);
        contactBlock(txt, html, t, org, contact);
        html.append("</section>");
        return new RenderedNotice("intimation:" + purposeCode, d.version(), t.language(), Set.of(purposeCode), txt.toString(),
                html.toString(), Sql.hashHex(txt.toString()), !t.language().code().equals(language.code()));
    }

    public RenderedNotice privacyPolicy(Connection c, String tenantId, Language language) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        NoticeTemplate t = template(cfg, language);
        String org = cfg.organisationName;
        String lang = t.language().code();
        StringBuilder txt = new StringBuilder();
        StringBuilder html = open(t.language());
        line(txt, html, "h1", t.label("policy.title", org));
        line(txt, html, "p", t.label("policy.intro", org));
        Set<String> codes = new LinkedHashSet<>();
        for (PurposeDefinition d : purposes.list(c, tenantId)) {
            if (!d.active()) {
                continue;
            }
            codes.add(d.code());
            line(txt, html, "h2", d.name(lang) + " (" + citation(cfg.pack, d) + ")");
            line(txt, html, "p", d.description(lang));
            list(txt, html, d.dataItems().stream().map(i -> i.names().getOrDefault(lang, i.names().getOrDefault(cfg.pack.defaultLanguage(), i.code()))
                    + (i.sensitive() ? " *" : "")).toList());
        }
        line(txt, html, "h2", t.label("policy.activities", org));
        list(txt, html, register.list(c, tenantId).stream().map(a -> a.activityId() + ": " + (a.description() == null ? "" : a.description())
                + (a.recipients().isEmpty() ? "" : " -> " + String.join(", ", a.recipients()))).toList());
        line(txt, html, "p", t.label("policy.security", org));
        linkLine(txt, html, t.label("withdraw", org), cfg.withdrawConsentUrl);
        linkLine(txt, html, t.label("rights", org), cfg.rightsPortalUrl);
        linkLine(txt, html, t.label("complaint", org), cfg.authorityComplaintUrl);
        for (JurisdictionPack.ContactRole r : cfg.pack.contactRoles()) {
            if (r.published()) {
                contacts.current(c, tenantId, r.code()).ifPresent(ct -> contactBlock(txt, html, t, org, ct));
            }
        }
        html.append("</section>");
        return new RenderedNotice("privacy-policy", 0, t.language(), codes, txt.toString(), html.toString(),
                Sql.hashHex(txt.toString()), !t.language().code().equals(language.code()));
    }

    /** Template gaps across enabled languages, for the evidence report. */
    public Map<String, String> languageGaps(String tenantId) {
        ComplianceConfig cfg = config.forTenant(tenantId);
        NoticeTemplate.Catalogue cat = templates.of(cfg.pack);
        Map<String, String> gaps = new LinkedHashMap<>();
        for (Language l : cfg.enabledLanguages) {
            Optional<NoticeTemplate> t = cat.get(l.code());
            if (t.isEmpty()) {
                gaps.put(l.code(), "no template");
            } else if (!t.get().missingKeys().isEmpty()) {
                gaps.put(l.code(), "template misses " + t.get().missingKeys());
            } else if (!t.get().lint(cfg.noticeLintPhrases).isEmpty()) {
                gaps.put(l.code(), "template contains invalid clauses " + t.get().lint(cfg.noticeLintPhrases));
            }
        }
        return gaps;
    }

    // ---------------------------------------------------------------- rendering

    private Optional<String> renderable(NoticeTemplate.Catalogue cat, Language l, List<PurposeDefinition> defs, ComplianceConfig cfg) {
        Optional<NoticeTemplate> t = cat.get(l.code());
        if (t.isEmpty()) {
            return Optional.of("no template");
        }
        if (!t.get().missingKeys().isEmpty()) {
            return Optional.of("template misses " + t.get().missingKeys());
        }
        List<String> lint = t.get().lint(cfg.noticeLintPhrases);
        if (!lint.isEmpty()) {
            return Optional.of("template contains invalid clauses: " + lint);
        }
        for (PurposeDefinition d : defs) {
            if (!d.names().containsKey(l.code())) {
                return Optional.of("purpose " + d.code() + " has no name in " + l.code());
            }
            if (d.consentBased(cfg.pack) && !d.descriptions().containsKey(l.code())) {
                return Optional.of("purpose " + d.code() + " has no description in " + l.code());
            }
            for (PurposeDefinition.DataItem i : d.dataItems()) {
                if (!i.names().containsKey(l.code())) {
                    return Optional.of("data item " + i.code() + " has no name in " + l.code());
                }
            }
            String text = (d.name(l.code()) + " " + d.description(l.code())).toLowerCase(Locale.ROOT);
            for (String p : cfg.noticeLintPhrases) {
                if (text.contains(p.toLowerCase(Locale.ROOT))) {
                    return Optional.of("purpose " + d.code() + " text contains an invalid clause: " + p);
                }
            }
        }
        return Optional.empty();
    }

    private RenderedNotice render(String noticeKey, int version, NoticeTemplate t, List<PurposeDefinition> defs, ComplianceConfig cfg,
                                  ContactDirectory.Contact contact) {
        String org = cfg.organisationName;
        String lang = t.language().code();
        StringBuilder txt = new StringBuilder();
        StringBuilder html = open(t.language());
        line(txt, html, "h1", t.label("title", org));
        line(txt, html, "p", t.label("intro", org));
        line(txt, html, "h2", t.label("data.heading", org));
        for (PurposeDefinition d : defs) {
            line(txt, html, "h3", d.name(lang));
            list(txt, html, d.dataItems().stream().map(i -> i.names().get(lang)).toList());
        }
        line(txt, html, "h2", t.label("purposes.heading", org));
        for (PurposeDefinition d : defs) {
            String basis = d.consentBased(cfg.pack) ? "" : " [" + citation(cfg.pack, d) + "]";
            line(txt, html, "p", d.name(lang) + basis + ": " + d.description(lang));
        }
        linkLine(txt, html, t.label("withdraw", org), cfg.withdrawConsentUrl);
        linkLine(txt, html, t.label("rights", org), cfg.rightsPortalUrl);
        linkLine(txt, html, t.label("complaint", org), cfg.authorityComplaintUrl);
        contactBlock(txt, html, t, org, contact);
        line(txt, html, "p", t.label("consent.action", org));
        html.append("</section>");
        Set<String> codes = new LinkedHashSet<>(defs.stream().map(PurposeDefinition::code).toList());
        return new RenderedNotice(noticeKey, version, t.language(), codes, txt.toString(), html.toString(), Sql.hashHex(txt.toString()), false);
    }

    private NoticeTemplate template(ComplianceConfig cfg, Language language) {
        NoticeTemplate.Catalogue cat = templates.of(cfg.pack);
        Optional<NoticeTemplate> t = cat.get(language.code()).filter(x -> x.missingKeys().isEmpty());
        if (t.isPresent()) {
            return t.get();
        }
        if (cfg.allowLanguageFallback) {
            return cat.get(cfg.defaultLanguage.code()).orElseThrow();
        }
        throw new ComplianceException(Code.LANGUAGE_NOT_CONFIGURED, "no complete notice template for " + language.code());
    }

    private static String citation(JurisdictionPack pack, PurposeDefinition d) {
        return pack.legalBasis(d.basis()).map(JurisdictionPack.LegalBasis::citation).orElse(d.basis());
    }

    private Optional<RenderedNotice> load(Connection c, String tenantId, String key, int version, Language l, boolean fallback) {
        return Jdbc.one(c, "SELECT purposes_json, body_text, body_html, content_hash FROM CMP_NOTICE WHERE tenant_id=? AND notice_key=?"
                        + " AND version=? AND language=?",
                rs -> new RenderedNotice(key, version, l, Sql.set(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), fallback),
                tenantId, key, version, l.code());
    }

    private static StringBuilder open(Language l) {
        return new StringBuilder("<section class=\"sangam-notice\" lang=\"" + Html.esc(l.code()) + "\"" + (l.rtl() ? " dir=\"rtl\"" : "") + ">");
    }

    private static void contactBlock(StringBuilder txt, StringBuilder html, NoticeTemplate t, String org, ContactDirectory.Contact ct) {
        line(txt, html, "h2", t.label("contact.heading", org));
        line(txt, html, "p", ct.name() + (ct.designation() == null ? "" : ", " + ct.designation()) + " - " + ct.email()
                + (ct.phone() == null || ct.phone().isBlank() ? "" : ", " + ct.phone()));
    }

    private static void line(StringBuilder txt, StringBuilder html, String tag, String text) {
        txt.append(text).append('\n');
        html.append('<').append(tag).append('>').append(Html.esc(text)).append("</").append(tag).append('>');
    }

    private static void linkLine(StringBuilder txt, StringBuilder html, String label, String url) {
        txt.append(label).append(' ').append(url).append('\n');
        html.append("<p>").append(Html.esc(label)).append(' ').append(Html.link(url)).append("</p>");
    }

    private static void list(StringBuilder txt, StringBuilder html, List<String> items) {
        html.append("<ul>");
        for (String i : items) {
            txt.append(" - ").append(i).append('\n');
            html.append("<li>").append(Html.esc(i)).append("</li>");
        }
        html.append("</ul>");
    }
}
