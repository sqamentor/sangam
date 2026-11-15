// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.engine;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/**
 * Decision 333: the text format of the engine local-date type, version 1 ({@value #FORMAT_VERSION}), the same shape
 * decision 138 gave decimals ({@link DecimalText}).
 *
 * <p><b>Format v1.</b> ISO-8601 calendar date {@code uuuu-MM-dd}: exactly four year digits, a two-digit month and a
 * two-digit day, separated by {@code -}, ten characters, e.g. {@code 2026-03-31}. It is {@link LocalDate#toString()}
 * for every year it accepts. Because every stored text has the same width and the same separators, ordering the text
 * is chronological order; the engine's range queries compare {@code TEXT_} as text and are therefore correct.
 *
 * <p><b>Range.</b> Years {@value #MIN_YEAR} to {@value #MAX_YEAR}. A {@link LocalDate} outside it is <b>refused</b>
 * ({@code LOCAL_DATE_OUT_OF_RANGE}), never written: {@code LocalDate.toString()} gives such a year a sign and more
 * digits ({@code +10000-01-01}, {@code -0001-01-01}), which would no longer sort chronologically as text.
 *
 * <p><b>Inputs</b> ({@link #parse}): format v1 text only, resolved strictly: no date-time, no offset or zone, no
 * blanks, no week or ordinal dates, no sign, no lenient day ({@code 2026-02-30} and {@code 2026-02-29} are refused).
 * {@link #exact} also takes a {@link LocalDate}; every other type is refused. A string variable is never turned into a
 * date by this type: callers decide to send a typed date.
 */
public final class LocalDateText {

    /** The format version, part of the persisted type id {@code sangam-local-date-v1}. */
    public static final int FORMAT_VERSION = 1;
    /** The earliest accepted year. */
    public static final int MIN_YEAR = 0;
    /** The latest accepted year. */
    public static final int MAX_YEAR = 9999;

    static final Pattern INPUT = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final DateTimeFormatter STRICT = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private LocalDateText() {
    }

    /** The format v1 text of {@code value}; refuses {@code null} and a year outside {@value #MIN_YEAR}..{@value #MAX_YEAR}. */
    public static String format(LocalDate value) {
        if (value == null) {
            throw new LocalDateFormatException("LOCAL_DATE_NULL", "a date value is required");
        }
        if (value.getYear() < MIN_YEAR || value.getYear() > MAX_YEAR) {
            throw new LocalDateFormatException("LOCAL_DATE_OUT_OF_RANGE", "the year of " + value + " is outside "
                    + MIN_YEAR + ".." + MAX_YEAR + "; such a date cannot be stored in sortable ISO text");
        }
        return value.toString();
    }

    /** Parses format v1 text strictly (see the class comment). */
    public static LocalDate parse(String text) {
        if (text == null) {
            throw new LocalDateFormatException("LOCAL_DATE_NULL", "date text is required");
        }
        if (!INPUT.matcher(text).matches()) {
            throw new LocalDateFormatException("LOCAL_DATE_INVALID", "not an ISO date (uuuu-MM-dd): '"
                    + abbreviate(text) + "'");
        }
        try {
            LocalDate d = LocalDate.parse(text, STRICT);
            if (!format(d).equals(text)) {
                throw new LocalDateFormatException("LOCAL_DATE_INVALID", "not an ISO date (uuuu-MM-dd): '" + text + "'");
            }
            return d;
        } catch (DateTimeException e) {
            throw new LocalDateFormatException("LOCAL_DATE_INVALID", "not a calendar date: '" + text + "'");
        }
    }

    /** The date of a value: a {@link LocalDate} (range checked) or format v1 text; anything else is refused. */
    public static LocalDate exact(Object value) {
        if (value instanceof LocalDate d) {
            format(d);
            return d;
        }
        if (value instanceof String s) {
            return parse(s);
        }
        throw new LocalDateFormatException("LOCAL_DATE_UNSUPPORTED", "cannot convert "
                + (value == null ? "null" : value.getClass().getName()) + " to a date");
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    /** A refused date, with a stable code. */
    public static final class LocalDateFormatException extends IllegalArgumentException {
        private final String code;

        public LocalDateFormatException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
