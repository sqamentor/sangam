// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.retention;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Purpose-based retention of one purpose. The erase date of an item is the latest of:
 * <ol>
 *   <li>anchor + {@code retention} (anchor depends on the {@link Trigger});</li>
 *   <li>last processing + the minimum retention (pack limit {@code minimumRetentionDays}; IN: DPDP r.8(3));</li>
 *   <li>for a pack retention class (IN: Third Schedule): last approach + the class period, and never before the
 *       class floor (IN: commencement + period);</li>
 *   <li>a record-class retention end from the records retention schedule (IN: Public Records Act s.8).</li>
 * </ol>
 */
public record RetentionSchedule(String purposeCode, Trigger trigger, Duration retention, Duration classPeriod, Instant classFloorFrom,
                                String recordClass, Duration recordRetention, String recordRetentionRef, boolean preErasureNotice) {

    public enum Trigger {
        /** The specified purpose is served (the workflow completed). */
        PURPOSE_COMPLETED,
        /** The data subject last approached the controller or exercised a right. */
        LAST_INTERACTION,
        /** Consent withdrawn (always applied on withdrawal for consent purposes). */
        CONSENT_WITHDRAWN,
        /** Collection date. */
        COLLECTION
    }

    public RetentionSchedule {
        if (purposeCode == null || purposeCode.isBlank()) {
            throw new IllegalArgumentException("purposeCode is required");
        }
        trigger = trigger == null ? Trigger.PURPOSE_COMPLETED : trigger;
        retention = retention == null ? Duration.ZERO : retention;
        if (retention.isNegative()) {
            throw new IllegalArgumentException("retention must not be negative");
        }
    }

    public Instant eraseAt(Instant anchor, Instant lastProcessed, Instant lastApproach, Instant recordCreated, Duration minimumRetention) {
        if (anchor == null) {
            return null;
        }
        Instant due = anchor.plus(retention);
        if (lastProcessed != null && minimumRetention != null) {
            due = max(due, lastProcessed.plus(minimumRetention));
        }
        if (classPeriod != null) {
            Instant approach = lastApproach == null ? anchor : lastApproach;
            due = max(due, approach.plus(classPeriod));
            if (classFloorFrom != null) {
                due = max(due, classFloorFrom.plus(classPeriod));
            }
        }
        if (recordRetention != null && recordCreated != null) {
            due = max(due, recordCreated.plus(recordRetention));
        }
        return due;
    }

    public RetentionSchedule withdrawn() {
        return new RetentionSchedule(purposeCode, Trigger.CONSENT_WITHDRAWN, Duration.ZERO, classPeriod, classFloorFrom, recordClass,
                recordRetention, recordRetentionRef, preErasureNotice);
    }

    static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    public static Duration years(int years) {
        LocalDate base = LocalDate.of(2000, 1, 1);
        return Duration.ofDays(base.plusYears(years).toEpochDay() - base.toEpochDay());
    }
}
