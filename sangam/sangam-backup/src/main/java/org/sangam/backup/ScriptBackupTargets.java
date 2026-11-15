// SPDX-License-Identifier: Apache-2.0
package org.sangam.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Backup targets on VM and docker compose installs (owner decision 220, completing decision 155): each
 * target is a backup command the operator configured (the documented procedure of {@code deploy/README.md} section 3 as
 * a script on the host), started on demand, with the run history kept as files.
 *
 * <ul>
 *   <li><b>Command:</b> an absolute executable plus fixed arguments, started without a shell; nothing from the request
 *       reaches it. The environment is cleared and refilled from an allow-list of variable names (the operator names
 *       the variables the script needs, for example the database password variable), plus
 *       {@code SANGAM_BACKUP_TARGET} and {@code SANGAM_BACKUP_RUN_ID}. Output goes to {@code <run>.log} in the history
 *       directory and is never returned by the API (it may name hosts or files).</li>
 *   <li><b>History:</b> one JSON file per run, {@code <run>.json}, written atomically, PENDING then RUNNING then
 *       SUCCEEDED (exit code 0) or FAILED (another exit code, the timeout, or a start failure). The newest
 *       {@link #KEEP_RUNS} runs per target are kept. Runs the host's own scheduler starts are not seen.</li>
 *   <li><b>One run per target:</b> a lock file {@code <target>.lock}, created with CREATE_NEW, held from start to the
 *       final record. Servers that share the history directory (several VMs on one shared volume) therefore never run
 *       a target twice. A lock whose run has finished, or has passed its timeout plus {@link #GRACE}, is stale (the
 *       server stopped during the run); the run is recorded as FAILED and the lock taken over.</li>
 * </ul>
 */
public final class ScriptBackupTargets implements BackupTargets {

    /** Runs kept per target. */
    public static final int KEEP_RUNS = 100;
    /** Time after the timeout before a still-active run counts as abandoned. */
    public static final Duration GRACE = Duration.ofMinutes(2);
    /** Variables passed through whatever the configuration says (process basics, never secrets). */
    public static final Set<String> BASE_ENV = Set.of("PATH", "HOME", "LANG", "TZ", "TMPDIR", "TEMP", "TMP", "SystemRoot");
    public static final Duration MAX_TIMEOUT = Duration.ofHours(24);
    public static final int MAX_TARGETS = 32;
    public static final int MAX_ARGS = 32;

    private static final Pattern ID = Pattern.compile("[a-z0-9]([-a-z0-9]{0,40}[a-z0-9])?");
    private static final Pattern RUN_ID = Pattern.compile("[a-z0-9][-a-z0-9]{0,62}");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A configured backup command. {@code schedule} is shown only (the host's scheduler runs the scheduled backups). */
    public record ScriptTarget(String id, String name, List<String> command, String schedule, Duration timeout) {
        public ScriptTarget {
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("backup target id must be lower-case letters, digits and '-': " + id);
            }
            if (command == null || command.isEmpty() || command.size() > MAX_ARGS) {
                throw new IllegalArgumentException("backup target " + id + " needs a command of 1 to " + MAX_ARGS + " parts");
            }
            if (!Path.of(command.get(0)).isAbsolute()) {
                throw new IllegalArgumentException("backup target " + id + ": the command must be an absolute path (no shell, no PATH lookup)");
            }
            if (command.stream().anyMatch(a -> a == null || a.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("backup target " + id + ": empty or NUL argument");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
                throw new IllegalArgumentException("backup target " + id + ": timeout must be positive and at most " + MAX_TIMEOUT);
            }
            command = List.copyOf(command);
            name = name == null || name.isBlank() ? id : name;
        }
    }

    /** The outcome of one command: its exit code, or that it was stopped at the timeout. */
    public record Outcome(boolean timedOut, int exitCode) {
    }

    /** Runs a command to its end; the production runner is {@link #PROCESS}. */
    @FunctionalInterface
    public interface Runner {
        Outcome run(List<String> command, Map<String, String> environment, Path log, Duration timeout)
                throws IOException, InterruptedException;
    }

    /** {@link ProcessBuilder}: no shell, cleared environment, stdout and stderr to the log, the process tree stopped at the timeout. */
    public static final Runner PROCESS = (command, environment, log, timeout) -> {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().clear();
        pb.environment().putAll(environment);
        Process p = pb.start();
        if (p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return new Outcome(false, p.exitValue());
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        p.waitFor(30, TimeUnit.SECONDS);
        return new Outcome(true, -1);
    };

    private final Map<String, ScriptTarget> targets = new LinkedHashMap<>();
    private final Path historyDir;
    private final Map<String, String> environment;
    private final Runner runner;
    private final Executor executor;
    private final Clock clock;

    /**
     * @param envPassthrough names of host variables the commands receive (besides {@link #BASE_ENV}); values are read
     *                       from {@code hostEnv} at start and never logged
     */
    public ScriptBackupTargets(List<ScriptTarget> scripts, Path historyDir, Set<String> envPassthrough,
                               Map<String, String> hostEnv, Runner runner, Executor executor, Clock clock) throws IOException {
        if (scripts == null || scripts.isEmpty() || scripts.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("configure 1 to " + MAX_TARGETS + " backup targets");
        }
        for (ScriptTarget s : scripts) {
            if (targets.putIfAbsent(s.id(), s) != null) {
                throw new IllegalArgumentException("backup target " + s.id() + " is configured twice");
            }
        }
        if (historyDir == null || !historyDir.isAbsolute()) {
            throw new IllegalArgumentException("the backup history directory must be an absolute path");
        }
        Files.createDirectories(historyDir);
        this.historyDir = historyDir;
        Set<String> names = new LinkedHashSet<>(BASE_ENV);
        for (String n : envPassthrough) {
            if (!ENV_NAME.matcher(n).matches()) {
                throw new IllegalArgumentException("not an environment variable name: " + n);
            }
            names.add(n);
        }
        Map<String, String> env = new LinkedHashMap<>();
        for (String n : names) {
            String v = hostEnv.get(n);
            if (v != null) {
                env.put(n, v);
            }
        }
        this.environment = Map.copyOf(env);
        this.runner = runner;
        this.executor = executor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ port

    @Override
    public List<Target> targets() throws IOException {
        List<Target> out = new ArrayList<>();
        for (ScriptTarget s : targets.values()) {
            List<Run> runs = runs(s.id());
            int active = (int) runs.stream().filter(Run::active).count();
            Instant lastStart = runs.stream().map(Run::startedAt).filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            Instant lastSuccess = runs.stream().filter(r -> r.status() == Status.SUCCEEDED).map(Run::finishedAt)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            out.add(new Target(s.id(), s.name(), s.schedule(), null, false, active, lastStart, lastSuccess));
        }
        out.sort(Comparator.comparing(Target::id));
        return out;
    }

    @Override
    public List<Run> runs(String targetId) throws IOException {
        List<Run> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(historyDir, "*.json")) {
            for (Path f : files) {
                Run r = read(f);
                if (r != null && targets.containsKey(r.target()) && (targetId == null || targetId.equals(r.target()))) {
                    out.add(effective(r));
                }
            }
        }
        return out;
    }

    @Override
    public String runId(Target target, Instant now) {
        return target.id() + "-m" + Long.toString(now.getEpochSecond(), 36);
    }

    @Override
    public synchronized Optional<Run> start(Target target, String runId) throws IOException {
        ScriptTarget s = targets.get(target.id());
        if (s == null) {
            throw new IOException("unknown backup target " + target.id());
        }
        if (Files.exists(runFile(runId)) || !lock(s, runId)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Run pending = new Run(runId, s.id(), Trigger.MANUAL, Status.PENDING, now, null, null, null);
        try {
            write(pending);
            prune(s.id());
            executor.execute(() -> execute(s, pending));
        } catch (IOException | RejectedExecutionException e) {
            finish(pending, Status.FAILED, "the backup could not be started: " + e.getMessage());
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        return Optional.of(pending);
    }

    // ------------------------------------------------------------------ run

    private void execute(ScriptTarget s, Run pending) {
        Run running = new Run(pending.id(), s.id(), Trigger.MANUAL, Status.RUNNING, pending.startedAt(), null, null, null);
        try {
            write(running);
            Map<String, String> env = new LinkedHashMap<>(environment);
            env.put("SANGAM_BACKUP_TARGET", s.id());
            env.put("SANGAM_BACKUP_RUN_ID", pending.id());
            Outcome o = runner.run(s.command(), env, historyDir.resolve(pending.id() + ".log"), s.timeout());
            if (o.timedOut()) {
                finish(running, Status.FAILED, "stopped after the timeout of " + s.timeout());
            } else if (o.exitCode() == 0) {
                finish(running, Status.SUCCEEDED, null);
            } else {
                finish(running, Status.FAILED, "exit code " + o.exitCode() + "; see " + pending.id() + ".log on the server");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(running, Status.FAILED, "the server stopped during the backup");
        } catch (IOException | RuntimeException e) {
            finish(running, Status.FAILED, "the backup command could not run: " + e.getMessage());
        }
    }

    /** Writes the final record and only then releases the lock, so a new run never sees an active record of the old. */
    private void finish(Run run, Status status, String message) {
        try {
            write(new Run(run.id(), run.target(), run.trigger(), status, run.startedAt(), clock.instant(), null, message));
        } catch (IOException e) {
            // the record stays active and the lock stays: the run turns FAILED when its timeout plus GRACE has passed
            return;
        }
        try {
            Files.deleteIfExists(lockFile(run.target()));
        } catch (IOException ignored) {
            // a left-over lock of a finished run is stale and taken over by the next start
        }
    }

    /** CREATE_NEW on the lock; a stale lock (its run finished or abandoned) is taken over once. */
    private boolean lock(ScriptTarget s, String runId) throws IOException {
        Path lock = lockFile(s.id());
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Files.writeString(lock, runId, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE);
                return true;
            } catch (FileAlreadyExistsException held) {
                String holder;
                try {
                    holder = Files.readString(lock, StandardCharsets.UTF_8).trim();
                } catch (NoSuchFileException gone) {
                    continue;
                }
                Run r = holder.isEmpty() || !RUN_ID.matcher(holder).matches() ? null : read(runFile(holder));
                if (r != null && effective(r).active()) {
                    return false;
                }
                if (r != null && r.active()) {
                    write(new Run(r.id(), r.target(), r.trigger(), Status.FAILED, r.startedAt(), clock.instant(), null,
                            "abandoned: no result within the timeout (the server stopped during the run)"));
                }
                Files.deleteIfExists(lock);
            }
        }
        return false;
    }

    /** An active run past its timeout plus {@link #GRACE} is shown as FAILED (the server running it stopped). */
    private Run effective(Run r) {
        ScriptTarget s = targets.get(r.target());
        if (!r.active() || s == null || r.startedAt() == null) {
            return r;
        }
        if (clock.instant().isAfter(r.startedAt().plus(s.timeout()).plus(GRACE))) {
            return new Run(r.id(), r.target(), r.trigger(), Status.FAILED, r.startedAt(), null, null,
                    "abandoned: no result within the timeout (the server stopped during the run)");
        }
        return r;
    }

    private void prune(String targetId) throws IOException {
        List<Run> runs = new ArrayList<>(runs(targetId));
        runs.sort(Comparator.comparing(Run::startedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        for (Run old : runs.subList(Math.min(KEEP_RUNS, runs.size()), runs.size())) {
            if (!old.active()) {
                Files.deleteIfExists(runFile(old.id()));
                Files.deleteIfExists(historyDir.resolve(old.id() + ".log"));
            }
        }
    }

    // ------------------------------------------------------------------ files

    private Path runFile(String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("not a run id: " + runId);
        }
        return historyDir.resolve(runId + ".json");
    }

    private Path lockFile(String targetId) {
        return historyDir.resolve(targetId + ".lock");
    }

    private void write(Run r) throws IOException {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", r.id());
        n.put("target", r.target());
        n.put("trigger", r.trigger().name());
        n.put("status", r.status().name());
        n.put("startedAt", r.startedAt() == null ? null : r.startedAt().toString());
        n.put("finishedAt", r.finishedAt() == null ? null : r.finishedAt().toString());
        n.put("message", r.message());
        Path target = runFile(r.id());
        Path tmp = Files.createTempFile(historyDir, r.id() + ".", ".tmp");
        try {
            Files.writeString(tmp, n.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Null when the file is gone or unreadable (a half-written file of another tool never breaks the list). */
    private static Run read(Path f) {
        try {
            JsonNode n = JSON.readTree(Files.readString(f, StandardCharsets.UTF_8));
            return new Run(n.path("id").asText(), n.path("target").asText(), Trigger.valueOf(n.path("trigger").asText()),
                    Status.valueOf(n.path("status").asText()), instant(n.get("startedAt")), instant(n.get("finishedAt")),
                    null, n.hasNonNull("message") ? n.get("message").asText() : null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Instant instant(JsonNode n) {
        return n == null || n.isNull() ? null : Instant.parse(n.asText());
    }

    // ------------------------------------------------------------------ settings

    /**
     * The targets from flat settings, as the server's environment gives them: {@code <prefix>.targets[i].id},
     * {@code .name}, {@code .schedule}, {@code .timeout} (ISO-8601, default PT2H) and {@code .command[j]}, for
     * {@code i} and {@code j} from 0 until the first missing entry.
     */
    public static List<ScriptTarget> targetsFrom(String prefix, Function<String, String> settings) {
        List<ScriptTarget> out = new ArrayList<>();
        for (int i = 0; i < MAX_TARGETS; i++) {
            String p = prefix + ".targets[" + i + "]";
            String id = settings.apply(p + ".id");
            if (id == null || id.isBlank()) {
                break;
            }
            List<String> command = new ArrayList<>();
            for (int j = 0; j < MAX_ARGS; j++) {
                String a = settings.apply(p + ".command[" + j + "]");
                if (a == null) {
                    break;
                }
                command.add(a);
            }
            String timeout = settings.apply(p + ".timeout");
            out.add(new ScriptTarget(id.trim(), settings.apply(p + ".name"), command, settings.apply(p + ".schedule"),
                    timeout == null || timeout.isBlank() ? Duration.ofHours(2) : Duration.parse(timeout.trim())));
        }
        return out;
    }
}
