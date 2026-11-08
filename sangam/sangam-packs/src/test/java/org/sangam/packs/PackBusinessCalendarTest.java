// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.TenantWorkingDays;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.WorkingDayCalendar;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * SL-34: a running pack counts the tenant's own working days. The calendar port reads the tenant's working days from
 * its configuration source (in the server: the profile business calendar); a holiday added there moves the due date
 * of a pack SLA counted in working days, with no change to the pack.
 */
class PackBusinessCalendarTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Set<LocalDate> HOLIDAYS = ConcurrentHashMap.newKeySet();
    static ProcessEngine engine;
    static PackDriver driver;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        TenantWorkingDays tenantDays = tenant -> Optional.of(new TenantWorkingDays.Days(IST, PackBusinessCalendarTest::working));
        PackRuntime.install(PackRuntime.ports().withCalendar(new WorkingDayCalendar(IST, tenantDays)));
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-calendar;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(null);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    static boolean working(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !HOLIDAYS.contains(d);
    }

    /** Runs a pension case and returns the first working-day SLA it planned (spec, due). */
    static PackAuditEvent firstWorkingDaySla(String bk) {
        engine.getRuntimeService().createProcessInstanceByKey("sangam-pension-sanction").businessKey(bk)
                .setVariables(AllPacksSmokeTest.bag()).processDefinitionWithoutTenantId().execute();
        driver.run(bk, new FormAnswers(engine)::answer, 80);
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk).stream()
                .filter(e -> "SLA_STARTED".equals(e.type()))
                .filter(e -> String.valueOf(e.details().get("spec")).matches("\\d+WD"))
                .findFirst().orElse(null);
    }

    @Test
    void tenantHolidayMovesThePackDueDate() {
        HOLIDAYS.clear();
        PackAuditEvent before = firstWorkingDaySla("CAL/plain");
        assertNotNull(before, "the pension case plans a working-day SLA");
        String spec = before.details().get("spec").toString();
        LocalDate dueBefore = Instant.parse(before.details().get("dueAt").toString()).atZone(IST).toLocalDate();

        // a gazetted holiday on the next working day, added to the tenant configuration only
        LocalDate next = LocalDate.now(IST).plusDays(1);
        while (!working(next)) {
            next = next.plusDays(1);
        }
        HOLIDAYS.add(next);
        PackAuditEvent after = firstWorkingDaySla("CAL/holiday");
        LocalDate dueAfter = Instant.parse(after.details().get("dueAt").toString()).atZone(IST).toLocalDate();

        assertEquals(spec, after.details().get("spec"), "same pack, same SLA");
        assertNotEquals(dueBefore, dueAfter, "the holiday moved the due date");
        LocalDate expected = dueBefore.plusDays(1);
        while (!working(expected)) {
            expected = expected.plusDays(1);
        }
        assertEquals(expected, dueAfter, "exactly one working day later (" + spec + ", holiday " + next + ")");
        HOLIDAYS.clear();
    }
}
