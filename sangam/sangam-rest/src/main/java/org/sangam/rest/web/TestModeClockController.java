// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TestClockPort;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-021 (C3-13): {@code getTestClock} and {@code changeTestClock}. The route exists only in test mode: without a bound
 * {@link TestClockPort} (the server binds one only with {@code sangam.test-mode.enabled}, on H2, never in production) it
 * answers 404 before anything else, so a production server does not even reveal it. In test mode the caller needs the
 * endpoint permission and the group {@value Permissions#GROUP_ADMIN} (the platform administrator after SL-70: the clock
 * is process-wide, every tenant sees it). Each change is appended as {@value #EVENT} to the caller's {@code sec:} chain
 * in its own transaction before the answer; when that write fails the change is undone and the call fails.
 */
@RestController
@RequestMapping("/api/v1")
public class TestModeClockController {

    /** Class-B audit event of a clock change (SANGAM.md X.4.0.3, change control by the integrator). */
    public static final String EVENT = "TEST_CLOCK_CHANGED";

    private final ObjectProvider<TestClockPort> clock;
    private final PermissionPort permissions;
    private final AuditPort audit;
    private final TxSupport tx;

    public TestModeClockController(ObjectProvider<TestClockPort> clock, PermissionPort permissions, AuditPort audit,
                                   TxSupport tx) {
        this.clock = clock;
        this.permissions = permissions;
        this.audit = audit;
        this.tx = tx;
    }

    @GetMapping("/test-mode/clock")
    public TestClockPort.TestClock getTestClock(RequestContext ctx) {
        TestClockPort c = requireTestMode();
        requireAdmin(ctx, Permissions.ADMIN_READ);
        return c.read();
    }

    @PostMapping("/test-mode/clock")
    public TestClockPort.TestClock changeTestClock(RequestContext ctx, @RequestBody TestClockPort.TestClockChange body) {
        TestClockPort c = requireTestMode();
        requireAdmin(ctx, Permissions.ADMIN_WRITE);
        if (body == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "a TestClockChange body is required");
        }
        int actions = (body.advanceBy() != null ? 1 : 0) + (body.set() != null ? 1 : 0) + (body.reset() != null ? 1 : 0);
        if (actions != 1) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "exactly one of advanceBy, set or reset is required");
        }
        String reason = body.reason() == null || body.reason().isBlank() ? null : body.reason().strip();
        String actor = ctx.principalId();
        TestClockPort.Change done;
        if (body.advanceBy() != null) {
            done = c.advanceBy(positive(body.advanceBy()), actor, reason, ch -> record(ctx, ch));
        } else if (body.set() != null) {
            done = c.set(body.set(), actor, reason, ch -> record(ctx, ch));
        } else {
            if (!body.reset()) {
                throw ApiProblem.badRequest("VALIDATION_FAILED", "reset must be true");
            }
            done = c.reset(actor, reason, ch -> record(ctx, ch));
        }
        return new TestClockPort.TestClock(done.now(), done.offsetMs(), done.action());
    }

    private TestClockPort requireTestMode() {
        TestClockPort c = clock.getIfAvailable();
        if (c == null) {
            throw ApiProblem.notFound("route", "/api/v1/test-mode/clock");
        }
        return c;
    }

    private void requireAdmin(RequestContext ctx, String permission) {
        permissions.require(ctx, permission);
        if (!ctx.inGroup(Permissions.GROUP_ADMIN)) {
            throw ApiProblem.notPermitted("test-mode clock");
        }
    }

    static Duration positive(String iso) {
        Duration d;
        try {
            d = Duration.parse(iso.strip());
        } catch (DateTimeParseException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "advanceBy must be an ISO-8601 duration such as P3D or PT2H");
        }
        if (d.isNegative() || d.isZero()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "advanceBy must be positive");
        }
        return d;
    }

    /** {@value #EVENT} in its own transaction, committed before the answer (a failure undoes the change). */
    private void record(RequestContext ctx, TestClockPort.Change ch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", ch.action());
        p.put("previousOffsetMs", ch.previousOffsetMs());
        p.put("offsetMs", ch.offsetMs());
        p.put("now", ch.now().toString());
        if (ch.actor() != null) {
            p.put("actor", ch.actor());
        }
        if (ch.reason() != null) {
            p.put("reason", ch.reason());
        }
        tx.inNewTx(() -> audit.record(ctx, "sec:" + ctx.tenantId(), EVENT, p));
    }
}
