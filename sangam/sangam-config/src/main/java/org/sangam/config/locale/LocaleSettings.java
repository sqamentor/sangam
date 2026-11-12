// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import org.sangam.config.common.Tree;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The locale section of a resolved profile:
 * <pre>
 * locale:
 *   zone: Asia/Kolkata
 *   currency: INR               # ISO 4217
 *   symbol: "₹"
 *   grouping: {first: 3, rest: 2}
 *   units: {L: 100000, CR: 10000000}
 *   fiscal: {startMonth: 4, startDay: 1, label: START-END2}
 *   languages: [{tag: hi, script: Deva, direction: ltr, default: true}, {tag: en, script: Latn}, {tag: ur, script: Arab, direction: rtl}]
 *   regions: {KARAIKAL: [ta, en]}
 *   calendars: {government: {...}, settlement: {...}}   # BusinessCalendar shapes
 *   exchangeRates: [...]
 * </pre>
 * SL-125 (fail closed): {@code fiscal} is optional. Without it, or with an incomplete one, the locale still builds (money,
 * zone, languages and calendars keep working) and {@link #fiscal()} refuses with {@link FiscalYearNotConfiguredException}
 * naming the profile and {@code locale.fiscal}; {@link #hasFiscal()} tells the two apart. No fiscal year is ever assumed.
 *
 * @param fiscal        the fiscal calendar, or null when none (or an invalid one) is configured; read it with
 *                      {@link #fiscal()}
 * @param fiscalProblem why {@code locale.fiscal} is unusable when it is present but incomplete (null otherwise)
 * @param subject       the profile the locale came from (scope and binding), named in the refusal
 */
public record LocaleSettings(ZoneId zone, MoneyFormat money, FiscalCalendar fiscal, List<Language> languages,
                             Map<String, List<String>> regionLanguages, Map<String, BusinessCalendar> calendars,
                             ExchangeRates exchangeRates, String fiscalProblem, String subject) {

    /** True when a complete fiscal calendar is configured. */
    public boolean hasFiscal() {
        return fiscal != null;
    }

    /** The fiscal calendar; refuses (never assumes one) when {@code locale.fiscal} is absent or incomplete. */
    @Override
    public FiscalCalendar fiscal() {
        if (fiscal != null) {
            return fiscal;
        }
        String who = subject == null ? "this profile" : subject;
        if (fiscalProblem != null) {
            throw new FiscalYearNotConfiguredException("the fiscal year of " + who + " is incomplete: " + fiscalProblem
                    + "; fiscal operations are refused until " + FiscalYearNotConfiguredException.PATH + " is complete");
        }
        throw FiscalYearNotConfiguredException.forSubject(who);
    }

    /** A language with its BCP 47 tag, ISO 15924 script and text direction. */
    public record Language(String tag, String script, String direction, boolean isDefault) {
        public boolean rtl() {
            return "rtl".equalsIgnoreCase(direction);
        }
    }

    public static LocaleSettings from(Map<String, Object> m) {
        return from(m, null);
    }

    /** {@code subject}: the profile (scope and binding) named when a fiscal operation is refused. */
    public static LocaleSettings from(Map<String, Object> m, String subject) {
        ZoneId zone = ZoneId.of(Tree.str(m.get("zone"), "UTC"));
        String ccy = Tree.str(m.get("currency"), null);
        Map<String, BigDecimal> units = new LinkedHashMap<>();
        Tree.map(m.get("units")).forEach((k, v) -> units.put(k, new BigDecimal(v.toString())));
        MoneyFormat money = new MoneyFormat(ccy == null ? null : Currency.getInstance(ccy),
                NumberGrouping.from(Tree.map(m.get("grouping"))), units, Tree.str(m.get("symbol"), null));
        List<Language> langs = new ArrayList<>();
        for (Object o : m.get("languages") instanceof List<?> l ? l : List.of()) {
            if (o instanceof Map<?, ?> lm) {
                Map<String, Object> x = Tree.map(lm);
                langs.add(new Language(Tree.str(x.get("tag"), "en"), Tree.str(x.get("script"), null),
                        Tree.str(x.get("direction"), "ltr"), Tree.bool(x.get("default"), false)));
            } else if (o != null) {
                langs.add(new Language(o.toString(), null, "ltr", langs.isEmpty()));
            }
        }
        Map<String, List<String>> regions = new LinkedHashMap<>();
        Tree.map(m.get("regions")).forEach((k, v) -> regions.put(k, Tree.strings(v)));
        Map<String, BusinessCalendar> cals = new LinkedHashMap<>();
        Tree.map(m.get("calendars")).forEach((k, v) -> cals.put(k, BusinessCalendar.from(k, Tree.map(v), zone)));
        FiscalCalendar fiscal = null;
        String fiscalProblem = null;
        try {
            fiscal = FiscalCalendar.from(Tree.map(m.get("fiscal")), zone).orElse(null);
        } catch (RuntimeException e) {   // incomplete or invalid: kept as the reason fiscal() gives, never a default
            fiscalProblem = e.getMessage();
        }
        return new LocaleSettings(zone, money, fiscal, List.copyOf(langs), Map.copyOf(regions), Map.copyOf(cals),
                ExchangeRates.from(m.get("exchangeRates")), fiscalProblem, subject);
    }

    public BusinessCalendar calendar(String name) {
        BusinessCalendar c = calendars.get(name);
        return c != null ? c : new BusinessCalendar(name, zone, null, null, null, null);
    }

    /** Languages for a region (e.g. a UT region with its own language), else the profile languages. */
    public List<String> languagesFor(String region) {
        List<String> r = region == null ? null : regionLanguages.get(region);
        if (r != null) {
            return r;
        }
        return languages.stream().map(Language::tag).toList();
    }
}
