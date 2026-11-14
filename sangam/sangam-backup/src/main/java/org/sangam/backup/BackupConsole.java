// SPDX-License-Identifier: Apache-2.0
package org.sangam.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The backup console (owner decision 155, M-102): lists the targets, starts a backup now, and shows the run history.
 * Restore is not offered: it stays the documented offline procedure.
 *
 * <p>Rules, independent of any web framework:
 * <ul>
 *   <li>A trigger needs a reason (at most 1000 characters).</li>
 *   <li>Only a known target can be started; ids are matched against {@link BackupTargets#targets()}, so a caller cannot
 *       name any other object of the platform.</li>
 *   <li>A target with an active run (pending or running) is not started again: {@code 409 BACKUP_RUNNING}. This mirrors
 *       the chart's {@code concurrencyPolicy: Forbid}, which the platform applies only to scheduled runs.</li>
 *   <li>{@link #EVENT_TRIGGERED} is written before the platform is called (fail closed: without the audit event nothing
 *       starts). When the platform does not start the run, {@link #EVENT_FAILED} follows, so every triggered event
 *       has either a run or a failure.</li>
 * </ul>
 */
public final class BackupConsole {

    public static final String EVENT_TRIGGERED = "BACKUP_TRIGGERED";
    public static final String EVENT_FAILED = "BACKUP_TRIGGER_FAILED";
    public static final int MAX_REASON = 1000;
    /** 502 codes: the platform refused or failed to start the run, or could not be read. */
    public static final String TRIGGER_FAILED = "BACKUP_TRIGGER_FAILED";
    public static final String PLATFORM_ERROR = "BACKUP_PLATFORM_ERROR";

    /** Appends a class-B audit event; throws when it cannot (then nothing is started). */
    @FunctionalInterface
    public interface Audit {
        void record(String chainId, String eventType, Map<String, Object> payload);
    }

    private final BackupTargets targets;
    private final Clock clock;

    public BackupConsole(BackupTargets targets, Clock clock) {
        this.targets = targets;
        this.clock = clock;
    }

    public static String chainId(String targetId) {
        return "backup:" + targetId;
    }

    public List<BackupTargets.Target> targets() {
        try {
            return targets.targets();
        } catch (IOException e) {
            throw upstream(PLATFORM_ERROR, "cannot list the backup targets", e);
        }
    }

    /** Runs newest first (a run not started yet comes first). {@code targetId} null or blank: every target. */
    public List<BackupTargets.Run> runs(String targetId) {
        String t = targetId == null || targetId.isBlank() ? null : targetId.trim();
        if (t != null) {
            target(t);
        }
        try {
            return targets.runs(t).stream()
                    .sorted(Comparator.comparing(BackupTargets.Run::startedAt, Comparator.nullsFirst(Comparator.reverseOrder()))
                            .thenComparing(BackupTargets.Run::id, Comparator.reverseOrder()))
                    .toList();
        } catch (IOException e) {
            throw upstream(PLATFORM_ERROR, "cannot read the backup runs", e);
        }
    }

    /**
     * Starts a backup of {@code targetId} now. Serialised in this process; across server replicas the deterministic run
     * id and the active-run check keep a double submit from starting two runs.
     */
    public synchronized BackupTargets.Run trigger(String targetId, String actor, String reason, Audit audit) {
        if (reason == null || reason.isBlank()) {
            throw new BackupException(BackupException.Kind.INVALID, "REASON_REQUIRED", "a reason is required");
        }
        String why = reason.trim();
        if (why.length() > MAX_REASON) {
            throw new BackupException(BackupException.Kind.INVALID, "VALIDATION_FAILED",
                    "the reason is longer than " + MAX_REASON + " characters");
        }
        BackupTargets.Target target = target(targetId);
        List<BackupTargets.Run> runs;
        try {
            runs = targets.runs(target.id());
        } catch (IOException e) {
            throw upstream(PLATFORM_ERROR, "cannot read the runs of " + target.id(), e);
        }
        Optional<BackupTargets.Run> active = runs.stream().filter(BackupTargets.Run::active).findFirst();
        if (active.isPresent()) {
            throw new BackupException(BackupException.Kind.CONFLICT, "BACKUP_RUNNING",
                    "run " + active.get().id() + " of " + target.id() + " is still " + active.get().status());
        }
        String runId = targets.runId(target, clock.instant());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target.id());
        payload.put("name", target.name());
        payload.put("run", runId);
        payload.put("actor", actor);
        payload.put("reason", why);
        audit.record(chainId(target.id()), EVENT_TRIGGERED, payload);
        Optional<BackupTargets.Run> started;
        try {
            started = targets.start(target, runId);
        } catch (IOException | UncheckedIOException e) {
            failed(audit, target, runId, e.getMessage());
            throw upstream(TRIGGER_FAILED, "the backup of " + target.id() + " could not be started", e);
        }
        if (started.isEmpty()) {
            failed(audit, target, runId, "a run with this id exists already");
            throw new BackupException(BackupException.Kind.CONFLICT, "BACKUP_RUNNING",
                    "run " + runId + " of " + target.id() + " exists already");
        }
        return started.get();
    }

    private BackupTargets.Target target(String targetId) {
        String id = targetId == null ? "" : targetId.trim();
        return targets().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new BackupException(BackupException.Kind.NOT_FOUND, "NOT_FOUND",
                        "backup target " + id + " was not found"));
    }

    private static void failed(Audit audit, BackupTargets.Target target, String runId, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target.id());
        payload.put("run", runId);
        payload.put("message", message == null ? "" : message);
        audit.record(chainId(target.id()), EVENT_FAILED, payload);
    }

    private static BackupException upstream(String code, String message, Exception e) {
        return new BackupException(BackupException.Kind.UPSTREAM, code,
                message + ": " + e.getMessage(), e);
    }
}
