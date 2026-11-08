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
 * (sangam-integration {@code PackConnectorBindings}). All operations are read-only except {@code series.next}, which is
 * idempotent per operationId (the number allocated for an operationId is remembered).
 *
 * <p>Operations and inputs (decimals as strings):
 * <ul>
 *   <li>{@code profile.get}: {@code paths} (comma list) → {@code values} map, {@code binding}, {@code placeholders};
 *       {@code office}, {@code asOf} optional;</li>
 *   <li>{@code profile.binding}: → {@code binding} (stored as the case's {@code profileVersion});</li>
 *   <li>{@code classification.validate}: {@code scheme} (default: profile {@code classification.defaultScheme}),
 *       {@code code} → {@code valid}, {@code findings}, {@code attributes}, segment values;</li>
 *   <li>{@code documentType.get}: {@code typeId} or {@code formNumber} → the type row (chain, rail, sanction ...);</li>
 *   <li>{@code documentType.check}: {@code typeId}, {@code document} (default: the call's own fields) →
 *       {@code valid}, {@code findings};</li>
 *   <li>{@code chain.signatures}: {@code chainId}, {@code amount} → {@code signatures} (unknown chain: CHAIN_UNKNOWN);</li>
 *   <li>{@code series.next}: {@code series}, {@code unit}, {@code period}, {@code format} → {@code number};</li>
 *   <li>{@code crosswalk.map}: {@code crosswalk}, {@code code}, {@code amount} → {@code mapped}.</li>
 * </ul>
 */
public final class ConfigConnector implements SangamConnector {

    private final ProfileResolver resolver;
    private final ClassificationRegistry registry;
    private final NumberSeries series;
    private final Map<String, Object> issued = new java.util.concurrent.ConcurrentHashMap<>();

    private final BusinessClock businessClock;

    /** Business dates from the profiles' own {@code locale.zone} (system clock). */
    public ConfigConnector(ProfileResolver resolver, ClassificationRegistry registry, NumberSeries series) {
        this(resolver, registry, series, new ProfileBusinessClock(resolver, java.time.Clock.systemUTC()));
    }

    public ConfigConnector(ProfileResolver resolver, ClassificationRegistry registry, NumberSeries series,
                           BusinessClock businessClock) {
        this.resolver = resolver;
        this.businessClock = java.util.Objects.requireNonNull(businessClock, "businessClock");
        this.registry = registry.withReferenceScopes(scope -> {
            List<String> ids = new ArrayList<>();
            for (String ref : resolver.resolve(scope, businessClock.today(scope)).chain()) {
                ids.add(ref.substring(0, ref.indexOf('@')));
            }
            java.util.Collections.reverse(ids);
            return ids;
        });
        this.series = series;
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
            ResolvedProfile p = resolver.resolve(r.tenantId(), r.str("office"), asOf);
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
                    // a call naming only the code (a bill's head of account) uses the profile's classification.defaultScheme
                    String schemeId = r.str("scheme") != null ? r.str("scheme") : p.str("classification.defaultScheme", null);
                    if (schemeId == null) {
                        return ConnectorResult.BusinessFailure.of("SCHEME_UNKNOWN",
                                "no scheme given and no classification.defaultScheme in the profile");
                    }
                    if (r.str("code") == null) {
                        return ConnectorResult.BusinessFailure.of("CODE_MISSING", "no classification code to validate");
                    }
                    Map<String, Object> sm = p.list("classification.schemes").stream()
                            .filter(m -> schemeId.equals(m.get("id"))).findFirst().orElse(null);
                    if (sm == null) {
                        return ConnectorResult.BusinessFailure.of("SCHEME_UNKNOWN", "no scheme " + schemeId);
                    }
                    ClassificationScheme s = ClassificationScheme.from(sm);
                    ClassificationRegistry.Code c = registry.parse(s, r.str("code"));
                    List<Finding> f = new ArrayList<>(registry.validate(r.tenantId(), s, c, asOf, id -> p.list("classification.schemes").stream()
                            .filter(m -> id.equals(m.get("id"))).findFirst().map(ClassificationScheme::from).orElse(null)));
                    f.addAll(Crosswalk.checkCombinations(p.list("classification.combinations"), c));
                    out.put("valid", !Finding.anyError(f));
                    out.put("findings", render(f));
                    out.put("attributes", c.attributes());
                    out.putAll(c.segments());
                }
                case "documentType.get" -> {
                    DocumentTypeCatalogue cat = DocumentTypeCatalogue.of(p);
                    var t = r.str("typeId") != null ? cat.type(r.str("typeId")) : cat.byForm(r.str("formNumber"));
                    if (t.isEmpty()) {
                        return ConnectorResult.BusinessFailure.of("TYPE_UNKNOWN", "document type not in the catalogue");
                    }
                    out.putAll(t.get().raw());
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
                    // an unknown chain is a failure, never a default of one signature (ADR-D-26); the amount is the
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
                    Object prior = issued.get(r.operationId().toString());
                    if (prior != null) {
                        out.put("number", prior);
                        return new ConnectorResult.Success(out, String.valueOf(prior), true);
                    }
                    String period = r.str("period") == null ? asOf.toString() : r.str("period");
                    long n = series.next(r.tenantId(), r.str("series"), r.str("unit"), period);
                    Object number = r.str("format") == null ? n
                            : NumberSeries.format(r.str("format"), r.str("unit"), period, asOf, n);
                    issued.put(r.operationId().toString(), number);
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
            return ConnectorResult.BusinessFailure.of("CONFIG_ERROR", e.getMessage());
        }
    }

    @Override
    public LookupResult lookup(ConnectorRequest r) {
        Object prior = issued.get(r.operationId().toString());
        return prior == null ? new LookupResult.NotFound()
                : new LookupResult.Found(true, Map.of("number", prior), String.valueOf(prior), null);
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
