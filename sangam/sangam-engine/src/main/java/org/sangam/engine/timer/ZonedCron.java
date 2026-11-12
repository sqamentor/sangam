// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.timer;

import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import operatonjar.impl.com.cronutils.model.Cron;
import operatonjar.impl.com.cronutils.model.CronType;
import operatonjar.impl.com.cronutils.model.definition.CronDefinitionBuilder;
import operatonjar.impl.com.cronutils.model.time.ExecutionTime;
import operatonjar.impl.com.cronutils.parser.CronParser;

/**
 * A cron expression evaluated on the wall clock of the timer zone (SL-53). It is parsed exactly as Operaton parses a
 * cron cycle ({@code CronTimer.parse}: cron-utils with the Spring 5.3 definition, shaded into operaton-engine 2.2.0,
 * PATCHES.md I-17); only the zone differs: Operaton's {@code CronTimer.getDueDate} evaluates in
 * {@code ZoneId.systemDefault()}, which is UTC in the engine JVM, never the business zone.
 * {@code EngineInternalsPinTest#i17_cronParsedAndEvaluatedAsOperatonDoes} pins the parity.
 */
public final class ZonedCron {

    private final ExecutionTime execution;

    private ZonedCron(Cron cron) {
        this.execution = ExecutionTime.forCron(cron);
    }

    /** Parses {@code expression} as Operaton does; a malformed expression throws as {@code CronTimer.parse} does. */
    public static ZonedCron parse(String expression) throws ParseException {
        try {
            return new ZonedCron(new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING53))
                    .parse(expression));
        } catch (IllegalArgumentException | NullPointerException e) {
            ParseException p = new ParseException(e.getMessage(), 0);
            p.initCause(e);
            throw p;
        }
    }

    /** The first execution strictly after {@code after} on the wall clock of {@code zone}; null if there is none. */
    public Instant nextAfter(Instant after, ZoneId zone) {
        return execution.nextExecution(ZonedDateTime.ofInstant(after, zone)).map(ZonedDateTime::toInstant).orElse(null);
    }
}
