// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.sangam.packs.core.MoneyCalculationDelegate.refused;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.sangam.packs.port.PackRuntime;

/**
 * Decisions 280 and 288: the evidence and permitted use of a rounding policy row or a versioned rules row, read by
 * {@link MoneyCalculationDelegate}. Evidence ({@code evidenceStatus}) is {@value #VERIFIED} only when the primary text
 * was read and checked against the rule as implemented, otherwise {@value #UNVERIFIED}; use ({@code permittedUse}) is
 * {@value #PRODUCTION} only with {@value #VERIFIED} evidence, a citation and an explicit effective date, otherwise
 * {@value #SYNTHETIC_DEMO_ONLY}. A real but unproven rule is {@value #UNVERIFIED} + {@value #SYNTHETIC_DEMO_ONLY},
 * never labelled fictitious; its pack declares it a placeholder, so the readiness check blocks go-live. packgen
 * rounding.py {@code check_evidence} applies the same rules when it generates a row. Decision 292: in production mode
 * ({@link PackRuntime#production()}, set by the server from its fail-closed production setting) a row whose use is not
 * {@value #PRODUCTION} is refused ({@code <prefix>_NOT_PERMITTED_IN_PRODUCTION}).
 */
public final class RowEvidence {

    /** The first day of a row with no recorded effective date (packgen rounding.py NO_EFFECTIVE_DATE, decision 280). */
    public static final String NO_EFFECTIVE_DATE = "1900-01-01";
    public static final String VERIFIED = "VERIFIED";
    public static final String UNVERIFIED = "UNVERIFIED";
    public static final String PRODUCTION = "PRODUCTION";
    public static final String SYNTHETIC_DEMO_ONLY = "SYNTHETIC_DEMO_ONLY";
    /** Decision 292: the refusal code suffix of a row not for PRODUCTION while in production mode. */
    public static final String NOT_PERMITTED_IN_PRODUCTION = "_NOT_PERMITTED_IN_PRODUCTION";

    private RowEvidence() {
    }

    /**
     * The row's {@code evidenceStatus} and {@code permittedUse}, kept apart (decision 288). A row without them (the old
     * single {@code status} column included) or with any other value is refused ({@code <prefix>_INVALID});
     * {@value #PRODUCTION} with evidence that is not {@value #VERIFIED} is refused
     * ({@code <prefix>_VERIFICATION_INCOMPLETE}); then, in production mode (decision 292), a use other than
     * {@value #PRODUCTION} is refused ({@code <prefix>_NOT_PERMITTED_IN_PRODUCTION}). The refusal is a
     * {@link org.operaton.bpm.engine.ProcessEngineException} whose message starts with the code and a colon.
     *
     * @param prefix {@code POLICY} for a rounding policy row, {@code RULES} for a versioned rules row
     * @param what   the row named in the message
     * @return {evidence, use}
     */
    public static String[] evidenceAndUse(Map<String, ?> row, String prefix, String what) {
        Object e = row.get("evidenceStatus");
        Object u = row.get("permittedUse");
        String evidence = e == null ? "" : e.toString().trim();
        String use = u == null ? "" : u.toString().trim();
        if ((!evidence.equals(VERIFIED) && !evidence.equals(UNVERIFIED))
                || (!use.equals(PRODUCTION) && !use.equals(SYNTHETIC_DEMO_ONLY))) {
            throw refused(prefix + "_INVALID", what + " states evidenceStatus '" + evidence + "' and permittedUse '"
                    + use + "': evidenceStatus is VERIFIED or UNVERIFIED and permittedUse is PRODUCTION or"
                    + " SYNTHETIC_DEMO_ONLY (decision 288)" + (row.containsKey("status") ? "; a single status column"
                    + " is no longer read" : ""));
        }
        if (use.equals(PRODUCTION) && !evidence.equals(VERIFIED)) {
            throw refused(prefix + "_VERIFICATION_INCOMPLETE", what + " is for PRODUCTION but its evidence is "
                    + evidence + " (decision 288)");
        }
        if (PackRuntime.production() && !use.equals(PRODUCTION)) {
            throw refused(prefix + NOT_PERMITTED_IN_PRODUCTION, what + " is " + evidence + " for " + use
                    + "; this installation runs in production mode, where only a PRODUCTION row applies (decision"
                    + " 292); deploy the confirmed rule as a tenant override");
        }
        return new String[] {evidence, use};
    }

    /**
     * Decisions 280, 288 and 297: a row for {@value #PRODUCTION} (its evidence is VERIFIED, {@link #evidenceAndUse})
     * applies only when it records its citation (source and provision) and an explicit effective date, and only when
     * {@code checkDay} is on or after that date. {@code checkDay} is the day the row was selected on
     * ({@code MoneyCalculationDelegate} {@code rowsAsOf}): the wage period's last day for a row that applies from the
     * last day ({@code periodRuleBasis LAST_DAY}, decision 255), otherwise its first day, or the business date when no
     * period is stated. Anything else refuses that period. A {@value #SYNTHETIC_DEMO_ONLY} row is not checked here: it
     * is never a production rule, and its pack placeholder blocks go-live.
     */
    static void inForce(String prefix, String what, String use, Map<String, Object> row, String checkDay) {
        if (!PRODUCTION.equals(use)) {
            return;
        }
        Object source = row.get("source");
        Object provision = row.get("provision");
        Object from = row.get("effectiveFrom");
        LocalDate effective;
        try {
            effective = from == null ? null : LocalDate.parse(from.toString().trim());
        } catch (DateTimeParseException e) {
            effective = null;
        }
        if (source == null || source.toString().isBlank() || source.toString().trim().startsWith("none found")
                || provision == null || provision.toString().isBlank() || effective == null
                || effective.toString().equals(NO_EFFECTIVE_DATE)) {
            throw refused(prefix + "_VERIFICATION_INCOMPLETE", what + " is for PRODUCTION but does not record its"
                    + " citation (source and provision) and an explicit effective date (decisions 280, 288)");
        }
        if (LocalDate.parse(checkDay).isBefore(effective)) {
            throw refused(prefix + "_NOT_IN_FORCE_FOR_PERIOD", what + " is in force from " + effective
                    + "; it was selected for " + checkDay + " (decisions 280, 288, 297)");
        }
    }
}
