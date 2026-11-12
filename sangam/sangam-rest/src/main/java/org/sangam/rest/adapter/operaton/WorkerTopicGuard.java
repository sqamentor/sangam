// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import org.sangam.rest.port.RequestContext;

/**
 * R26 (SL-158): a worker principal may fetch and settle work only on topics it is bound to. The endpoint gate
 * ({@code work.execute}) decides who may call the worker API at all; this guard decides per topic. The server wires it
 * to sangam-security's {@code WorkerBindingService.requireTopic} (WORK on {@code WORKER_TOPIC/<topic>}, with the
 * {@code AUTH_DENIED} denial audit); without a guard every caller that passed the gate may work on every topic, as
 * before.
 */
@FunctionalInterface
public interface WorkerTopicGuard {

    /** Returns normally when the caller may work on {@code topic}; otherwise throws (a 403 problem). */
    void requireTopic(RequestContext ctx, String topic);
}
