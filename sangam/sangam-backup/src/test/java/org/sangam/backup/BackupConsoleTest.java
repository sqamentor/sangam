// SPDX-License-Identifier: Apache-2.0
package org.sangam.backup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Decision 155 console rules: reason, known target, one active run per target, audit before the platform call. */
class BackupConsoleTest {

    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    static final BackupTargets.Target PG = new BackupTargets.Target("prod-pg-full", "pg-full", "15 1 * * *", "Etc/UTC",
            false, 0, null, null);

    /** Records calls; {@code startFails} and {@code exists} steer the platform answer. */
    static final class Stub implements BackupTargets {
        final List<Run> runs = new ArrayList<>();
        final List<String> started = new ArrayList<>();
        boolean startFails;
        boolean exists;

        @Override
        public List<Target> targets() {
            return List.of(PG);
        }

        @Override
        public List<Run> runs(String targetId) {
            return runs.stream().filter(r -> targetId == null || r.target().equals(targetId)).toList();
        }

        @Override
        public String runId(Target target, Instant now) {
            return target.id() + "-m" + now.getEpochSecond();
        }

        @Override
        public Optional<Run> start(Target target, String runId) throws IOException {
            if (startFails) {
                throw new IOException("Kubernetes API answered 403: jobs.batch is forbidden");
            }
            if (exists) {
                return Optional.empty();
            }
            started.add(runId);
            return Optional.of(new Run(runId, target.id(), Trigger.MANUAL, Status.PENDING, null, null, null, null));
        }
    }

    record Event(String chain, String type, Map<String, Object> payload) {
    }

    final Stub stub = new Stub();
    final BackupConsole console = new BackupConsole(stub, Clock.fixed(NOW, ZoneOffset.UTC));
    final List<Event> events = new ArrayList<>();
    final BackupConsole.Audit audit = (chain, type, payload) -> events.add(new Event(chain, type, payload));

    @Test
    void aTriggerIsAuditedFirstThenStarted() {
        BackupTargets.Run run = console.trigger("prod-pg-full", "ops-1", " nightly missed ", audit);
        assertEquals("prod-pg-full-m" + NOW.getEpochSecond(), run.id());
        assertEquals(List.of(run.id()), stub.started);
        assertEquals(1, events.size());
        Event e = events.get(0);
        assertEquals("backup:prod-pg-full", e.chain());
        assertEquals(BackupConsole.EVENT_TRIGGERED, e.type());
        assertEquals("nightly missed", e.payload().get("reason"));
        assertEquals("ops-1", e.payload().get("actor"));
        assertEquals(run.id(), e.payload().get("run"));
    }

    @Test
    void withoutTheAuditEventNothingStarts() {
        BackupConsole.Audit broken = (c, t, p) -> {
            throw new IllegalStateException("audit store unavailable");
        };
        assertThrows(IllegalStateException.class, () -> console.trigger("prod-pg-full", "ops-1", "x", broken));
        assertTrue(stub.started.isEmpty(), "fail closed");
    }

    @Test
    void aReasonIsRequiredAndBounded() {
        BackupException blank = assertThrows(BackupException.class, () -> console.trigger("prod-pg-full", "ops-1", " ", audit));
        assertEquals("REASON_REQUIRED", blank.code());
        assertEquals(BackupException.Kind.INVALID, blank.kind());
        BackupException tooLong = assertThrows(BackupException.class,
                () -> console.trigger("prod-pg-full", "ops-1", "r".repeat(BackupConsole.MAX_REASON + 1), audit));
        assertEquals(BackupException.Kind.INVALID, tooLong.kind());
        assertTrue(events.isEmpty());
        assertTrue(stub.started.isEmpty());
    }

    @Test
    void onlyAKnownTargetCanBeStarted() {
        BackupException e = assertThrows(BackupException.class, () -> console.trigger("kube-system-thing", "ops-1", "x", audit));
        assertEquals(BackupException.Kind.NOT_FOUND, e.kind());
        assertTrue(events.isEmpty());
        assertThrows(BackupException.class, () -> console.runs("kube-system-thing"));
    }

    @Test
    void aTargetWithAnActiveRunIsNotStartedAgain() {
        stub.runs.add(new BackupTargets.Run("prod-pg-full-290", "prod-pg-full", BackupTargets.Trigger.SCHEDULED,
                BackupTargets.Status.RUNNING, NOW.minusSeconds(60), null, null, null));
        BackupException e = assertThrows(BackupException.class, () -> console.trigger("prod-pg-full", "ops-1", "x", audit));
        assertEquals("BACKUP_RUNNING", e.code());
        assertEquals(BackupException.Kind.CONFLICT, e.kind());
        assertTrue(events.isEmpty(), "a refused trigger writes no event");
        assertTrue(stub.started.isEmpty());
    }

    @Test
    void aPlatformFailureIsAuditedAndReported() {
        stub.startFails = true;
        BackupException e = assertThrows(BackupException.class, () -> console.trigger("prod-pg-full", "ops-1", "x", audit));
        assertEquals(BackupConsole.TRIGGER_FAILED, e.code());
        assertEquals(BackupException.Kind.UPSTREAM, e.kind());
        assertEquals(List.of(BackupConsole.EVENT_TRIGGERED, BackupConsole.EVENT_FAILED), events.stream().map(Event::type).toList());
        assertTrue(String.valueOf(events.get(1).payload().get("message")).contains("403"));
    }

    @Test
    void aDuplicateRunIdIsAConflictAndAudited() {
        stub.exists = true;
        BackupException e = assertThrows(BackupException.class, () -> console.trigger("prod-pg-full", "ops-1", "x", audit));
        assertEquals("BACKUP_RUNNING", e.code());
        assertEquals(List.of(BackupConsole.EVENT_TRIGGERED, BackupConsole.EVENT_FAILED), events.stream().map(Event::type).toList());
    }

    @Test
    void runsAreNewestFirstWithPendingOnTop() {
        stub.runs.add(new BackupTargets.Run("a", "prod-pg-full", BackupTargets.Trigger.SCHEDULED, BackupTargets.Status.SUCCEEDED,
                NOW.minusSeconds(7200), NOW.minusSeconds(7000), null, null));
        stub.runs.add(new BackupTargets.Run("b", "prod-pg-full", BackupTargets.Trigger.SCHEDULED, BackupTargets.Status.SUCCEEDED,
                NOW.minusSeconds(3600), NOW.minusSeconds(3500), null, null));
        stub.runs.add(new BackupTargets.Run("c", "prod-pg-full", BackupTargets.Trigger.MANUAL, BackupTargets.Status.PENDING,
                null, null, null, null));
        assertEquals(List.of("c", "b", "a"), console.runs(null).stream().map(BackupTargets.Run::id).toList());
        assertEquals(3, console.runs("prod-pg-full").size());
    }
}
