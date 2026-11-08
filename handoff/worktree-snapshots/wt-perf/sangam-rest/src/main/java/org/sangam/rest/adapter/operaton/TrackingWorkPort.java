// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;

/**
 * {@link WorkPort} decorator that records worker activity for the operations console (O06, R26) and enforces topic
 * pause (R63: "pause a topic without stopping workers"). Topic pauses are persisted (REST_DOCUMENT kind
 * {@code ops:topic-pause}, read through with a 2 s cache), so they survive restarts and apply on every node. Worker
 * heartbeat telemetry (workers, leases, error counts) is runtime telemetry of this node, kept in memory and rebuilt by
 * the workers' next fetch after a restart. Paused topics are removed from fetch-and-lock
 * requests; if every requested topic is paused the call returns an empty list after a short wait, so a polling worker
 * does not spin.
 */
public class TrackingWorkPort implements WorkPort {

    private static final Pattern SDK = Pattern.compile("^\\s*([^/\\s]+)(?:/([^\\s(]+))?\\s*(?:\\(([^)]*)\\))?");
    private static final long PAUSED_WAIT_MS = 1_000;

    /** A worker as seen by this node. */
    public static final class Worker {
        final String workerId;
        final Set<String> topics = ConcurrentHashMap.newKeySet();
        final Set<String> leases = ConcurrentHashMap.newKeySet();
        final AtomicLong calls = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong stale = new AtomicLong();
        volatile Instant lastSeen = Instant.now();
        volatile String sdk = "unknown";
        volatile String sdkVersion;
        volatile String language;

        Worker(String workerId) {
            this.workerId = workerId;
        }

        public String workerId() {
            return workerId;
        }

        public List<String> topics() {
            return topics.stream().sorted().toList();
        }

        public long activeLeases() {
            return leases.size();
        }

        public long heartbeatAgeSec() {
            return Math.max(0, Duration.between(lastSeen, Instant.now()).toSeconds());
        }

        public Double errorRate() {
            long c = calls.get();
            return c == 0 ? null : (double) errors.get() / c;
        }

        public long staleLeaseCount() {
            return stale.get();
        }

        public String sdk() {
            return sdk;
        }

        public String sdkVersion() {
            return sdkVersion;
        }

        public String language() {
            return language;
        }
    }

    /** REST_DOCUMENT kind of a topic pause (doc id = topic). */
    static final String TOPIC_PAUSE = "ops:topic-pause";
    private static final long PAUSE_CACHE_MS = 2_000;

    private final WorkPort delegate;
    private final org.sangam.rest.support.DocumentStore docs;
    // Worker heartbeat telemetry below is runtime state, not business state: it is rebuilt by the workers' next
    // fetch-and-lock / heartbeat after a restart, so it is deliberately kept in memory.
    private final Map<String, Map<String, Worker>> workers = new ConcurrentHashMap<>();
    /** tenant → workId → workerId, to attribute lease ends to the worker that holds them. */
    private final Map<String, Map<String, String>> leaseOwner = new ConcurrentHashMap<>();
    /** tenant → topic → configured lock duration seen in the last fetch. */
    private final Map<String, Map<String, Long>> lockDurations = new ConcurrentHashMap<>();
    /** Read-through cache of the persisted pauses (2 s), or the only store when no DocumentStore is given. */
    private final Map<String, PauseCache> paused = new ConcurrentHashMap<>();

    private record PauseCache(Set<String> topics, long loadedAt) {
    }

    /** Topic pauses persisted in REST_DOCUMENT kind {@value #TOPIC_PAUSE}: they survive restarts and apply cluster-wide. */
    public TrackingWorkPort(WorkPort delegate, org.sangam.rest.support.DocumentStore docs) {
        this.delegate = delegate;
        this.docs = docs;
    }

    /** No persistence: pauses live in memory (tests only). */
    public TrackingWorkPort(WorkPort delegate) {
        this(delegate, null);
    }

    public WorkPort delegate() {
        return delegate;
    }

    // ------------------------------------------------------------------ console API

    public void pauseTopic(String tenant, String topic, boolean pause) {
        if (docs != null) {
            if (pause) {
                docs.upsert(tenant, TOPIC_PAUSE, topic, topic, "PAUSED", null, null,
                        Map.of("topic", topic, "pausedAt", java.time.Instant.now().toString()), "ops");
            } else {
                docs.delete(tenant, TOPIC_PAUSE, topic);
            }
            paused.remove(tenant);
            return;
        }
        PauseCache c = paused.getOrDefault(tenant, new PauseCache(Set.of(), 0));
        Set<String> next = new java.util.HashSet<>(c.topics());
        if (pause) {
            next.add(topic);
        } else {
            next.remove(topic);
        }
        paused.put(tenant, new PauseCache(Set.copyOf(next), Long.MAX_VALUE));
    }

    public boolean isPaused(String tenant, String topic) {
        return pausedTopics(tenant).contains(topic);
    }

    public Set<String> pausedTopics(String tenant) {
        PauseCache c = paused.get(tenant);
        long now = System.currentTimeMillis();
        if (docs != null && (c == null || now - c.loadedAt() > PAUSE_CACHE_MS)) {
            Set<String> topics = docs.all(tenant, TOPIC_PAUSE, org.sangam.rest.support.DocumentStore.Filter.ALL).stream()
                    .map(org.sangam.rest.support.DocumentStore.Doc::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            c = new PauseCache(topics, now);
            paused.put(tenant, c);
        }
        return c == null ? Set.of() : c.topics();
    }

    public List<Worker> workers(String tenant) {
        List<Worker> list = new ArrayList<>(workers.getOrDefault(tenant, Map.of()).values());
        list.sort(Comparator.comparing(Worker::workerId));
        return list;
    }

    /** Topics workers asked for on this node, with the lock duration they requested. */
    public Map<String, Long> knownTopics(String tenant) {
        return Map.copyOf(lockDurations.getOrDefault(tenant, Map.of()));
    }

    /** Records the SDK from the {@code X-Sangam-Sdk: <name>/<version> (<language>)} header (or User-Agent). */
    public void noteSdk(String tenant, String workerId, String sdkHeader) {
        if (tenant == null || workerId == null || sdkHeader == null || sdkHeader.isBlank()) {
            return;
        }
        Worker w = worker(tenant, workerId);
        Matcher m = SDK.matcher(sdkHeader);
        if (m.find()) {
            w.sdk = m.group(1);
            w.sdkVersion = m.group(2);
            w.language = m.group(3) == null ? null : m.group(3).trim();
        }
    }

    // ------------------------------------------------------------------ WorkPort

    @Override
    public List<ExternalWork> fetchAndLock(RequestContext ctx, FetchAndLockRequest request) {
        String tenant = ctx.tenantId();
        Worker w = worker(tenant, request.workerId());
        w.calls.incrementAndGet();
        List<TopicRequest> topics = request.topics() == null ? List.of() : request.topics();
        Map<String, Long> durations = lockDurations.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>());
        for (TopicRequest t : topics) {
            if (t.name() != null) {
                w.topics.add(t.name());
                durations.put(t.name(), t.lockDurationMs() == null ? -1L : t.lockDurationMs());
            }
        }
        List<TopicRequest> open = topics.stream().filter(t -> !isPaused(tenant, t.name())).toList();
        if (open.isEmpty() && !topics.isEmpty()) {
            long wait = Math.min(PAUSED_WAIT_MS,
                    request.asyncResponseTimeoutMs() == null ? 0 : Math.max(0, request.asyncResponseTimeoutMs()));
            sleep(wait);
            return List.of();
        }
        FetchAndLockRequest filtered = open.size() == topics.size() ? request
                : new FetchAndLockRequest(request.workerId(), open, request.maxTasks(), request.asyncResponseTimeoutMs(),
                request.usePriority());
        List<ExternalWork> locked = call(w, () -> delegate.fetchAndLock(ctx, filtered));
        Map<String, String> owners = leaseOwner.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>());
        for (ExternalWork e : locked) {
            w.leases.add(e.id());
            owners.put(e.id(), w.workerId);
        }
        return locked;
    }

    @Override
    public LockState extendLock(RequestContext ctx, String workId, ExtendLockRequest request) {
        Worker w = worker(ctx.tenantId(), request.workerId());
        return call(w, () -> delegate.extendLock(ctx, workId, request));
    }

    @Override
    public void complete(RequestContext ctx, String workId, CompleteRequest request) {
        Worker w = worker(ctx.tenantId(), request.workerId());
        call(w, () -> {
            delegate.complete(ctx, workId, request);
            return null;
        });
        end(ctx.tenantId(), workId);
    }

    @Override
    public void fail(RequestContext ctx, String workId, FailRequest request) {
        Worker w = worker(ctx.tenantId(), request.workerId());
        w.errors.incrementAndGet();
        call(w, () -> {
            delegate.fail(ctx, workId, request);
            return null;
        });
        end(ctx.tenantId(), workId);
    }

    @Override
    public void bpmnError(RequestContext ctx, String workId, BpmnErrorRequest request) {
        Worker w = worker(ctx.tenantId(), request.workerId());
        w.errors.incrementAndGet();
        call(w, () -> {
            delegate.bpmnError(ctx, workId, request);
            return null;
        });
        end(ctx.tenantId(), workId);
    }

    @Override
    public void unlock(RequestContext ctx, String workId, UnlockRequest request) {
        Worker w = worker(ctx.tenantId(), request.workerId());
        call(w, () -> {
            delegate.unlock(ctx, workId, request);
            return null;
        });
        end(ctx.tenantId(), workId);
    }

    @Override
    public OutcomeUnknownResult outcomeUnknown(RequestContext ctx, String workId, OutcomeUnknownRequest request) {
        Worker w = worker(ctx.tenantId(), request.workerId());
        w.errors.incrementAndGet();
        OutcomeUnknownResult r = call(w, () -> delegate.outcomeUnknown(ctx, workId, request));
        end(ctx.tenantId(), workId);
        return r;
    }

    @Override
    public void cancelAck(RequestContext ctx, String workId, CancelAckRequest request) {
        Worker w = worker(ctx.tenantId(), request.workerId());
        call(w, () -> {
            delegate.cancelAck(ctx, workId, request);
            return null;
        });
        end(ctx.tenantId(), workId);
    }

    // ------------------------------------------------------------------ helpers

    private Worker worker(String tenant, String workerId) {
        String id = workerId == null || workerId.isBlank() ? "anonymous" : workerId;
        Worker w = workers.computeIfAbsent(tenant == null ? "-" : tenant, t -> new ConcurrentHashMap<>())
                .computeIfAbsent(id, Worker::new);
        w.lastSeen = Instant.now();
        return w;
    }

    private void end(String tenant, String workId) {
        Map<String, String> owners = leaseOwner.get(tenant);
        String owner = owners == null ? null : owners.remove(workId);
        if (owner != null) {
            Worker w = workers.getOrDefault(tenant, Map.of()).get(owner);
            if (w != null) {
                w.leases.remove(workId);
            }
        }
    }

    private static <T> T call(Worker w, java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (ApiProblem p) {
            if ("STALE_LEASE".equals(p.code())) {
                w.stale.incrementAndGet();
            }
            throw p;
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
