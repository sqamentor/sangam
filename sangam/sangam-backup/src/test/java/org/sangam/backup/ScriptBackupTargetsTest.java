// SPDX-License-Identifier: Apache-2.0
package org.sangam.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Owner decision 220: the VM/compose backup adapter runs the configured command on demand, keeps the run history as
 * files, never runs one target twice (also across servers sharing the directory) and recovers from a server that
 * stopped mid-run. A fake runner stands in for the process; the console rules are the same as on Kubernetes.
 * Written, not run.
 */
class ScriptBackupTargetsTest {

    /** An absolute path on every platform (a Unix path is not absolute on Windows). */
    static final String SCRIPT = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("pg-full.sh").toString();

    @TempDir
    Path dir;

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-13T02:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MovingClock clock = new MovingClock();
    final List<Runnable> queued = new ArrayList<>();
    final Executor later = queued::add;
    final List<Map<String, String>> envs = new ArrayList<>();
    ScriptBackupTargets.Outcome next = new ScriptBackupTargets.Outcome(false, 0);

    final ScriptBackupTargets.Runner fake = (command, env, log, timeout) -> {
        envs.add(env);
        Files.writeString(log, "pg_basebackup: done\n");
        return next;
    };

    static ScriptBackupTargets.ScriptTarget pg() {
        return new ScriptBackupTargets.ScriptTarget("pg-full", "PostgreSQL full", List.of(SCRIPT, "--gzip"),
                "0 1 * * *", Duration.ofHours(1));
    }

    ScriptBackupTargets adapter(Executor executor) throws Exception {
        Map<String, String> host = new HashMap<>();
        host.put("PATH", "/usr/bin");
        host.put("PGPASSWORD", "from-the-host");
        host.put("SANGAM_DB_PASSWORD", "not-listed");
        return new ScriptBackupTargets(List.of(pg()), dir, Set.of("PGPASSWORD"), host, fake, executor, clock);
    }

    private void drain() {
        while (!queued.isEmpty()) {
            queued.remove(0).run();
        }
    }

    @Test
    void aTriggerRunsTheCommandAndRecordsPendingThenTheOutcome() throws Exception {
        ScriptBackupTargets t = adapter(later);
        BackupConsole console = new BackupConsole(t, clock);
        List<String> audit = new ArrayList<>();
        BackupTargets.Run run = console.trigger("pg-full", "ops-1", "before the upgrade", (chain, type, p) -> audit.add(type));
        assertEquals(BackupTargets.Status.PENDING, run.status());
        assertEquals(List.of(BackupConsole.EVENT_TRIGGERED), audit);
        assertEquals(1, t.targets().get(0).active());
        BackupException busy = assertThrows(BackupException.class,
                () -> console.trigger("pg-full", "ops-2", "again", (chain, type, p) -> audit.add(type)));
        assertEquals("BACKUP_RUNNING", busy.code());

        drain();
        BackupTargets.Run done = t.runs("pg-full").get(0);
        assertEquals(BackupTargets.Status.SUCCEEDED, done.status());
        assertEquals(BackupTargets.Trigger.MANUAL, done.trigger());
        assertEquals(clock.now, done.finishedAt());
        assertEquals(clock.now, t.targets().get(0).lastSuccessfulTime());
        assertFalse(Files.exists(dir.resolve("pg-full.lock")), "the lock is released after the final record");
        assertTrue(Files.exists(dir.resolve(run.id() + ".log")));
    }

    @Test
    void theCommandGetsOnlyTheAllowListedEnvironment() throws Exception {
        ScriptBackupTargets t = adapter(Runnable::run);
        t.start(t.targets().get(0), "pg-full-m1");
        Map<String, String> env = envs.get(0);
        assertEquals("from-the-host", env.get("PGPASSWORD"));
        assertEquals("/usr/bin", env.get("PATH"));
        assertNull(env.get("SANGAM_DB_PASSWORD"), "variables not listed never reach the script");
        assertEquals("pg-full", env.get("SANGAM_BACKUP_TARGET"));
        assertEquals("pg-full-m1", env.get("SANGAM_BACKUP_RUN_ID"));
    }

    @Test
    void aNonZeroExitOrTheTimeoutIsFailedWithoutTheLogContent() throws Exception {
        ScriptBackupTargets t = adapter(Runnable::run);
        next = new ScriptBackupTargets.Outcome(false, 3);
        t.start(t.targets().get(0), "pg-full-m1");
        BackupTargets.Run failed = t.runs("pg-full").get(0);
        assertEquals(BackupTargets.Status.FAILED, failed.status());
        assertTrue(failed.message().startsWith("exit code 3"));
        assertFalse(failed.message().contains("pg_basebackup"), "the log stays on the server");
        clock.now = clock.now.plusSeconds(60);
        next = new ScriptBackupTargets.Outcome(true, -1);
        t.start(t.targets().get(0), "pg-full-m2");
        assertTrue(t.runs("pg-full").stream().anyMatch(r -> r.id().equals("pg-full-m2") && r.message().contains("timeout")));
    }

    @Test
    void twoServersSharingTheDirectoryNeverRunATargetTwice() throws Exception {
        ScriptBackupTargets a = adapter(later);
        ScriptBackupTargets b = adapter(later);
        assertTrue(a.start(a.targets().get(0), "pg-full-m1").isPresent());
        assertTrue(b.start(b.targets().get(0), "pg-full-m2").isEmpty(), "the lock file is held by the first server");
        assertTrue(b.start(b.targets().get(0), "pg-full-m1").isEmpty(), "the same run id is never started twice");
        drain();
        assertTrue(b.start(b.targets().get(0), "pg-full-m3").isPresent(), "free again after the first run ended");
    }

    @Test
    void aRunOfAServerThatStoppedTurnsFailedAfterItsTimeoutAndTheLockIsTakenOver() throws Exception {
        ScriptBackupTargets t = adapter(later);
        t.start(t.targets().get(0), "pg-full-m1");
        queued.clear();                                         // the server stopped: the run never executes
        assertTrue(t.runs("pg-full").get(0).active());
        clock.now = clock.now.plus(Duration.ofHours(1)).plus(ScriptBackupTargets.GRACE).plusSeconds(1);
        BackupTargets.Run shown = t.runs("pg-full").get(0);
        assertEquals(BackupTargets.Status.FAILED, shown.status());
        assertTrue(shown.message().startsWith("abandoned"));
        assertTrue(t.start(t.targets().get(0), "pg-full-m2").isPresent(), "the stale lock is taken over");
    }

    @Test
    void aRejectedStartIsRecordedAsFailedAndReported() throws Exception {
        ScriptBackupTargets t = adapter(r -> {
            throw new java.util.concurrent.RejectedExecutionException("shutting down");
        });
        assertThrows(java.io.IOException.class, () -> t.start(t.targets().get(0), "pg-full-m1"));
        assertEquals(BackupTargets.Status.FAILED, t.runs("pg-full").get(0).status());
        assertFalse(Files.exists(dir.resolve("pg-full.lock")));
    }

    @Test
    void unreadableFilesInTheDirectoryAreIgnored() throws Exception {
        ScriptBackupTargets t = adapter(later);
        Files.writeString(dir.resolve("junk.json"), "{not json");
        Files.writeString(dir.resolve("other-m1.json"), "{\"id\":\"other-m1\",\"target\":\"other\",\"trigger\":\"MANUAL\",\"status\":\"RUNNING\"}");
        assertEquals(List.of(), t.runs(null), "unknown targets and broken files are not shown");
    }

    @Test
    void configurationIsCheckedAtStartUp() {
        assertThrows(IllegalArgumentException.class, () -> new ScriptBackupTargets.ScriptTarget("pg-full", null,
                List.of("pg-full.sh"), null, Duration.ofHours(1)), "relative command");
        assertThrows(IllegalArgumentException.class, () -> new ScriptBackupTargets.ScriptTarget("PG Full", null,
                List.of(SCRIPT), null, Duration.ofHours(1)), "id");
        assertThrows(IllegalArgumentException.class, () -> new ScriptBackupTargets.ScriptTarget("pg-full", null,
                List.of(SCRIPT), null, Duration.ofHours(25)), "timeout");
        assertThrows(IllegalArgumentException.class, () -> new ScriptBackupTargets(List.of(pg(), pg()), dir, Set.of(),
                Map.of(), fake, later, clock), "duplicate id");
        assertThrows(IllegalArgumentException.class, () -> new ScriptBackupTargets(List.of(pg()), Path.of("relative"),
                Set.of(), Map.of(), fake, later, clock));
        assertThrows(IllegalArgumentException.class, () -> new ScriptBackupTargets(List.of(pg()), dir, Set.of("BAD NAME"),
                Map.of(), fake, later, clock));
    }

    @Test
    void targetsAreReadFromFlatSettings() {
        Map<String, String> s = Map.of(
                "p.targets[0].id", "pg-full", "p.targets[0].name", "PostgreSQL full",
                "p.targets[0].command[0]", SCRIPT, "p.targets[0].command[1]", "--gzip",
                "p.targets[0].timeout", "PT3H",
                "p.targets[1].id", "pg-logical", "p.targets[1].command[0]", SCRIPT);
        List<ScriptBackupTargets.ScriptTarget> t = ScriptBackupTargets.targetsFrom("p", s::get);
        assertEquals(2, t.size());
        assertEquals(List.of(SCRIPT, "--gzip"), t.get(0).command());
        assertEquals(Duration.ofHours(3), t.get(0).timeout());
        assertEquals("pg-logical", t.get(1).name(), "the id is the name when none is set");
        assertEquals(Duration.ofHours(2), t.get(1).timeout());
    }
}
