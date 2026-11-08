// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.support;

import org.sangam.core.layer.Hashes;

import java.security.SecureRandom;

/**
 * Verification codes for public verification of instruments, extracts and receipts: 20 random Crockford base32
 * characters (100 bits) plus one check character (mod 37), grouped for reading aloud. Only the SHA-256 of the
 * normalised code is stored, so a database reader cannot rebuild verification links. Also hosts the generic check
 * digit algorithms used by number formats and identifier types.
 */
public final class Codes {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final String CHECK = ALPHABET + "*~$=U";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Codes() {
    }

    public static String newCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(32)));
        }
        sb.append(check(sb));
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < sb.length(); i++) {
            if (i > 0 && i % 7 == 0) {
                grouped.append('-');
            }
            grouped.append(sb.charAt(i));
        }
        return grouped.toString();
    }

    /** Upper-cases, drops separators and maps the Crockford look-alikes (O to 0, I and L to 1). */
    public static String normalise(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : code.toUpperCase().toCharArray()) {
            if (ch == '-' || ch == ' ') {
                continue;
            }
            sb.append(switch (ch) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> ch;
            });
        }
        return sb.toString();
    }

    /** True when the code has the right length and check character (rejects typos before a lookup). */
    public static boolean wellFormed(String code) {
        String n = normalise(code);
        if (n.length() != 21) {
            return false;
        }
        for (int i = 0; i < 20; i++) {
            if (ALPHABET.indexOf(n.charAt(i)) < 0) {
                return false;
            }
        }
        return check(n.substring(0, 20)) == n.charAt(20);
    }

    /** Stored lookup key of a code. */
    public static String hash(String code) {
        return Hashes.hex(Hashes.sha256("sangam-verify-v1:" + normalise(code)));
    }

    private static char check(CharSequence s) {
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            v = (v * 32 + ALPHABET.indexOf(s.charAt(i))) % 37;
        }
        return CHECK.charAt((int) v);
    }

    /** Luhn mod-10 check digit of the digits in a string. */
    public static int luhn(String digits) {
        int sum = 0;
        boolean dbl = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char ch = digits.charAt(i);
            if (!Character.isDigit(ch)) {
                continue;
            }
            int d = ch - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    /** True when the last digit is a valid Luhn check digit of the preceding digits. */
    public static boolean luhnValid(String value) {
        String d = value.replaceAll("\\D", "");
        return d.length() >= 2 && luhn(d.substring(0, d.length() - 1)) == d.charAt(d.length() - 1) - '0';
    }

    private static final int[][] VERHOEFF_D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] VERHOEFF_P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    /** Verhoeff checksum validity of a digit string (check digit last). */
    public static boolean verhoeffValid(String value) {
        String d = value.replaceAll("\\D", "");
        if (d.isEmpty()) {
            return false;
        }
        int c = 0;
        for (int i = 0; i < d.length(); i++) {
            c = VERHOEFF_D[c][VERHOEFF_P[i % 8][d.charAt(d.length() - 1 - i) - '0']];
        }
        return c == 0;
    }

    /** ISO 7064 mod 97-10 check digits (two) of an alphanumeric string. */
    public static String mod97(String s) {
        return String.format("%02d", 98 - mod97Remainder(s + "00"));
    }

    /** ISO 7064 mod 97-10 validity: the whole string (check digits included) has remainder 1. */
    public static boolean mod97Valid(String s) {
        return mod97Remainder(s) == 1;
    }

    private static int mod97Remainder(String s) {
        StringBuilder digits = new StringBuilder();
        for (char ch : s.toUpperCase().toCharArray()) {
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else if (ch >= 'A' && ch <= 'Z') {
                digits.append(ch - 'A' + 10);
            }
        }
        int r = 0;
        for (int i = 0; i < digits.length(); i++) {
            r = (r * 10 + (digits.charAt(i) - '0')) % 97;
        }
        return r;
    }

    /** Weighted mod-11 validity (weights 2..7 repeating from the right, check digit last; 10 written as X). */
    public static boolean mod11Valid(String value) {
        String v = value.replaceAll("[^0-9Xx]", "").toUpperCase();
        if (v.length() < 2) {
            return false;
        }
        int sum = 0;
        int w = 2;
        for (int i = v.length() - 2; i >= 0; i--) {
            if (!Character.isDigit(v.charAt(i))) {
                return false;
            }
            sum += (v.charAt(i) - '0') * w;
            w = w == 7 ? 2 : w + 1;
        }
        int check = (11 - sum % 11) % 11;
        char last = v.charAt(v.length() - 1);
        int lastVal = last == 'X' ? 10 : last - '0';
        return check == lastVal;
    }
}
