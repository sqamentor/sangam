// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;

import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;

/**
 * A {@link WorkPort} for long-poll tests: {@code fetchAndLock} records each request and hands out the queued work (one
 * batch per call, nothing when the queue is empty) without waiting itself; {@code unlock} records the release.
 */
public final class FakeWorkPort implements WorkPort {

    public final List<FetchAndLockRequest> fetches = new CopyOnWriteArrayList<>();
    public final List<String> unlocked = new CopyOnWriteArrayList<>();
    public final LinkedBlockingDeque<List<ExternalWork>> queued = new LinkedBlockingDeque<>();
    public volatile RuntimeException failure;

    public static ExternalWork work(String id, String topic, String tenant) {
        return new ExternalWork(id, topic, tenant, "pi-" + id, null, "act", "ai-" + id, null, null, 3L, "att-" + id,
                "op-" + id, Instant.parse("2026-11-12T00:00:00Z"), 3, null, null, null);
    }

    @Override
    public List<ExternalWork> fetchAndLock(RequestContext ctx, FetchAndLockRequest request) {
        fetches.add(request);
        RuntimeException f = failure;
        if (f != null) {
            throw f;
        }
        List<ExternalWork> next = queued.poll();
        return next == null ? List.of() : new ArrayList<>(next);
    }

    @Override
    public LockState extendLock(RequestContext ctx, String workId, ExtendLockRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void complete(RequestContext ctx, String workId, CompleteRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fail(RequestContext ctx, String workId, FailRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void bpmnError(RequestContext ctx, String workId, BpmnErrorRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unlock(RequestContext ctx, String workId, UnlockRequest request) {
        unlocked.add(workId + "|" + request.workerId() + "|" + request.lockVersion() + "|" + request.attemptId());
    }

    @Override
    public OutcomeUnknownResult outcomeUnknown(RequestContext ctx, String workId, OutcomeUnknownRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void cancelAck(RequestContext ctx, String workId, CancelAckRequest request) {
        throw new UnsupportedOperationException();
    }
}
