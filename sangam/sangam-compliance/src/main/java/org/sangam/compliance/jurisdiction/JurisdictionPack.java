// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.jurisdiction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A jurisdiction pack: everything that is law-specific, as data. The Java framework only knows the neutral concepts
 * (consent, purposes, lawful bases, data-subject rights, retention, breach and incident reporting, localisation,
 * accountability evidence); the pack supplies the legal bases, exemptions, rights and their time limits, authorities
 * and their report items and deadlines, notice languages and templates, retention classes, contact roles, periodic
 * evidence, hosting categories, national identifier formats, the commencement calendar and the obligation catalogue.
 *
 * <p>A pack is a JSON document ({@code compliance/jurisdictions/<CODE>/pack.json}) listed in
 * {@code META-INF/sangam/jurisdictions.idx}; see {@link JurisdictionRegistry}. India (DPDP Act 2023 + Rules 2025,
 * CERT-In Directions 2022, IT Act / SPDI Rules) is the first pack, {@code IN}. A further jurisdiction (e.g. EU GDPR,
 * US HIPAA) is a further pack: no code change.
 */
public final class JurisdictionPack {

    // ---------------------------------------------------------------- model

    public record Language(String code, String name, String nativeName, String script, boolean rtl) {
        public Locale locale(String country) {
            return Locale.forLanguageTag(code + "-" + country);
        }
    }

    public record Instrument(String id, String title, String issuer, String reference, String url) {
    }

    public record Phase(String id, LocalDate from, List<String> provisions) {
    }

    /** A lawful basis / ground. {@code kind} is {@code CONSENT} or {@code OTHER}. */
    public record LegalBasis(String code, String citation, String kind, boolean requiresLegalRef, List<String> subGrounds,
                             String subGroundRequiringRegisterRef, boolean requiresStandardsAttestation, boolean objectable,
                             boolean impliesRetentionByLaw, String description) {
        public boolean consent() {
            return "CONSENT".equals(kind);
        }
    }

    public record Exemption(String code, String citation, boolean disappliesNoticeAndConsent, boolean disappliesErasure,
                            boolean disappliesCorrectionWithoutDecision, boolean requiresStandardsAttestation, String description) {
    }

    /** Exemption from child safeguards; {@code covers} lists the {@code ProcessingFlag} names it permits. */
    public record ChildExemption(String code, String citation, Set<String> covers, boolean exemptsGuardianConsent, String description) {
    }

    public record VerificationMethod(String code, String citation, String description) {
    }

    /** A data-subject right. {@code category} is a neutral {@code RightCategory} name. */
    public record Right(String code, String category, String citation, String processKey, String slaKey, int defaultDays,
                        int maxDays) {
    }

    public record RetentionClass(String code, String citation, int periodDays, String floorFromPhase, String description) {
    }

    /** An item a report or notice must contain. */
    public record ReportItem(String key, String citation, String label) {
    }

    public record IncidentType(String code, String label, boolean personalDataLikely) {
    }

    /** An authority the deployer reports to or receives requests from. */
    public record Authority(String code, String role, String name, String citation, List<String> recipients, int deadlineHours,
                            int warnHours, List<IncidentType> catalogue, List<ReportItem> formItems) {
        public Optional<IncidentType> incidentType(String code) {
            return catalogue.stream().filter(t -> t.code().equals(code)).findFirst();
        }
    }

    public record BreachRules(String authority, String initialTiming, List<ReportItem> initialItems, int detailedReportHours,
                              List<ReportItem> detailedItems, String subjectTiming, List<ReportItem> subjectItems,
                              String linkedIncidentAuthority, String linkedIncidentType, String citation) {
    }

    public record ContactRole(String code, String citation, boolean mustBeResident, List<String> requiredFields, boolean published,
                              String description) {
    }

    public record EvidenceKind(String code, String citation, int months, boolean elevatedTierOnly, boolean requiresIndependent,
                               boolean requiresAccreditationRef, String description) {
    }

    public record HostingCategory(String code, boolean acceptableForPublicSector, String citation) {
    }

    public record NationalIdentifier(String code, String regex, String checksum, int keepLast, String citation) {
    }

    public record LawfulRequestBasis(String code, String citation, int maxHours, boolean withheldFromAccess, String recipientKind,
                                     boolean requiresRankAttestation, String rankRequirement) {
    }

    /** A security / good-practice checklist item, evaluated from deployment facts. */
    public record ChecklistItem(String id, String citation, String description, String fact, String expected, String activity,
                                int maxAgeMonths) {
    }

    /** A limit that changes when an entity attribute applies, until a date. */
    public record ConditionalLimit(String key, int value, String whenAttribute, LocalDate until, String citation) {
    }

    // ---------------------------------------------------------------- data

    private final String code;
    private final String name;
    private final String homeCountry;
    private final ZoneId timeZone;
    private final Map<String, String> terminology;
    private final List<Instrument> instruments;
    private final List<Phase> phases;
    private final String defaultPhase;
    private final List<Language> languages;
    private final String defaultLanguage;
    private final Map<String, Integer> limits;
    private final Map<String, String> limitCitations;
    private final List<ConditionalLimit> conditionalLimits;
    private final Map<String, String> defaults;
    private final List<LegalBasis> legalBases;
    private final List<Exemption> exemptions;
    private final List<ChildExemption> childExemptions;
    private final List<VerificationMethod> childVerification;
    private final List<VerificationMethod> guardianVerification;
    private final List<Right> rights;
    private final List<RetentionClass> retentionClasses;
    private final List<Authority> authorities;
    private final BreachRules breach;
    private final List<ContactRole> contactRoles;
    private final String elevatedTierName;
    private final int impactAssessmentMonths;
    private final List<EvidenceKind> evidenceKinds;
    private final List<HostingCategory> hostingCategories;
    private final List<String> localisedCategories;
    private final List<NationalIdentifier> nationalIdentifiers;
    private final List<LawfulRequestBasis> lawfulRequestBases;
    private final List<ChecklistItem> checklist;
    private final List<String> noticeLint;
    private final String noticeTemplatePath;
    private final String obligationsPath;

    private JurisdictionPack(JsonNode n) {
        code = req(n, "code").toUpperCase(Locale.ROOT);
        name = req(n, "name");
        homeCountry = n.path("homeCountry").asText(code).toUpperCase(Locale.ROOT);
        timeZone = ZoneId.of(n.path("timeZone").asText("UTC"));
        terminology = strings(n.path("terminology"));
        instruments = list(n.path("instruments"), x -> new Instrument(x.path("id").asText(), x.path("title").asText(),
                x.path("issuer").asText(), x.path("reference").asText(), x.path("url").asText()));
        JsonNode c = n.path("commencement");
        phases = list(c.path("phases"), x -> new Phase(x.path("id").asText(), LocalDate.parse(x.path("from").asText()),
                texts(x.path("provisions"))));
        defaultPhase = c.path("defaultPhase").asText(phases.isEmpty() ? null : phases.get(phases.size() - 1).id());
        languages = list(n.path("languages"), x -> new Language(x.path("code").asText(), x.path("name").asText(),
                x.path("nativeName").asText(x.path("name").asText()), x.path("script").asText("Latn"), x.path("rtl").asBoolean(false)));
        defaultLanguage = n.path("defaultLanguage").asText(languages.isEmpty() ? "en" : languages.get(0).code());
        limits = new LinkedHashMap<>();
        limitCitations = new LinkedHashMap<>();
        n.path("limits").fields().forEachRemaining(e -> {
            limits.put(e.getKey(), e.getValue().path("value").asInt(e.getValue().asInt()));
            limitCitations.put(e.getKey(), e.getValue().path("citation").asText(""));
        });
        conditionalLimits = list(n.path("conditionalLimits"), x -> new ConditionalLimit(x.path("key").asText(),
                x.path("value").asInt(), x.path("when").asText(), x.hasNonNull("until") ? LocalDate.parse(x.path("until").asText()) : null,
                x.path("citation").asText("")));
        defaults = strings(n.path("defaults"));
        legalBases = list(n.path("legalBases"), x -> new LegalBasis(x.path("code").asText(), x.path("citation").asText(),
                x.path("kind").asText("OTHER"), x.path("requiresLegalRef").asBoolean(false), texts(x.path("subGrounds")),
                x.path("subGroundRequiringRegisterRef").asText(null), x.path("requiresStandardsAttestation").asBoolean(false),
                x.path("objectable").asBoolean(false), x.path("impliesRetentionByLaw").asBoolean(false), x.path("description").asText("")));
        exemptions = list(n.path("exemptions"), x -> new Exemption(x.path("code").asText(), x.path("citation").asText(),
                x.path("disappliesNoticeAndConsent").asBoolean(false), x.path("disappliesErasure").asBoolean(false),
                x.path("disappliesCorrectionWithoutDecision").asBoolean(false), x.path("requiresStandardsAttestation").asBoolean(false),
                x.path("description").asText("")));
        childExemptions = list(n.path("childExemptions"), x -> new ChildExemption(x.path("code").asText(), x.path("citation").asText(),
                new LinkedHashSet<>(texts(x.path("covers"))), x.path("exemptsGuardianConsent").asBoolean(true), x.path("description").asText("")));
        childVerification = list(n.path("verification").path("child"), JurisdictionPack::method);
        guardianVerification = list(n.path("verification").path("guardian"), JurisdictionPack::method);
        rights = list(n.path("rights"), x -> new Right(x.path("code").asText(), x.path("category").asText(), x.path("citation").asText(),
                x.path("processKey").asText(), x.path("slaKey").asText(x.path("code").asText().toLowerCase(Locale.ROOT)),
                x.path("defaultDays").asInt(30), x.path("maxDays").asInt(Integer.MAX_VALUE)));
        retentionClasses = list(n.path("retentionClasses"), x -> new RetentionClass(x.path("code").asText(), x.path("citation").asText(),
                x.path("periodDays").asInt(), x.path("floorFromPhase").asText(null), x.path("description").asText("")));
        authorities = list(n.path("authorities"), x -> new Authority(x.path("code").asText(), x.path("role").asText(),
                x.path("name").asText(), x.path("citation").asText(""), texts(x.path("recipients")), x.path("deadlineHours").asInt(0),
                x.path("warnHours").asInt(0), list(x.path("catalogue"), t -> new IncidentType(t.path("code").asText(),
                t.path("label").asText(), t.path("personalDataLikely").asBoolean(false))), items(x.path("formItems"))));
        JsonNode b = n.path("breach");
        breach = new BreachRules(b.path("authority").asText(null), b.path("initialTiming").asText("WITHOUT_DELAY"),
                items(b.path("initialItems")), b.path("detailedReportHours").asInt(0), items(b.path("detailedItems")),
                b.path("subjectTiming").asText("WITHOUT_DELAY"), items(b.path("subjectItems")),
                b.path("linkedIncidentAuthority").asText(null), b.path("linkedIncidentType").asText(null), b.path("citation").asText(""));
        contactRoles = list(n.path("contactRoles"), x -> new ContactRole(x.path("code").asText(), x.path("citation").asText(""),
                x.path("mustBeResident").asBoolean(false), texts(x.path("requiredFields")), x.path("published").asBoolean(false),
                x.path("description").asText("")));
        JsonNode acc = n.path("accountability");
        elevatedTierName = acc.path("elevatedTierName").asText("elevated tier");
        impactAssessmentMonths = acc.path("impactAssessmentMonths").asInt(12);
        evidenceKinds = list(acc.path("evidence"), x -> new EvidenceKind(x.path("code").asText(), x.path("citation").asText(""),
                x.path("months").asInt(12), x.path("elevatedTierOnly").asBoolean(false), x.path("requiresIndependent").asBoolean(false),
                x.path("requiresAccreditationRef").asBoolean(false), x.path("description").asText("")));
        JsonNode h = n.path("hosting");
        hostingCategories = list(h.path("categories"), x -> new HostingCategory(x.path("code").asText(),
                x.path("acceptableForPublicSector").asBoolean(false), x.path("citation").asText("")));
        localisedCategories = texts(h.path("localisedCategories"));
        nationalIdentifiers = list(n.path("nationalIdentifiers"), x -> new NationalIdentifier(x.path("code").asText(),
                x.path("regex").asText(), x.path("checksum").asText("NONE"), x.path("keepLast").asInt(4), x.path("citation").asText("")));
        lawfulRequestBases = list(n.path("lawfulRequestBases"), x -> new LawfulRequestBasis(x.path("code").asText(),
                x.path("citation").asText(""), x.path("maxHours").asInt(0), x.path("withheldFromAccess").asBoolean(false),
                x.path("recipientKind").asText("STATE_AGENCY"), x.path("requiresRankAttestation").asBoolean(false),
                x.path("rankRequirement").asText("")));
        checklist = list(n.path("securityChecklist"), x -> new ChecklistItem(x.path("id").asText(), x.path("citation").asText(""),
                x.path("description").asText(""), x.path("fact").asText(null), x.path("expected").asText(null),
                x.path("activity").asText(null), x.path("maxAgeMonths").asInt(0)));
        noticeLint = texts(n.path("noticeLint"));
        noticeTemplatePath = n.path("noticeTemplates").asText("compliance/jurisdictions/" + code + "/notices");
        obligationsPath = n.path("obligations").asText(null);
    }

    public static JurisdictionPack parse(InputStream in) {
        try {
            return new JurisdictionPack(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- accessors

    public String code() { return code; }
    public String name() { return name; }
    public String homeCountry() { return homeCountry; }
    public ZoneId timeZone() { return timeZone; }
    public List<Instrument> instruments() { return instruments; }
    public List<Phase> phases() { return phases; }
    public List<Language> languages() { return languages; }
    public String defaultLanguage() { return defaultLanguage; }
    public Map<String, String> defaults() { return defaults; }
    public List<LegalBasis> legalBases() { return legalBases; }
    public List<Exemption> exemptions() { return exemptions; }
    public List<ChildExemption> childExemptions() { return childExemptions; }
    public List<VerificationMethod> childVerification() { return childVerification; }
    public List<VerificationMethod> guardianVerification() { return guardianVerification; }
    public List<Right> rights() { return rights; }
    public List<RetentionClass> retentionClasses() { return retentionClasses; }
    public List<Authority> authorities() { return authorities; }
    public BreachRules breach() { return breach; }
    public List<ContactRole> contactRoles() { return contactRoles; }
    public String elevatedTierName() { return elevatedTierName; }
    public int impactAssessmentMonths() { return impactAssessmentMonths; }
    public List<EvidenceKind> evidenceKinds() { return evidenceKinds; }
    public List<HostingCategory> hostingCategories() { return hostingCategories; }
    public List<String> localisedCategories() { return localisedCategories; }
    public List<NationalIdentifier> nationalIdentifiers() { return nationalIdentifiers; }
    public List<LawfulRequestBasis> lawfulRequestBases() { return lawfulRequestBases; }
    public List<ChecklistItem> checklist() { return checklist; }
    public List<String> noticeLint() { return noticeLint; }
    public String noticeTemplatePath() { return noticeTemplatePath; }
    public String obligationsPath() { return obligationsPath; }

    /** Pack vocabulary, e.g. {@code dataSubject} → "Data Principal"; falls back to the neutral term. */
    public String term(String key, String fallback) {
        return terminology.getOrDefault(key, fallback);
    }

    public Optional<Language> language(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        int dash = c.indexOf('-');
        String primary = dash > 0 ? c.substring(0, dash) : c;
        return languages.stream().filter(l -> l.code().equals(primary)).findFirst();
    }

    public Language requireLanguage(String code) {
        return language(code).orElseThrow(() -> new IllegalArgumentException("language " + code + " is not in jurisdiction pack " + this.code));
    }

    /** A numeric limit; absent means "no legal limit" ({@code fallback}). */
    public int limit(String key, int fallback) {
        return limits.getOrDefault(key, fallback);
    }

    public boolean hasLimit(String key) {
        return limits.containsKey(key);
    }

    public String limitCitation(String key) {
        return limitCitations.getOrDefault(key, "");
    }

    public Map<String, Integer> limits() {
        return Map.copyOf(limits);
    }

    /** The limit after conditional limits that apply to the entity's attributes at {@code at}. */
    public int effectiveLimit(String key, int fallback, Set<String> entityAttributes, Instant at) {
        int v = limit(key, fallback);
        for (ConditionalLimit cl : conditionalLimits) {
            if (cl.key().equals(key) && entityAttributes.contains(cl.whenAttribute())
                    && (cl.until() == null || at.isBefore(cl.until().atStartOfDay(timeZone).toInstant()))) {
                v = Math.min(v, cl.value());
            }
        }
        return v;
    }

    public List<ConditionalLimit> conditionalLimits() {
        return conditionalLimits;
    }

    public Optional<LegalBasis> legalBasis(String code) {
        return legalBases.stream().filter(b -> b.code().equals(code)).findFirst();
    }

    public Optional<Exemption> exemption(String code) {
        return exemptions.stream().filter(b -> b.code().equals(code)).findFirst();
    }

    public Optional<ChildExemption> childExemption(String code) {
        return childExemptions.stream().filter(b -> b.code().equals(code)).findFirst();
    }

    public Optional<Right> right(String code) {
        return rights.stream().filter(r -> r.code().equals(code)).findFirst();
    }

    public Optional<RetentionClass> retentionClass(String code) {
        return retentionClasses.stream().filter(r -> r.code().equals(code)).findFirst();
    }

    public Optional<Authority> authority(String code) {
        return authorities.stream().filter(a -> a.code().equals(code)).findFirst();
    }

    public List<Authority> authoritiesWithRole(String role) {
        return authorities.stream().filter(a -> a.role().equals(role)).toList();
    }

    public Optional<ContactRole> contactRole(String code) {
        return contactRoles.stream().filter(r -> r.code().equals(code)).findFirst();
    }

    public Optional<EvidenceKind> evidenceKind(String code) {
        return evidenceKinds.stream().filter(r -> r.code().equals(code)).findFirst();
    }

    public Optional<HostingCategory> hostingCategory(String code) {
        return hostingCategories.stream().filter(r -> r.code().equals(code)).findFirst();
    }

    public Optional<LawfulRequestBasis> lawfulRequestBasis(String code) {
        return lawfulRequestBases.stream().filter(r -> r.code().equals(code)).findFirst();
    }

    public Optional<Phase> phase(String id) {
        return phases.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public Instant phaseStart(String id) {
        return phase(id).map(p -> p.from().atStartOfDay(timeZone).toInstant()).orElse(null);
    }

    /**
     * Date from which a provision applies: the phase whose provision list matches the citation (exact, or as a prefix
     * followed by "(" / " "), else the default phase.
     */
    public LocalDate inForceFrom(String citation) {
        String s = citation == null ? "" : citation.trim().toLowerCase(Locale.ROOT);
        Phase best = null;
        int bestLen = -1;
        for (Phase p : phases) {
            for (String prov : p.provisions()) {
                String q = prov.toLowerCase(Locale.ROOT);
                boolean match = s.equals(q) || s.startsWith(q) && s.length() > q.length()
                        && (s.charAt(q.length()) == '(' || s.charAt(q.length()) == ' ' || s.charAt(q.length()) == ',');
                if (match && q.length() > bestLen) {
                    best = p;
                    bestLen = q.length();
                }
            }
        }
        if (best == null) {
            best = phase(defaultPhase).orElse(phases.stream().max(Comparator.comparing(Phase::from)).orElse(null));
        }
        return best == null ? null : best.from();
    }

    public boolean inForce(String citation, Instant at) {
        LocalDate d = inForceFrom(citation);
        return d == null || !at.isBefore(d.atStartOfDay(timeZone).toInstant());
    }

    // ---------------------------------------------------------------- parsing helpers

    private interface Mapper<T> {
        T map(JsonNode n);
    }

    private static <T> List<T> list(JsonNode arr, Mapper<T> m) {
        List<T> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(x -> out.add(m.map(x)));
        }
        return List.copyOf(out);
    }

    private static List<String> texts(JsonNode arr) {
        return list(arr, JsonNode::asText);
    }

    private static List<ReportItem> items(JsonNode arr) {
        return list(arr, x -> new ReportItem(x.path("key").asText(), x.path("citation").asText(""), x.path("label").asText(x.path("key").asText())));
    }

    private static VerificationMethod method(JsonNode x) {
        return new VerificationMethod(x.path("code").asText(), x.path("citation").asText(""), x.path("description").asText(""));
    }

    private static Map<String, String> strings(JsonNode obj) {
        Map<String, String> m = new LinkedHashMap<>();
        if (obj != null && obj.isObject()) {
            obj.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
        }
        return Map.copyOf(m);
    }

    private static String req(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("jurisdiction pack: '" + field + "' is required");
        }
        return v;
    }
}
