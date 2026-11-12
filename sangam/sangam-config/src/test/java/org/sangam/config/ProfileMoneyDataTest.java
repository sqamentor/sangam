// SPDX-License-Identifier: Apache-2.0
package org.sangam.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileDocument;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ResolvedProfile;
import org.sangam.core.money.Money;

/**
 * SL-102: the shipped profiles carry money as integer minor units of the profile currency (SD-398 / PACKS4), never as
 * free text. Before the fix 25 thresholds were text such as {@code 2CR}, {@code 5L} or {@code 2835.20CR} and five were
 * rupee strings such as {@code '50000'} (and the hand-maintained GBR token amount {@code '1000'} pounds); no reader
 * could tell major from minor units. Every one now reads as typed
 * {@link Money} through {@link ResolvedProfile#money(String)}, and no profile holds an amount written with a unit of
 * its own locale ({@code locale.units}) again.
 */
class ProfileMoneyDataTest {

    static final Path DIR = Path.of("..", "sangam-packs", "src", "main", "resources", "profiles");
    static final LocalDate AS_OF = LocalDate.of(2026, 11, 5);

    private static ProfileResolver resolver(List<ProfileDocument> docs) throws Exception {
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(Files.readString(DIR.resolve("layers.yaml"))).get("layers"));
        return new ProfileResolver(InMemoryProfileRepository.active(docs), layers);
    }

    /** The profile's own currency, from its locale (each scope resolves it; nothing is assumed here). */
    private static Money minor(ResolvedProfile p, long minor) {
        return Money.ofMinor(minor, p.locale().money().currency().getCurrencyCode());
    }

    @Test
    void everyFormerFreeTextThresholdIsTypedMinorUnits() throws Exception {
        ProfileResolver r = resolver(ProfileLoader.loadDirectory(DIR));
        Map<String, Map<String, Long>> expected = Map.of(
                "GOI", Map.ofEntries(
                        Map.entry("budgetControl.reappropriation.objectGroupLimits.EST_2CR", 2_000_000_000L),
                        Map.entry("budgetControl.reappropriation.objectGroupLimits.NONEST_5CR", 5_000_000_000L),
                        Map.entry("budgetControl.reappropriation.objectGroupLimits.OTHER_15CR", 15_000_000_000L),
                        Map.entry("budgetControl.reappropriation.parliamentReport.amountAbove", 100_000_000_000L),
                        Map.entry("appraisal.ladder.0.upTo", 100_000_000_000L),
                        Map.entry("appraisal.ladder.1.upTo", 500_000_000_000L),
                        Map.entry("appraisal.ladder.2.upTo", 1_000_000_000_000L),
                        Map.entry("appraisal.preInvestmentUpTo", 100_000_000_000L),
                        Map.entry("grants.cna.model1Threshold", 100_000_000_000L),
                        Map.entry("establishment.pension.minimum", 900_000L),
                        Map.entry("establishment.pension.maximum", 12_500_000L),
                        Map.entry("establishment.gpf.annualCeiling", 50_000_000L),
                        Map.entry("establishment.ups.minimumAfter10Years", 1_000_000L),
                        Map.entry("procurement.directPurchaseUpTo", 5_000_000L),
                        Map.entry("procurement.lpcUpTo", 50_000_000L),
                        Map.entry("procurement.limitedTenderUpTo.general", 500_000_000L),
                        Map.entry("procurement.limitedTenderUpTo.scientificDepartments", 1_000_000_000L),
                        Map.entry("overpaymentWaiver.departmentUpTo", 20_000_000L),
                        Map.entry("delegation.rows.procurementThresholds.direct", 5_000_000L),
                        Map.entry("delegation.rows.procurementThresholds.gemL1", 100_000_000L),
                        Map.entry("delegation.rows.procurementThresholds.limitedTender", 500_000_000L),
                        Map.entry("delegation.rows.procurementThresholds.limitedTenderScientific", 1_000_000_000L)),
                "DH", Map.of(
                        "regime.administratorDelegation.governorOrLg", 50_000_000_000L,
                        "regime.administratorDelegation.other", 25_000_000_000L,
                        "budget.be2026_27Gross", 2_835_200_000_000L),
                "KA", Map.of("procurement.vmsBelow", 50_000_000L, "procurement.gemAbove", 50_000_000L),
                "RJ", Map.of("publicAccounts.types.PD.controls.cfTransferAlert", 10_000_000_000L),
                "TG", Map.of("dbtBurst.maxPerDay", 6_000_000_000_000L),
                "TN", Map.of("receipts.channelThresholds.0.atOrAbove", 1_000_000_000L),
                // hand-maintained (intl.idx, outside profilegen): the UK token vote of GBP 1,000, in pence
                "GBR", Map.of("estimates.tokenAmount", 100_000L));
        int checked = 0;
        for (var scope : expected.entrySet()) {
            ResolvedProfile p = r.resolve(scope.getKey(), AS_OF);
            for (var e : scope.getValue().entrySet()) {
                assertEquals(minor(p, e.getValue()), p.money(e.getKey()), scope.getKey() + " " + e.getKey());
                checked++;
            }
        }
        assertEquals(31, checked, "25 unit-suffixed texts, 5 rupee strings and the GBR token amount");
    }

    @Test
    void noShippedProfileWritesAnAmountWithALocaleUnit() throws Exception {
        List<ProfileDocument> docs = ProfileLoader.loadDirectory(DIR);
        ProfileResolver r = resolver(docs);
        List<String> found = new ArrayList<>();
        int scanned = 0;
        for (ProfileDocument d : docs) {
            if (d.layer().equals("international") || d.layer().equals("regime")) {
                continue; // currency-neutral presets: no locale, no units
            }
            Pattern freeText = freeTextMoney(r.resolve(d.id(), AS_OF).locale().money().units().keySet());
            if (freeText == null) {
                continue;
            }
            scanned++;
            collect(d.toTree(), d.id(), freeText, found);
        }
        assertTrue(scanned > 40, "the Indian and international profiles with locale units are scanned: " + scanned);
        assertTrue(found.isEmpty(), "money written as free text (write integer minor units, profilegen money): " + found);
    }

    @Test
    void theGuardRecognisesTheFormerFreeTextAndNothingElse() {
        Pattern p = freeTextMoney(java.util.Set.of("L", "CR"));
        for (String s : List.of("2CR", "5L", "2835.20CR", "100 CR", "5l", "1,00CR", "6000CR")) {
            assertTrue(p.matcher(s).matches(), s);
        }
        for (String s : List.of("PT30M", "P12M", "RJ:GA 36L", "2054-00-001-01", "2026.11.0", "50000", "EST_2CR", "CR", "L")) {
            assertFalse(p.matcher(s).matches(), s);
        }
    }

    /** {@code <number><unit>} for the locale's units (longest first), any case, as MoneyFormat.parse would read it. */
    private static Pattern freeTextMoney(java.util.Set<String> units) {
        if (units.isEmpty()) {
            return null;
        }
        String alt = units.stream().sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return Pattern.compile("^\\s*[+-]?\\d[\\d,_]*(\\.\\d+)?\\s*(" + alt + ")\\s*$", Pattern.CASE_INSENSITIVE);
    }

    private static void collect(Object v, String path, Pattern freeText, List<String> found) {
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, x) -> collect(x, path + "." + k, freeText, found));
        } else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                collect(l.get(i), path + "." + i, freeText, found);
            }
        } else if (v instanceof String s && freeText.matcher(s).matches()) {
            found.add(path + " = " + s);
        }
    }
}
