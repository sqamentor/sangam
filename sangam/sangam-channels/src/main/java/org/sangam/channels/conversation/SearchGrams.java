// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.conversation;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Trigram index of message bodies (NG-12). A message is indexed by every distinct run of three code points of its
 * lower-cased body ({@link Locale#ROOT}), so any needle of three or more code points can only occur in a message that has
 * all of the needle's trigrams. The index narrows the candidates; the substring condition itself is still applied, so the
 * answer is the same as the substring scan's. Needles shorter than three code points use the scan.
 *
 * <p>A gram is stored as the lower-case hex of its UTF-8 bytes, so every database compares it byte for byte: a case-, accent-
 * or width-insensitive collation (the SQL Server default, for example) can never merge two grams into one key.
 */
final class SearchGrams {

    static final int N = 3;
    /** Enough grams to make the candidate set small; using fewer than all never loses a match, it only narrows less. */
    static final int MAX_QUERY_GRAMS = 32;

    private SearchGrams() {
    }

    /** The distinct trigrams of a text as hex keys, in first-seen order. */
    static Set<String> of(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        int[] cps = text.toLowerCase(Locale.ROOT).codePoints().toArray();
        for (int i = 0; i + N <= cps.length; i++) {
            out.add(HexFormat.of().formatHex(new String(cps, i, N).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    /** The grams a search uses: at most {@link #MAX_QUERY_GRAMS} of the needle's distinct trigrams. */
    static Set<String> forQuery(String needle) {
        Set<String> all = of(needle);
        if (all.size() <= MAX_QUERY_GRAMS) {
            return all;
        }
        Set<String> some = new LinkedHashSet<>();
        for (String g : all) {
            if (some.size() == MAX_QUERY_GRAMS) {
                break;
            }
            some.add(g);
        }
        return some;
    }
}
