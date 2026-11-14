// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.operaton.bpm.engine.AuthorizationException;
import org.operaton.bpm.engine.BadUserRequestException;
import org.operaton.bpm.engine.OptimisticLockingException;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.exception.NotFoundException;
import org.operaton.bpm.engine.exception.NullValueException;
import org.operaton.bpm.engine.query.Query;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;

/** Shared helpers for the Operaton default adapters: error translation, capped paging (A-16), tenant checks. */
public final class OperatonSupport {

    private static final java.util.regex.Pattern CODED =
            java.util.regex.Pattern.compile("\\b(40[0-9]|41[0-9]|42[0-9]) ([A-Z][A-Z0-9_]{2,}): ?(.*)", java.util.regex.Pattern.DOTALL);

    private OperatonSupport() {
    }

    /** Runs an engine call and translates engine exceptions into RFC 9457 problems. */
    public static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (ApiProblem p) {
            throw p;
        } catch (OptimisticLockingException e) {
            throw ApiProblem.conflict("CONFLICT_RETRY_EXHAUSTED",
                    "The system is busy with this case; your input is kept, try again");
        } catch (NotFoundException | NullValueException e) {
            throw new ApiProblem(404, "NOT_FOUND", e.getMessage());
        } catch (AuthorizationException e) {
            throw ApiProblem.forbidden("ACTION_NOT_PERMITTED", e.getMessage());
        } catch (BadUserRequestException e) {
            throw ApiProblem.badRequest("BAD_REQUEST", e.getMessage());
        } catch (ProcessEngineException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            // Pack and security listeners signal business refusals as "<status> <CODE>: <detail>"
            // (422 SOD_VIOLATION, 422 POWER_LIMIT_EXCEEDED, 409 APPROVAL_INVALIDATED, ... ; WS-PACKS §8).
            java.util.regex.Matcher coded = CODED.matcher(m);
            if (coded.find()) {
                throw new ApiProblem(Integer.parseInt(coded.group(1)), coded.group(2), coded.group(3).trim());
            }
            if (m.contains("no deployed") || m.contains("not found") || m.contains("doesn't exist")
                    || m.contains("does not exist")) {
                throw new ApiProblem(404, "NOT_FOUND", m);
            }
            if (m.contains("ENGINE-09005") || m.contains("ENGINE-01009") || m.contains("parse")) {
                throw ApiProblem.unprocessable("MODEL_INVALID", m);
            }
            throw ApiProblem.unprocessable("ENGINE_REJECTED", m);
        }
    }

    public static void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Capped page over an Operaton query: counts only up to {@code cap} (probe {@code listPage(cap, 1)} first), so a
     * huge result never needs a full count (A-16).
     */
    public static <U, T> Page<T> page(Query<?, U> query, Page.PageRequest page, long cap, Function<U, T> mapper) {
        return call(() -> {
            List<U> rows = query.listPage(page.firstResult(), page.size());
            boolean capped = cap > 0 && !query.listPage((int) Math.min(Integer.MAX_VALUE, cap), 1).isEmpty();
            long total = capped ? cap : query.count();
            return Page.of(rows.stream().map(mapper).toList(), total, capped);
        });
    }

    /** Get-by-id tenant check: an object of another tenant is reported as not found (no existence leak). */
    public static void checkTenant(RequestContext ctx, String objectTenant, String what, String id) {
        if (ctx.tenantId() != null && objectTenant != null && !ctx.tenantId().equals(objectTenant)) {
            throw ApiProblem.notFound(what, id);
        }
        if (ctx.tenantId() != null && objectTenant == null && !isShared(ctx)) {
            throw ApiProblem.notFound(what, id);
        }
    }

    /** Untenanted (shared) definitions are visible to everyone; untenanted runtime objects only to the system. */
    private static boolean isShared(RequestContext ctx) {
        return ctx.inGroup("sangam-system") || ctx.inGroup("sangam-admin");
    }

    public static Instant instant(Date d) {
        return d == null ? null : d.toInstant();
    }

    public static Date date(Instant i) {
        return i == null ? null : Date.from(i);
    }

    public static String tenantOrNull(RequestContext ctx) {
        return ctx.tenantId();
    }
}
