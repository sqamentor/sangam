// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.config;

import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Per-tenant compliance configuration. Values resolve in this order: {@code sangam.compliance.*} property, the
 * jurisdiction pack's {@code defaults}, then a neutral fallback. Legal floors and ceilings come only from the pack's
 * {@code limits} (and {@code conditionalLimits}); {@link #validate()} reports any configured value that crosses them
 * and {@link #requireValid()} refuses to start. No jurisdiction-specific number or name is held in this class.
 *
 * <p>Neutral limit keys the framework reads from the pack:
 * {@value #LOG_MIN}, {@value #INCIDENT_LOG_MIN}, {@value #PRE_ERASURE_MIN}, {@value #MIN_RETENTION},
 * {@value #LAWFUL_REQUEST_MAX}, {@value #CHILD_AGE}; per right {@code right.<CODE>.maxDays} (conditional limits may
 * lower them for an entity attribute until a date).
 */
public final class ComplianceConfig {

    public static final String PREFIX = "sangam.compliance.";
    public static final String LOG_MIN = "logRetentionMinDays";
    public static final String INCIDENT_LOG_MIN = "incidentLogRetentionDays";
    public static final String PRE_ERASURE_MIN = "preErasureNoticeMinHours";
    public static final String MIN_RETENTION = "minimumRetentionDays";
    public static final String LAWFUL_REQUEST_MAX = "lawfulRequestMaxHours";
    public static final String CHILD_AGE = "childAgeYears";

    /** Entity attribute meaning a public authority / instrumentality of the State. */
    public static final String PUBLIC_SECTOR = "public-sector";

    @FunctionalInterface
    public interface Provider {
        ComplianceConfig forTenant(String tenantId);

        static Provider fixed(ComplianceConfig config) {
            return t -> config;
        }
    }

    public final JurisdictionPack pack;
    /** Sub-jurisdiction (US state, EU member state ...) from {@code region}; null when not set. */
    public final JurisdictionPack.Region region;
    public final String organisationName;
    public final String sector;
    public final Set<String> entityAttributes;
    public final Set<String> appliedExemptions;
    public final boolean elevatedTier;
    public final String elevatedTierDesignationRef;
    public final JurisdictionPack.Language defaultLanguage;
    public final List<JurisdictionPack.Language> enabledLanguages;
    public final boolean allowLanguageFallback;
    public final List<String> noticeLintPhrases;
    public final Map<String, Integer> rightSlaDays;
    public final int preErasureNoticeHours;
    public final int minimumRetentionDays;
    public final int legalHoldRecheckHours;
    public final int logRetentionDays;
    public final String logRegion;
    public final int breachReportHours;
    public final Map<String, List<String>> authorityRecipients;
    public final List<String> additionalIncidentRecipients;
    public final int lawfulRequestHours;
    public final boolean ntpEnabled;
    public final List<String> ntpServers;
    public final long ntpMaxOffsetMs;
    public final int ntpTimeoutMs;
    public final String hostingCategory;
    public final String hostingProvider;
    public final String accreditationRef;
    public final LocalDate accreditationValidUntil;
    public final String hostingCountry;
    public final String requiredHostingCategory;
    public final Set<String> negativeListCountries;
    public final Set<String> orderRestrictedCountries;
    public final boolean denyCrossBorderByDefault;
    public final Set<String> localisedDataCategories;
    public final Set<String> registeredConsentManagers;
    public final String rightsPortalUrl;
    public final String withdrawConsentUrl;
    public final String authorityComplaintUrl;
    public final String privacyPolicyUrl;

    private ComplianceConfig(JurisdictionPack pack, Function<String, String> props) {
        this.pack = pack;
        String regionCode = props.apply("region");
        this.region = regionCode == null || regionCode.isBlank() ? null : pack.region(regionCode).orElseThrow(() ->
                new IllegalArgumentException("region " + regionCode + " is not defined by pack " + pack.code()));
        JurisdictionPack.Region reg = this.region;
        Function<String, String> p = k -> {
            String v = props.apply(k);
            if (v == null && reg != null) {
                v = reg.defaults().get(k);
            }
            return v != null ? v : pack.defaults().get(k);
        };
        organisationName = str(p, "organisation-name", "");
        sector = str(p, "sector", "");
        entityAttributes = set(csv(str(p, "entity-attributes", "")), false);
        appliedExemptions = set(csv(str(p, "applied-exemptions", "")), false);
        for (String e : appliedExemptions) {
            if (pack.exemption(e).isEmpty()) {
                throw new IllegalArgumentException("applied-exemptions: " + e + " is not an exemption of pack " + pack.code());
            }
        }
        elevatedTier = bool(p, "elevated-tier", false);
        elevatedTierDesignationRef = str(p, "elevated-tier-ref", "");
        defaultLanguage = pack.requireLanguage(str(p, "default-language", pack.defaultLanguage()));
        String langs = str(p, "languages", "all");
        enabledLanguages = langs.equalsIgnoreCase("all") ? pack.languages()
                : csv(langs).stream().map(pack::requireLanguage).toList();
        allowLanguageFallback = bool(p, "allow-language-fallback", false);
        noticeLintPhrases = csvOr(p, "notice-lint-phrases", pack.noticeLint());
        Map<String, Integer> sla = new LinkedHashMap<>();
        for (JurisdictionPack.Right r : pack.rights()) {
            sla.put(r.code(), integer(p, "sla." + r.slaKey() + "-days", r.defaultDays()));
        }
        rightSlaDays = Collections.unmodifiableMap(sla);
        preErasureNoticeHours = integer(p, "retention.pre-erasure-notice-hours", limit(PRE_ERASURE_MIN, 0));
        minimumRetentionDays = integer(p, "retention.minimum-days", limit(MIN_RETENTION, 0));
        legalHoldRecheckHours = integer(p, "retention.legal-hold-recheck-hours", 24);
        logRetentionDays = integer(p, "logs.retention-days", Math.max(limit(LOG_MIN, 0), limit(INCIDENT_LOG_MIN, 0)));
        logRegion = str(p, "logs.region", pack.homeCountry()).toUpperCase(Locale.ROOT);
        breachReportHours = integer(p, "breach.authority-report-hours", pack.breach().detailedReportHours());
        Map<String, List<String>> rec = new LinkedHashMap<>();
        for (JurisdictionPack.Authority a : pack.authorities()) {
            rec.put(a.code(), csvOr(p, "authority." + a.code() + ".recipients", authority(a.code()).recipients()));
        }
        authorityRecipients = Collections.unmodifiableMap(rec);
        additionalIncidentRecipients = csvOr(p, "incident.additional-recipients", List.of());
        lawfulRequestHours = integer(p, "lawful-request.response-hours", limit(LAWFUL_REQUEST_MAX, 72));
        ntpEnabled = bool(p, "ntp.enabled", false);
        ntpServers = csvOr(p, "ntp.servers", List.of());
        ntpMaxOffsetMs = Long.parseLong(str(p, "ntp.max-offset-ms", "1000"));
        ntpTimeoutMs = integer(p, "ntp.timeout-ms", 3000);
        hostingCategory = str(p, "hosting.category", "NOT_DECLARED").toUpperCase(Locale.ROOT);
        hostingProvider = str(p, "hosting.provider", "");
        accreditationRef = str(p, "hosting.accreditation-ref", "");
        String until = str(p, "hosting.accreditation-valid-until", "");
        accreditationValidUntil = until.isBlank() ? null : LocalDate.parse(until);
        hostingCountry = str(p, "hosting.country", pack.homeCountry()).toUpperCase(Locale.ROOT);
        requiredHostingCategory = str(p, "hosting.required-category", "").toUpperCase(Locale.ROOT);
        negativeListCountries = set(csvOr(p, "transfer.negative-list", List.of()), true);
        orderRestrictedCountries = set(csvOr(p, "transfer.order-restricted", List.of()), true);
        denyCrossBorderByDefault = bool(p, "transfer.deny-by-default", entityAttributes.contains(PUBLIC_SECTOR));
        localisedDataCategories = set(csvOr(p, "transfer.localised-categories", pack.localisedCategories()), true);
        registeredConsentManagers = set(csvOr(p, "consent-managers.registered", List.of()), false);
        rightsPortalUrl = str(p, "links.rights-portal", "");
        withdrawConsentUrl = str(p, "links.withdraw-consent", "");
        authorityComplaintUrl = str(p, "links.authority-complaint", "");
        privacyPolicyUrl = str(p, "links.privacy-policy", "");
    }

    /**
     * Binds the tenant's configuration. {@code jurisdiction} names the pack; blank selects the only installed pack.
     */
    public static ComplianceConfig fromProperties(JurisdictionRegistry registry, Map<String, String> properties) {
        Map<String, String> props = properties == null ? Map.of() : properties;
        Function<String, String> f = k -> {
            String v = props.get(PREFIX + k);
            return v != null ? v : props.get(k);
        };
        return new ComplianceConfig(registry.require(f.apply("jurisdiction")), f);
    }

    public static ComplianceConfig of(JurisdictionPack pack, Map<String, String> properties) {
        Map<String, String> props = properties == null ? Map.of() : properties;
        return new ComplianceConfig(pack, k -> {
            String v = props.get(PREFIX + k);
            return v != null ? v : props.get(k);
        });
    }

    /** A pack limit, overridden by the region when the region defines it. */
    public int limit(String key, int fallback) {
        if (region != null && region.limits().containsKey(key)) {
            return region.limits().get(key);
        }
        return pack.limit(key, fallback);
    }

    public boolean hasLimit(String key) {
        return region != null && region.limits().containsKey(key) || pack.hasLimit(key);
    }

    /** An authority of the pack, replaced by the region's authority of the same code (e.g. a state attorney general). */
    public JurisdictionPack.Authority authority(String code) {
        if (region != null) {
            for (JurisdictionPack.Authority a : region.authorities()) {
                if (a.code().equals(code)) {
                    return a;
                }
            }
        }
        return pack.authority(code).orElseThrow(() -> new IllegalArgumentException("authority " + code + " is not defined by pack " + pack.code()));
    }

    /** Breach notification rules of the pack plus the region's additional rules. */
    public List<JurisdictionPack.BreachNotificationRule> breachRules() {
        List<JurisdictionPack.BreachNotificationRule> l = new ArrayList<>(pack.breachRules());
        if (region != null) {
            for (JurisdictionPack.BreachNotificationRule r : region.breachRules()) {
                l.removeIf(x -> x.code().equals(r.code()));
                l.add(r);
            }
        }
        return l;
    }

    public boolean has(String attribute) {
        return entityAttributes.contains(attribute);
    }

    public boolean publicSector() {
        return has(PUBLIC_SECTOR);
    }

    public boolean exemptionApplied(String code) {
        return appliedExemptions.contains(code);
    }

    /** Maximum days for a right at {@code at}, after conditional limits. */
    public int rightMaxDays(String rightCode, Instant at) {
        JurisdictionPack.Right r = pack.right(rightCode).orElseThrow(() -> new IllegalArgumentException("unknown right " + rightCode));
        int max = pack.effectiveLimit("right." + rightCode + ".maxDays", r.maxDays(), entityAttributes, at);
        return region != null && region.limits().containsKey("right." + rightCode + ".maxDays")
                ? Math.min(max, region.limits().get("right." + rightCode + ".maxDays")) : max;
    }

    /** Configured SLA, never above the effective legal maximum. */
    public int rightSlaDays(String rightCode, Instant at) {
        Integer d = rightSlaDays.get(rightCode);
        if (d == null) {
            throw new IllegalArgumentException("unknown right " + rightCode);
        }
        return Math.min(d, rightMaxDays(rightCode, at));
    }

    public int effectiveLogRetentionDays() {
        return Math.max(logRetentionDays, Math.max(limit(LOG_MIN, 0), limit(INCIDENT_LOG_MIN, 0)));
    }

    public List<String> recipients(String authorityCode) {
        return authorityRecipients.getOrDefault(authorityCode, List.of());
    }

    public List<String> validate() {
        List<String> v = new ArrayList<>();
        Instant now = Instant.now();
        for (JurisdictionPack.Right r : pack.rights()) {
            int max = rightMaxDays(r.code(), now);
            int d = rightSlaDays.get(r.code());
            if (d < 1 || d > max) {
                v.add("sla." + r.slaKey() + "-days must be 1.." + max + " (" + r.citation() + ")");
            }
        }
        checkMin(v, LOG_MIN, logRetentionDays, "logs.retention-days");
        checkMin(v, INCIDENT_LOG_MIN, logRetentionDays, "logs.retention-days");
        checkMin(v, PRE_ERASURE_MIN, preErasureNoticeHours, "retention.pre-erasure-notice-hours");
        checkMin(v, MIN_RETENTION, minimumRetentionDays, "retention.minimum-days");
        if (pack.hasLimit(LAWFUL_REQUEST_MAX) && lawfulRequestHours > pack.limit(LAWFUL_REQUEST_MAX, 0)) {
            v.add("lawful-request.response-hours must be <= " + pack.limit(LAWFUL_REQUEST_MAX, 0) + " (" + pack.limitCitation(LAWFUL_REQUEST_MAX) + ")");
        }
        if (pack.breach().detailedReportHours() > 0 && (breachReportHours < 1 || breachReportHours > pack.breach().detailedReportHours())) {
            v.add("breach.authority-report-hours must be 1.." + pack.breach().detailedReportHours() + " (" + pack.breach().citation() + ")");
        }
        for (JurisdictionPack.Authority a : pack.authorities()) {
            if ("INCIDENT".equals(a.role()) && recipients(a.code()).isEmpty()) {
                v.add("authority." + a.code() + ".recipients must not be empty (" + a.citation() + ")");
            }
        }
        if (hasLimit(INCIDENT_LOG_MIN) && !pack.isHome(logRegion)) {
            v.add("logs.region must be " + pack.homeCountry() + " (" + pack.limitCitation(INCIDENT_LOG_MIN) + ")");
        }
        if (enabledLanguages.stream().noneMatch(l -> l.code().equals(defaultLanguage.code()))) {
            v.add("default-language must be one of the enabled languages");
        }
        if (publicSector() && !pack.isHome(hostingCountry)) {
            v.add("hosting.country must be " + pack.homeCountry() + " for a public-sector deployment");
        }
        if (!"NOT_DECLARED".equals(hostingCategory) && pack.hostingCategory(hostingCategory).isEmpty()) {
            v.add("hosting.category " + hostingCategory + " is not defined by pack " + pack.code());
        }
        if (ntpEnabled && ntpServers.isEmpty()) {
            v.add("ntp.servers must be set when ntp.enabled");
        }
        return v;
    }

    public List<String> publicationGaps() {
        List<String> g = new ArrayList<>();
        if (rightsPortalUrl.isBlank()) g.add("links.rights-portal is not set");
        if (withdrawConsentUrl.isBlank()) g.add("links.withdraw-consent is not set");
        if (authorityComplaintUrl.isBlank()) g.add("links.authority-complaint is not set");
        if (privacyPolicyUrl.isBlank()) g.add("links.privacy-policy is not set");
        if (organisationName.isBlank()) g.add("organisation-name is not set");
        if ("NOT_DECLARED".equals(hostingCategory)) g.add("hosting.category is not declared");
        if (!ntpEnabled) g.add("ntp.enabled is false: time-source verification is not running");
        return g;
    }

    public ComplianceConfig requireValid() {
        List<String> v = validate();
        if (!v.isEmpty()) {
            throw new IllegalStateException("invalid sangam.compliance configuration: " + String.join("; ", v));
        }
        return this;
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jurisdiction", pack.code());
        m.put("region", region == null ? null : region.code());
        m.put("organisationName", organisationName);
        m.put("sector", sector);
        m.put("entityAttributes", List.copyOf(entityAttributes));
        m.put("appliedExemptions", List.copyOf(appliedExemptions));
        m.put("elevatedTier", elevatedTier);
        m.put("defaultLanguage", defaultLanguage.code());
        m.put("enabledLanguages", enabledLanguages.stream().map(JurisdictionPack.Language::code).toList());
        Map<String, Object> sla = new LinkedHashMap<>(rightSlaDays);
        m.put("rightSlaDays", sla);
        m.put("preErasureNoticeHours", preErasureNoticeHours);
        m.put("minimumRetentionDays", minimumRetentionDays);
        m.put("logRetentionDays", effectiveLogRetentionDays());
        m.put("logRegion", logRegion);
        m.put("breachReportHours", breachReportHours);
        Map<String, Object> rec = new LinkedHashMap<>(authorityRecipients);
        m.put("authorityRecipients", rec);
        m.put("additionalIncidentRecipients", additionalIncidentRecipients);
        m.put("ntpEnabled", ntpEnabled);
        m.put("ntpServers", ntpServers);
        m.put("hostingCategory", hostingCategory);
        m.put("hostingProvider", hostingProvider);
        m.put("hostingCountry", hostingCountry);
        m.put("accreditationValidUntil", accreditationValidUntil == null ? null : accreditationValidUntil.toString());
        m.put("denyCrossBorderByDefault", denyCrossBorderByDefault);
        m.put("negativeListCountries", List.copyOf(negativeListCountries));
        m.put("localisedDataCategories", List.copyOf(localisedDataCategories));
        m.put("registeredConsentManagers", List.copyOf(registeredConsentManagers));
        return m;
    }

    private void checkMin(List<String> v, String key, int value, String property) {
        if (hasLimit(key) && value < limit(key, 0)) {
            v.add(property + " must be >= " + limit(key, 0) + " (" + pack.limitCitation(key) + ")");
        }
    }

    private static String str(Function<String, String> p, String key, String def) {
        String v = p.apply(key);
        return v == null ? def : v.trim();
    }

    private static boolean bool(Function<String, String> p, String key, boolean def) {
        String v = p.apply(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Function<String, String> p, String key, int def) {
        String v = p.apply(key);
        return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
    }

    private static List<String> csv(String v) {
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> csvOr(Function<String, String> p, String key, List<String> def) {
        String v = p.apply(key);
        return v == null ? List.copyOf(def) : csv(v);
    }

    private static Set<String> set(List<String> values, boolean upper) {
        LinkedHashSet<String> s = values.stream().map(v -> upper ? v.toUpperCase(Locale.ROOT) : v)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(s);
    }
}
