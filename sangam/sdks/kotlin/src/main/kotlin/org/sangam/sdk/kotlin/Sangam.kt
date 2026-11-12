// SPDX-License-Identifier: Apache-2.0
// Tier-2 Kotlin SDK (ADR-D-4, M-022): Kotlin idioms over the Java SDK. The worker runtime (VI.3 lease fencing,
// heartbeats at lease/3, Idempotency-Key per operationId, STALE_LEASE handling) is the Java SDK's SangamWorker, so a
// Kotlin worker has exactly the Tier-1 lifecycle semantics; this file only adds a DSL and reified helpers.
package org.sangam.sdk.kotlin

import java.time.Duration
import org.sangam.sdk.SangamClient
import org.sangam.sdk.worker.BpmnError
import org.sangam.sdk.worker.OutcomeUnknownException
import org.sangam.sdk.worker.SangamWorker
import org.sangam.sdk.worker.TaskContext
import org.sangam.sdk.worker.TopicSubscription

/** Builds a [SangamClient]: `sangamClient("https://host") { token("dev") }`. */
fun sangamClient(baseUrl: String, configure: SangamClient.Builder.() -> Unit = {}): SangamClient =
    SangamClient.builder().baseUrl(baseUrl).apply(configure).build()

/** Topic subscriptions of a worker built with [worker]. */
class WorkerTopics internal constructor(private val builder: SangamWorker.Builder) {

    /**
     * Subscribes [name]. The handler returns the output variables (null = none); throw with [bpmnError] or
     * [outcomeUnknown] for the other results. [irreversible] topics report failures as outcome-unknown, never as a
     * blind retry (VI.3 rule 5).
     */
    fun topic(
        name: String,
        lockDuration: Duration? = null,
        variables: List<String>? = null,
        irreversible: Boolean = false,
        handler: (TaskContext) -> Map<String, Any?>?,
    ) {
        val b = TopicSubscription.topic(name) { ctx -> handler(ctx) }
        if (lockDuration != null) b.lockDuration(lockDuration)
        if (variables != null) b.variables(variables)
        b.irreversible(irreversible)
        builder.subscribe(b.build())
    }
}

/**
 * Builds a worker on this client:
 * ```
 * val worker = client.worker("bill-worker") {
 *     topic("pay-bill", lockDuration = Duration.ofMinutes(2)) { ctx -> mapOf("paid" to true) }
 * }
 * worker.run()
 * ```
 */
fun SangamClient.worker(
    workerId: String,
    configure: SangamWorker.Builder.() -> Unit = {},
    topics: WorkerTopics.() -> Unit,
): SangamWorker {
    val b = SangamWorker.builder(this).workerId(workerId).apply(configure)
    WorkerTopics(b).topics()
    return b.build()
}

/** Typed variable access: `ctx.variable<java.math.BigDecimal>("amount")`. */
inline fun <reified T : Any> TaskContext.variable(name: String): T? = variable(name, T::class.java)

/** Throws a BPMN error routed by the model (the worker reports bpmn-error). */
fun bpmnError(code: String, message: String? = null, variables: Map<String, Any?>? = null): Nothing =
    throw if (variables == null) BpmnError(code, message) else BpmnError(code, message, variables)

/** The downstream effect may or may not have happened: the worker reports outcome-unknown (VI.3 rule 4). */
fun outcomeUnknown(detail: String): Nothing = throw OutcomeUnknownException(detail)
