// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import org.sangam.config.common.ConfigException;
import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;
import org.sangam.config.locale.LocaleSettings;
import org.sangam.core.money.Money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;

/**
 * The effective configuration of one scope (and optional office) on one date: the merged data tree, the chain of
 * documents it came from, the binding string a case stores at start, the open placeholders and the findings.
 *
 * @param binding  {@code id@version>id@version...#hash12}: stored on the case as {@code profileVersion}, so a running
 *                 case keeps the configuration in force at its start (GP-19 semantics)
 */
public record ResolvedProfile(String scope, String office, LocalDate asOf, Map<String, Object> data,
                              List<String> chain, String binding, Map<String, String> placeholders,
                              List<Finding> findings) {

    public Object get(String path) {
        return Tree.get(data, path);
    }

    public String str(String path, String def) {
        return Tree.str(get(path), def);
    }

    public boolean bool(String path, boolean def) {
        return Tree.bool(get(path), def);
    }

    public int integer(String path, int def) {
        return Tree.integer(get(path), def);
    }

    /**
     * Decimal value; amount unit suffixes ({@code 10L}, {@code 5M}) are resolved with the profile locale. Not for
     * money: a profile amount is integer minor units read with {@link #money(String)} (SL-102).
     */
    public BigDecimal amount(String path, BigDecimal def) {
        Object v = get(path);
        if (v == null) {
            return def;
        }
        BigDecimal b = locale().money().parse(v);
        return b == null ? def : b;
    }

    /**
     * A money value of the profile (SL-102; SD-398 / PACKS4, the rule packs follow): an integer count of ISO 4217
     * minor units of this profile's {@code locale.currency}, as profilegen writes it ({@code upTo: 100000000000} is
     * 100 crore rupees in paise). The result carries the amount and the currency together.
     *
     * <p>Strict, never guessed: free text ({@code "2CR"}, {@code "5L"}), a numeric string ({@code "50000"}: major or
     * minor units?), a decimal or a float is refused with {@code MONEY_NOT_MINOR_UNITS}; a profile without
     * {@code locale.currency} is refused with {@code MONEY_CURRENCY_MISSING} (no default currency). Both name the
     * profile and the path.
     *
     * @return the amount, or null when the path is absent
     */
    public Money money(String path) {
        Object v = get(path);
        if (v == null) {
            return null;
        }
        long minor;
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            minor = ((Number) v).longValue();
        } else if (v instanceof BigInteger b && b.bitLength() < Long.SIZE) {
            minor = b.longValue();
        } else {
            throw new ConfigException("MONEY_NOT_MINOR_UNITS", subject() + ": " + path + " = '" + v
                    + "' is not an integer amount in minor units of locale.currency (free text, numeric strings and"
                    + " decimals are refused)");
        }
        Currency currency = locale().money().currency();
        if (currency == null) {
            throw new ConfigException("MONEY_CURRENCY_MISSING", subject() + ": " + path
                    + " is money but locale.currency is not set");
        }
        return Money.ofMinor(minor, currency.getCurrencyCode());
    }

    private String subject() {
        return "profile " + scope + (binding == null ? "" : " (" + binding + ")");
    }

    public Map<String, Object> map(String path) {
        return Tree.map(get(path));
    }

    public List<Map<String, Object>> list(String path) {
        return Tree.maps(get(path));
    }

    public List<String> strings(String path) {
        return Tree.strings(get(path));
    }

    /** The locale section; its {@code fiscal()} names this profile (scope and binding) when it refuses (SL-125). */
    public LocaleSettings locale() {
        return LocaleSettings.from(map("locale"), subject());
    }

    /** True if the path (or one of its ancestors) is an unverified placeholder. */
    public boolean isPlaceholder(String path) {
        for (String p : placeholders.keySet()) {
            if (path.equals(p) || path.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    public boolean hasErrors() {
        return Finding.anyError(findings);
    }
}
