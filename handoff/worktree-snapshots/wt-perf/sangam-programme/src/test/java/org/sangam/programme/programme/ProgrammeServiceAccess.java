// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.programme;

import java.math.BigDecimal;

/** Test access to package-private ProgrammeService helpers. */
public final class ProgrammeServiceAccess {

    private ProgrammeServiceAccess() {
    }

    public static Long minor(BigDecimal amount, String currency) {
        return ProgrammeService.minor(amount, currency);
    }
}
