// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.kotlin

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.sangam.sdk.testing.SangamTestEngine

/** The Kotlin DSL drives the Java worker runtime against the Java SDK's R18 test engine (M-021). */
class SangamDslTest {

    @Test
    fun `a DSL worker completes leased work`() {
        SangamTestEngine.start().use { engine ->
            val id = engine.addWork("pay", mapOf("amount" to mapOf("type" to "Decimal", "value" to "1234.50")))
            val worker = engine.client().worker("kt-worker", { asyncResponseTimeout(Duration.ofMillis(50)).clock(engine.clock()) }) {
                topic("pay", lockDuration = Duration.ofSeconds(30)) { ctx ->
                    mapOf("paidAmount" to ctx.variable<java.math.BigDecimal>("amount"))
                }
            }
            val runner = Thread { worker.run() }.apply { start() }
            try {
                engine.awaitState(id, "COMPLETED", Duration.ofSeconds(10))
            } finally {
                worker.close()
                runner.join(5_000)
            }
            assertEquals("COMPLETED", engine.state(id))
        }
    }

    @Test
    fun `bpmnError and outcomeUnknown map to the worker results`() {
        SangamTestEngine.start().use { engine ->
            val err = engine.addWork("check", emptyMap<String, Any>())
            val unknown = engine.addWork("pay-irreversible", emptyMap<String, Any>())
            val worker = engine.client().worker("kt-worker", { asyncResponseTimeout(Duration.ofMillis(50)).maxTasksPerFetch(1).clock(engine.clock()) }) {
                topic("check") { bpmnError("NOT_ELIGIBLE", "rule 4 failed") }
                topic("pay-irreversible") { outcomeUnknown("timeout after the payment request was sent") }
            }
            val runner = Thread { worker.run() }.apply { start() }
            try {
                engine.awaitState(err, "COMPLETED_WITH_ERROR", Duration.ofSeconds(10))
                engine.awaitState(unknown, "UNKNOWN_OUTCOME", Duration.ofSeconds(10))
            } finally {
                worker.close()
                runner.join(5_000)
            }
        }
    }
}
