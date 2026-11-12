// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import org.sangam.config.catalogue.DocumentTypeCatalogue;
import org.sangam.config.catalogue.NumberSeries;
import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;
import org.sangam.config.master.ClassificationRegistry;
import org.sangam.config.master.ClassificationScheme;
import org.sangam.config.master.Crosswalk;
import org.sangam.config.locale.ProfileBusinessClock;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.time.BusinessClock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process connector through which packs read configuration (packs never hard-code a jurisdiction value). The
 * integrator binds the pack connector ids {@code config.profile}, {@code config.classification},
 * {@code config.documentType}, {@code config.series} and {@code config.crosswalk} to this connector
 * (sangam-integration {@code PackConnectorBindings}). All operations are read-only except {@code series.next}, which
 * reserves its number under a key: the caller's {@code ref} when given (the document identity, so a re-run of the
 * same document under a new operation gets the same number), else the operationId. With a durable series
 * ({@link SeriesReservations}, the JDBC store) the reservation is committed with the counter step, so a retry on any
 * node or after a restart returns the same number and burns none (SL-130 follow-up); an in-memory series remembers
 * its reservations in this node's memory only, as it does its counters.
 *
 * <p>Operations and inputs (decimals as strings):
 * <ul>
 *   <li>{@code profile.get}: {@code paths} (comma list) → {@code values} map, {@code binding}, {@code placeholders};
 *       {@code office}, {@code asOf} optional;</li>
 *   <li>{@code profile.binding}: → {@code binding} (stored as the case's {@code profileVersion});</li>
 *   <li>{@code classification.validate}: {@code scheme} (default: the {@code classification.scheme} of the document
 *       type {@code typeId}, else profile {@code classification.defaultScheme}), {@code code} → {@code valid},
 *       {@code findings}, {@code attributes}, segment values;</li>
 *   <li>{@code documentType.get}: {@code typeId}, {@code formNumber}, or {@code typeRef} (a catalogue id or a form
 *       number; exactly one type must match, else TYPE_AMBIGUOUS), optional {@code formKind} (only types of that
 *       kind match, e.g. {@code BILL}) → the type row (chain, rail, sanction ...);</li>
 *   <li>{@code documentType.check}: {@code typeId}, {@code document} (default: the call's own fields) →
 *       {@code valid}, {@code findings};</li>
 *   <li>{@code chain.signatures}: {@code chainId}, {@code amount} → {@code signatures} (unknown chain: CHAIN_UNKNOWN);</li>
 *   <li>{@code series.next}: {@code series}, {@code unit}, {@code period}, {@code format}, optional {@code ref} →
 *       {@code number} (a repeat with the same key answers the stored number as a duplicate);</li>
 *   <li>{@code crosswalk.map}: {@code crosswalk}, {@code code}, {@code amount} → {@code mapped}.</li>
 * </ul>
 */
public final class ConfigConnector implements SangamConnector {

    /**
     * A number series that reserves numbers durably by key (SL-130 follow-up; implemented by the JDBC config store).
     * The reservation and the counter step commit in one transaction.
     */
    public interface SeriesReservations {

        /**
         * A reserved number.
         *
         * @param number   the counter value
         * @param display  the formatted number, or null when the call had no format
         * @param replayed true when the key was already reserved (nothing was drawn)
         */
        record Reserved(long number, String display, boolean replayed) {
            /** What {@code series.next} answers: the formatted number, else the number. */
            public Object value() {
                return display != null ? display : number;
            }
        }

        /**
         * The number reserved for {@code key} in (scope, series); when there is none, the next number of
         * (scope, series, unit, period), formatted by {@code display} (may be null), reserved and counted together.
         */
        Reserved reserve(String scope, String series, String unit, String period, String key,
                         java.util.function.LongFunction<String> display);

        /** The reservation of {@code key}, without drawing. */
        java.util.Optional<Reserved> reserved(String scope, String series, String key);
    }

    private final ProfileResolver resolver;
    private final ClassificationRegistry registry;
    private final NumberSeries series;
    /** Durable reservations when the series supports them (the JDBC store), else null. */
    private final SeriesReservations reservations;
    /** Node-local reservations of a series without durable ones (in-memory series: dev and tests). */
    private final Map<String, Object> issued = new java.util.concurrent.ConcurrentHashMap<>();

    private final BusinessClock businessClock;
    private final CaseBindings caseBindings;
    private final java.util.Set<String> unboundLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ConfigConnector.class);

    /** Business dates from the profiles' own {@code locale.zone} (system clock). */
    public ConfigConnector(ProfileResolver resolver, ClassificationRegistry registry, NumberSeries series) {
        this(resolver, registry, series, new ProfileBusinessClock(resolver, java.time.Clock.systemUTC()));
    }

    /** Without stored case bindings: every call reads the profile versions in force ({@link CaseBindings#NONE}). */
    public ConfigConnector(ProfileResolver resolver, ClassificationRegistry registry, NumberSeries series,
                           BusinessClock businessClock) {
        this(resolver, registry, series, businessClock, CaseBindings.NONE);
    }

    /**
     * @param caseBindings the bindings cases stored at their start (SL-119): a call of a bound case ({@code rootId})
     *                     reads the profile versions of its binding, so a running case keeps the configuration it
     *                     started under (GP-19 / R43) and only new cases adopt a newly activated version
     */
    public ConfigConnector(ProfileResolver resolver, ClassificationRegistry registry, NumberSeries series,
                           BusinessClock businessClock, CaseBindings caseBindings) {
        this.resolver = resolver;
        this.businessClock = java.util.Objects.requireNonNull(businessClock, "businessClock");
        this.caseBindings = java.util.Objects.requireNonNull(caseBindings, "caseBindings");
        // other callers of the shared registry (no profile at hand) search the chain in force; this connector passes
        // the chain of the profile each call reads (SL-124), a running case's pinned chain included
        this.registry = registry.withReferenceScopes(scope -> ClassificationRegistry.referenceScopesOf(
                resolver.resolve(scope, businessClock.today(scope)).chain()));
        this.series = series;
        this.reservations = series instanceof SeriesReservations r ? r : null;
    }

    /** The reservation key of a {@code series.next} call: {@code ref:<ref>} when the caller names one, else {@code op:<operationId>}. */
    static String reservationKey(ConnectorRequest r) {
        String ref = r.str("ref");
        return ref != null && !ref.isBlank() ? "ref:" + ref : "op:" + r.operationId();
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor("config", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("profile.get", "profile.binding", "classification.validate", "documentType.get", "documentType.check",
                        "chain.signatures", "series.next", "crosswalk.map"), ConnectorProfile.LIVE);
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        try {
            LocalDate asOf = r.str("asOf") == null ? businessClock.today(r.tenantId()) : LocalDate.parse(r.str("asOf"));
            ResolvedProfile p = profileOf(r, asOf);
            Map<String, Object> out = new LinkedHashMap<>();
            switch (r.operation()) {
                case "profile.get" -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (String path : Tree.strings(r.input().get("paths"))) {
                        values.put(path, p.get(path));
                    }
                    out.put("values", values);
                    out.put("binding", p.binding());
                    out.put("placeholders", new ArrayList<>(p.placeholders().keySet()));
                }
                case "profile.binding" -> out.put("binding", p.binding());
                case "classification.validate" -> {
                    // a call naming only the code (a bill's head of account) uses the scheme its document type declares
                    // (catalogue classification.scheme, e.g. a Union bill type on HOA), else the profile's
                    // classification.defaultScheme (a State's own head-of-account scheme)
                    String schemeId = r.str("scheme") != null ? r.str("scheme") : typeScheme(p, r.str("typeId"));
                    if (schemeId == null) {
                        schemeId = p.str("classification.defaultScheme", null);
                    }
                    if (schemeId == null) {
                        return ConnectorResult.BusinessFailure.of("SCHEME_UNKNOWN",
                                "no scheme given, none on the document type and no classification.defaultScheme in the profile");
                    }
                    if (r.str("code") == null) {
                        return ConnectorResult.BusinessFailure.of("CODE_MISSING", "no classification code to validate");
                    }
                    String scheme = schemeId;
                    Map<String, Object> sm = p.list("classification.schemes").stream()
                            .filter(m -> scheme.equals(m.get("id"))).findFirst().orElse(null);
                    if (sm == null) {
                        return ConnectorResult.BusinessFailure.of("SCHEME_UNKNOWN", "no scheme " + scheme);
                    }
                    ClassificationScheme s = ClassificationScheme.from(sm);
                    ClassificationRegistry.Code c = registry.parse(s, r.str("code"));
                    List<Finding> f = new ArrayList<>(registry.validate(r.tenantId(),
                            ClassificationRegistry.referenceScopesOf(p.chain()), s, c, asOf, id -> p.list("classification.schemes").stream()
                            .filter(m -> id.equals(m.get("id"))).findFirst().map(ClassificationScheme::from).orElse(null)));
                    f.addAll(Crosswalk.checkCombinations(p.list("classification.combinations"), c));
                    out.put("valid", !Finding.anyError(f));
                    out.put("findings", render(f));
                    out.put("attributes", c.attributes());
                    out.putAll(c.segments());
                }
                case "documentType.get" -> {
                    DocumentTypeCatalogue cat = DocumentTypeCatalogue.of(p);
                    // typeRef: the caller's vocabulary (a catalogue id or a form number), resolved by the catalogue data;
                    // exactly one type must match; several are ambiguous and never decided by catalogue order
                    // formKind (optional, e.g. BILL): only types of that kind match, so a bill never resolves a report
                    // or a register; no formKind applies no restriction
                    String kind = r.str("formKind");
                    List<org.sangam.config.catalogue.DocumentType> t = r.str("typeId") != null
                            ? DocumentTypeCatalogue.ofKind(cat.type(r.str("typeId")).stream().toList(), kind)
                            : r.str("formNumber") != null ? cat.byForm(r.str("formNumber"), kind).stream().toList()
                            : cat.byReference(r.str("typeRef"), kind);
                    if (t.size() > 1) {
                        return ConnectorResult.BusinessFailure.of("TYPE_AMBIGUOUS", "'" + r.str("typeRef")
                                + "' names more than one document type: " + t.stream().map(x -> x.id()).toList());
                    }
                    if (t.isEmpty()) {
                        return ConnectorResult.BusinessFailure.of("TYPE_UNKNOWN", "document type not in the catalogue");
                    }
                    out.putAll(t.get(0).raw());
                }
                case "documentType.check" -> {
                    DocumentTypeCatalogue cat = DocumentTypeCatalogue.of(p);
                    // the document is the 'document' map, else the call's own fields (a process sends its bill fields)
                    Map<String, Object> doc = r.input().get("document") instanceof Map<?, ?>
                            ? Tree.map(r.input().get("document")) : new LinkedHashMap<>(r.input());
                    List<Finding> f = cat.check(r.str("typeId"), doc, asOf, null);
                    out.put("valid", !Finding.anyError(f));
                    out.put("findings", render(f));
                }
                case "chain.signatures" -> {
                    DocumentTypeCatalogue cat = DocumentTypeCatalogue.of(p);
                    // an unknown chain is a failure, never a default of one signature; the amount is the
                    // pack's number as given (its unit is the catalogue's band unit)
                    var chain = r.str("chainId") == null ? java.util.Optional.<DocumentTypeCatalogue.ChainTemplate>empty()
                            : cat.chain(r.str("chainId"));
                    if (chain.isEmpty()) {
                        return ConnectorResult.BusinessFailure.of("CHAIN_UNKNOWN",
                                "approval chain '" + r.str("chainId") + "' not in the catalogue");
                    }
                    if (r.str("amount") == null) {
                        return ConnectorResult.BusinessFailure.of("AMOUNT_MISSING", "no amount for the signature count");
                    }
                    out.put("signatures", chain.get().signaturesFor(new BigDecimal(r.str("amount"))));
                }
                case "series.next" -> {
                    String key = reservationKey(r);
                    String period = r.str("period") == null ? asOf.toString() : r.str("period");
                    String unit = r.str("unit");
                    String format = r.str("format");
                    if (reservations != null) {
                        SeriesReservations.Reserved res = reservations.reserve(r.tenantId(), r.str("series"), unit, period, key,
                                format == null ? null : n -> NumberSeries.format(format, unit, period, asOf, n));
                        out.put("number", res.value());
                        return res.replayed() ? new ConnectorResult.Success(out, String.valueOf(res.value()), true)
                                : ConnectorResult.Success.of(out, null);
                    }
                    String localKey = localKey(r, key);
                    Object prior = issued.get(localKey);
                    if (prior != null) {
                        out.put("number", prior);
                        return new ConnectorResult.Success(out, String.valueOf(prior), true);
                    }
                    long n = series.next(r.tenantId(), r.str("series"), unit, period);
                    Object number = format == null ? n : NumberSeries.format(format, unit, period, asOf, n);
                    issued.put(localKey, number);
                    out.put("number", number);
                }
                case "crosswalk.map" -> {
                    String id = r.str("crosswalk");
                    Map<String, Object> cw = p.list("crosswalks").stream().filter(m -> id.equals(m.get("id"))).findFirst()
                            .orElse(Map.of());
                    List<Map<String, Object>> mapped = new ArrayList<>();
                    for (Crosswalk.Mapped m : Crosswalk.map(cw, r.str("code"),
                            r.str("amount") == null ? null : new BigDecimal(r.str("amount")), asOf)) {
                        mapped.add(Map.of("target", m.target(), "share", m.share().toPlainString(),
                                "amount", m.amount() == null ? "" : m.amount().toPlainString()));
                    }
                    out.put("mapped", mapped);
                }
                default -> {
                    return ConnectorResult.BusinessFailure.of("OPERATION_UNKNOWN", r.operation());
                }
            }
            return ConnectorResult.Success.of(out, null);
        } catch (RuntimeException e) {
            if (e instanceof org.sangam.config.common.ConfigException ce && "BINDING_UNRESOLVABLE".equals(ce.code())) {
                return ConnectorResult.BusinessFailure.of(ce.code(), ce.getMessage());
            }
            return ConnectorResult.BusinessFailure.of("CONFIG_ERROR", e.getMessage());
        }
    }

    @Override
    public LookupResult lookup(ConnectorRequest r) {
        String key = reservationKey(r);
        Object prior;
        if (reservations != null) {
            if (r.str("series") == null) {
                return new LookupResult.NotFound();
            }
            prior = reservations.reserved(r.tenantId(), r.str("series"), key).map(SeriesReservations.Reserved::value)
                    .orElse(null);
        } else {
            prior = issued.get(localKey(r, key));
        }
        return prior == null ? new LookupResult.NotFound()
                : new LookupResult.Found(true, Map.of("number", prior), String.valueOf(prior), null);
    }

    /** The node-local key: tenant and series as the durable reservation's scope, plus the reservation key. */
    private static String localKey(ConnectorRequest r, String key) {
        return r.tenantId() + "\u0000" + r.str("series") + "\u0000" + key;
    }

    /**
     * The profile a call reads (SL-119): the case's stored binding when the call names a bound case, so every operation
     * honours the pin; otherwise (no case, or a case started before bindings were stored) the versions in force on
     * {@code asOf}, logged once per tenant. A binding that no longer resolves fails the call ({@code BINDING_*}); it is
     * never silently replaced by today's versions. The binding is the case's whole chain, so a request's {@code office}
     * does not add an overlay to it.
     */
    ResolvedProfile profileOf(ConnectorRequest r, LocalDate asOf) {
        String binding = r.rootId() == null ? null : caseBindings.binding(r.tenantId(), r.rootId()).orElse(null);
        if (binding == null) {
            if (r.rootId() != null && caseBindings != CaseBindings.NONE && unboundLogged.add(r.tenantId())) {
                LOG.warn("case {} of tenant {} has no stored profile binding: configuration reads use the versions in "
                        + "force (logged once per tenant)", r.rootId(), r.tenantId());
            }
            return resolver.resolve(r.tenantId(), r.str("office"), asOf);
        }
        ResolvedProfile p = resolver.resolveBinding(r.tenantId(), binding, asOf);
        List<Finding> broken = p.findings().stream().filter(ProfileResolver::isBindingError).toList();
        if (!broken.isEmpty()) {
            throw new org.sangam.config.common.ConfigException("BINDING_UNRESOLVABLE", "case " + r.rootId()
                    + " binding " + binding + ": " + broken.stream().map(Finding::message).toList(), broken);
        }
        return p;
    }

    /** The classification scheme a document type declares for its lines ({@code classification.scheme}), or null. */
    static String typeScheme(ResolvedProfile p, String typeId) {
        if (typeId == null) {
            return null;
        }
        return DocumentTypeCatalogue.of(p).type(typeId)
                .map(t -> Tree.str(Tree.map(t.get("classification")).get("scheme"), null)).orElse(null);
    }

    static List<Map<String, Object>> render(List<Finding> f) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Finding x : f) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", x.severity().name());
            m.put("code", x.code());
            m.put("path", x.path());
            m.put("message", x.message());
            m.put("ruleRef", x.ruleRef());
            out.add(m);
        }
        return out;
    }
}
