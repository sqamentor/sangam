// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

/** SL-59: pool exhaustion (HikariCP acquire timeout), however wrapped, answers 503 DATABASE_UNAVAILABLE + Retry-After. */
class PoolExhaustionProblemsTest {

    /** What HikariCP throws when the acquire wait expires. */
    private static SQLTransientConnectionException hikariTimeout() {
        return new SQLTransientConnectionException(
                "sangam - Connection is not available, request timed out after 2000ms (total=20, active=20, idle=0, "
                        + "waiting=7)", "08001");
    }

    @Test
    void recognisesTheAcquireTimeoutDirectlyAndWrapped() {
        SQLTransientConnectionException root = hikariTimeout();
        assertTrue(PoolExhaustionProblems.connectionUnavailable(root));
        // Spring JDBC
        assertTrue(PoolExhaustionProblems.connectionUnavailable(
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", root)));
        // Operaton / MyBatis style: engine exception -> persistence exception -> SQL exception
        RuntimeException persistence = new RuntimeException("### Error querying database", root);
        assertTrue(PoolExhaustionProblems.connectionUnavailable(
                new IllegalStateException("ENGINE-03083 exception while executing Batch Database Operations",
                        persistence)));
        // a servlet filter's failure as the container sees it
        assertTrue(PoolExhaustionProblems.connectionUnavailable(new ServletException("filter", persistence)));
        // a driver chain carrying it as the next exception
        SQLException chained = new SQLException("batch failed");
        chained.setNextException(root);
        assertTrue(PoolExhaustionProblems.connectionUnavailable(new RuntimeException(chained)));
    }

    @Test
    void otherDatabaseFailuresAreNotMapped() {
        assertFalse(PoolExhaustionProblems.connectionUnavailable(null));
        assertFalse(PoolExhaustionProblems.connectionUnavailable(new RuntimeException("boom")));
        // a connection lost mid-statement: the outcome is unknown, so it is not a retry-safe 503
        assertFalse(PoolExhaustionProblems.connectionUnavailable(
                new RuntimeException(new SQLException("An I/O error occurred while sending to the backend", "08006"))));
        assertFalse(PoolExhaustionProblems.connectionUnavailable(new SQLTimeoutException("statement timeout")));
    }

    @Test
    void causeCyclesTerminate() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertFalse(PoolExhaustionProblems.connectionUnavailable(a));
    }

    @Test
    void resolverWritesA503ProblemWithRetryAfter() throws Exception {
        PoolExhaustionProblems.Resolver r = new PoolExhaustionProblems.Resolver(2);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/process-instances");
        MockHttpServletResponse res = new MockHttpServletResponse();
        ModelAndView mav = r.resolveException(req, res, null,
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", hikariTimeout()));
        assertNotNull(mav, "handled");
        assertEquals(503, res.getStatus());
        assertEquals("2", res.getHeader("Retry-After"));
        assertTrue(res.getContentType().startsWith("application/problem+json"), res.getContentType());
        String body = res.getContentAsString();
        assertTrue(body.contains("\"code\":\"DATABASE_UNAVAILABLE\""), body);
        assertTrue(body.contains("\"status\":503"), body);
        assertTrue(r.getOrder() < 0, "must run before Spring MVC's @ExceptionHandler resolution (order 0)");
    }

    @Test
    void resolverLeavesOtherExceptionsAndCommittedResponsesAlone() throws Exception {
        PoolExhaustionProblems.Resolver r = new PoolExhaustionProblems.Resolver(2);
        MockHttpServletResponse untouched = new MockHttpServletResponse();
        assertNull(r.resolveException(new MockHttpServletRequest(), untouched, null, new IllegalStateException("x")));
        assertEquals(200, untouched.getStatus());

        MockHttpServletResponse committed = new MockHttpServletResponse();
        committed.setCommitted(true);
        assertNull(r.resolveException(new MockHttpServletRequest(), committed, null, new RuntimeException(hikariTimeout())));
    }

    @Test
    void filterAnswers503WhenAFilterCannotGetAConnection() throws Exception {
        PoolExhaustionProblems.UnavailableFilter f = new PoolExhaustionProblems.UnavailableFilter(3);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/tasks");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, (rq, rs) -> {
            throw new IllegalStateException("tenant quota read failed", new RuntimeException(hikariTimeout()));
        });
        assertEquals(503, res.getStatus());
        assertEquals("3", res.getHeader("Retry-After"));
        assertTrue(res.getContentAsString().contains("DATABASE_UNAVAILABLE"));
    }

    @Test
    void filterRethrowsEverythingElse() {
        PoolExhaustionProblems.UnavailableFilter f = new PoolExhaustionProblems.UnavailableFilter(3);
        IllegalStateException other = new IllegalStateException("not a pool problem");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> f.doFilter(
                new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), (rq, rs) -> {
                    throw other;
                }));
        assertSame(other, thrown);
    }

    @Test
    void retryAfterComesFromTheSettingInWholeSeconds() {
        assertEquals(2, PoolExhaustionProblems.retryAfterSeconds(new MockEnvironment()));
        assertEquals(5, PoolExhaustionProblems.retryAfterSeconds(
                new MockEnvironment().withProperty(PoolExhaustionProblems.RETRY_AFTER, "5s")));
        assertEquals(1, PoolExhaustionProblems.retryAfterSeconds(
                new MockEnvironment().withProperty(PoolExhaustionProblems.RETRY_AFTER, "300ms")));
        assertEquals(1, PoolExhaustionProblems.retryAfterSeconds(
                new MockEnvironment().withProperty(PoolExhaustionProblems.RETRY_AFTER, "0s")));
    }
}
