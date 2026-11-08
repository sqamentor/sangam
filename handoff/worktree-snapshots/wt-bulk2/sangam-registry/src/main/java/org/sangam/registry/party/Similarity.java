// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.party;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Script-neutral string normalisation and similarity measures used by the matcher: Unicode NFKD with combining marks
 * removed, lower case, letters and digits of any script kept; Jaro-Winkler, Levenshtein ratio, token-set overlap and
 * partial-date proximity. All return a value in [0, 1].
 */
public final class Similarity {

    private Similarity() {
    }

    /** Lower-cased, accent-free, punctuation-free text with single spaces (any script). */
    public static String normalise(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return n.replaceAll("\\s+", " ");
    }

    public static List<String> tokens(String s) {
        String n = normalise(s);
        return n.isEmpty() ? List.of() : Arrays.asList(n.split(" "));
    }

    public static double exact(String a, String b) {
        return normalise(a).equals(normalise(b)) ? 1.0 : 0.0;
    }

    public static double jaroWinkler(String s1, String s2) {
        String a = normalise(s1);
        String b = normalise(s2);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        int range = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] ma = new boolean[a.length()];
        boolean[] mb = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int lo = Math.max(0, i - range);
            int hi = Math.min(b.length() - 1, i + range);
            for (int j = lo; j <= hi; j++) {
                if (!mb[j] && a.charAt(i) == b.charAt(j)) {
                    ma[i] = true;
                    mb[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int t = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (ma[i]) {
                while (!mb[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    t++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / a.length() + m / b.length() + (m - t / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(a.length(), b.length())); i++) {
            if (a.charAt(i) == b.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    public static double levenshteinRatio(String s1, String s2) {
        String a = normalise(s1);
        String b = normalise(s2);
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
    }

    /** Jaccard overlap of token sets: tolerant of word order (given/family swaps). */
    public static double tokenSet(String s1, String s2) {
        Set<String> a = new LinkedHashSet<>(tokens(s1));
        Set<String> b = new LinkedHashSet<>(tokens(s2));
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    /**
     * Partial ISO dates ({@code yyyy}, {@code yyyy-MM}, {@code yyyy-MM-dd}): 1 when equal at the shared precision
     * with full precision on both sides, 0.8 when equal at a coarser shared precision, 0.6 for transposed day and
     * month, 0.4 when the years differ by one, else 0.
     */
    public static double dateProximity(String d1, String d2) {
        if (d1 == null || d2 == null || d1.isBlank() || d2.isBlank()) {
            return 0;
        }
        String a = d1.trim();
        String b = d2.trim();
        int shared = Math.min(a.length(), b.length());
        if (a.substring(0, shared).equals(b.substring(0, shared))) {
            return a.length() == 10 && b.length() == 10 ? 1.0 : 0.8;
        }
        if (a.length() == 10 && b.length() == 10 && a.substring(0, 4).equals(b.substring(0, 4))
                && a.substring(5, 7).equals(b.substring(8, 10)) && a.substring(8, 10).equals(b.substring(5, 7))) {
            return 0.6;
        }
        try {
            int ya = Integer.parseInt(a.substring(0, 4));
            int yb = Integer.parseInt(b.substring(0, 4));
            return Math.abs(ya - yb) == 1 ? 0.4 : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static double compare(String method, String a, String b) {
        return switch (method == null ? "EXACT" : method.toUpperCase()) {
            case "JARO_WINKLER" -> jaroWinkler(a, b);
            case "LEVENSHTEIN" -> levenshteinRatio(a, b);
            case "TOKEN_SET" -> tokenSet(a, b);
            case "DATE" -> dateProximity(a, b);
            default -> exact(a, b);
        };
    }
}
