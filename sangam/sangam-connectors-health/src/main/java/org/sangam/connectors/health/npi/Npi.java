// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.npi;

/**
 * NPI format and check digit (US-HLT-095): 10 digits; the 10th is the Luhn check digit of the first 9 prefixed with
 * {@code 80840} (CMS "NPI check digit" specification). Registry lookup (US-HLT-096) is an HTTP GET to the NPPES API
 * through the generic REST connector of sangam-integration; no special connector is needed.
 */
public final class Npi {

    private Npi() {
    }

    public static boolean valid(String npi) {
        if (npi == null || !npi.matches("\\d{10}")) {
            return false;
        }
        return checkDigit(npi.substring(0, 9)) == npi.charAt(9) - '0';
    }

    /** Check digit of the first nine digits. */
    public static int checkDigit(String first9) {
        String s = "80840" + first9;
        int sum = 0;
        boolean dbl = true; // rightmost digit of the payload is doubled
        for (int i = s.length() - 1; i >= 0; i--) {
            int d = s.charAt(i) - '0';
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
}
