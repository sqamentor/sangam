// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Delegation-of-powers tables (IX.5 rule 3, K6, A04): versioned DMN tables of financial and administrative powers per
 * seat, with four-eyes approval (author ≠ approver) and an effective date. Distinct from person-to-person delegation
 * ({@code /admin/delegations}). An ACTIVE table is deployed so that {@code POST /decisions/{key}/evaluate} uses it.
 */
public interface PowerTablePort {

    record TestCase(String id, Map<String, Object> inputs, Map<String, Object> expected) {
    }

    /** state: DRAFT | IN_REVIEW | APPROVED | ACTIVE | SUPERSEDED | REJECTED. */
    record PowerTable(String id, String key, String name, int version, String state, Instant effectiveFrom,
                      String author, String approver, String dmnXml, List<TestCase> testCases, String deploymentId,
                      long revision) {
    }

    record TestOutcome(String id, boolean passed, Map<String, Object> actual) {
    }

    record CheckResult(List<String> gaps, List<String> overlaps, String hitPolicy, List<TestOutcome> tests) {
    }

    record TransitionRequest(String reason, Instant effectiveFrom) {
    }

    Page<PowerTable> list(RequestContext ctx, String q, Page.PageRequest page);

    PowerTable get(RequestContext ctx, String id);

    PowerTable create(RequestContext ctx, PowerTable table);

    PowerTable update(RequestContext ctx, String id, PowerTable table);

    CheckResult check(RequestContext ctx, String id);

    /** action: submit | approve | reject. */
    PowerTable transition(RequestContext ctx, String id, String action, TransitionRequest request);
}
