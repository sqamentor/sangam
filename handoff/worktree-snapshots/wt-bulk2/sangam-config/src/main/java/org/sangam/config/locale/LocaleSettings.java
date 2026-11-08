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
 */
public record LocaleSettings(ZoneId zone, MoneyFormat money, FiscalCalendar fiscal, List<Language> languages,
                             Map<String, List<String>> regionLanguages, Map<String, BusinessCalendar> calendars,
                             ExchangeRates exchangeRates) {

    /** A language with its BCP 47 tag, ISO 15924 script and text direction. */
    public record Language(String tag, String script, String direction, boolean isDefault) {
        public boolean rtl() {
            return "rtl".equalsIgnoreCase(direction);
        }
    }

    public static LocaleSettings from(Map<String, Object> m) {
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
        return new LocaleSettings(zone, money, FiscalCalendar.from(Tree.map(m.get("fiscal")), zone), List.copyOf(langs),
                Map.copyOf(regions), Map.copyOf(cals), ExchangeRates.from(m.get("exchangeRates")));
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
