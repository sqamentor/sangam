// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.locale;

import org.sangam.config.common.ConfigException;

/**
 * A fiscal operation was refused because no fiscal year is configured (SL-125, owner decision 2026-11-12: fail closed,
 * core-wide). Sangam never assumes a fiscal year: the tenant or its profile must set {@code locale.fiscal}
 * ({@code startMonth}, {@code startDay}, {@code label}). Connectors turn this into a business failure with
 * {@link #CODE}, never a retry, because nothing was posted.
 */
public class FiscalYearNotConfiguredException extends ConfigException {

    public static final String CODE = "FISCAL_YEAR_NOT_CONFIGURED";

    /** The profile path that must be set. */
    public static final String PATH = "locale.fiscal";

    public FiscalYearNotConfiguredException(String message) {
        super(CODE, message);
    }

    /** {@code subject}: the scope / binding or tenant the fiscal year was asked for. */
    public static FiscalYearNotConfiguredException forSubject(String subject) {
        return new FiscalYearNotConfiguredException("no fiscal year is configured for " + subject + ": set " + PATH
                + " (startMonth, startDay, label) in the tenant profile; fiscal operations are refused until then");
    }
}
