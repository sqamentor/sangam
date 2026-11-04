// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.id;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Identity of a cell (SCALE-ARCHITECTURE.md section 6). Format: {@code <region>-<nn>}, lower case, 3..16 chars,
 * for example {@code in-rj-01} or {@code in-c-01}. The cell id is the prefix of every global id minted in that cell,
 * so it is immutable for the life of the cell and is never reused after a cell is retired.
 */
public record CellId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]{0,6}(-[a-z0-9]{1,6}){0,2}");

    public CellId {
        if (value == null) {
            throw new IllegalArgumentException("cell id is required");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() < 2 || value.length() > 16 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid cell id '" + value + "': expected e.g. in-rj-01 (max 16 chars)");
        }
    }

    public static CellId of(String value) {
        return new CellId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
