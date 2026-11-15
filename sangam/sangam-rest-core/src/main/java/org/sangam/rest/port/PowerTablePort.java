// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Delegation-of-powers tables (IX.5 rule 3, K6, A04): versioned DMN tables of financial and administrative powers per
 * seat, with four-eyes approval (author ≠ approver) and an effective date. Distinct from person-to-person delegation
 * ({@code /admin/delegations}). An ACTIVE table is deployed so that {@code POST /decisions/{key}/evaluate} uses it.
 */
public interface PowerTablePort {

    record TestCase(String id, Map<String, Object> inputs, Map<String, Object> expected) {
    }

    /**
     * state: DRAFT | IN_REVIEW | APPROVED | ACTIVE | SUPERSEDED | REJECTED. Optional on a request (SL-81), with
     * these defaults: {@code version} 0 (server-assigned: the next version of the key on create, kept on update);
     * {@code revision} 0 (none read: server-assigned on create; an update of a DRAFT refuses 0 with 409
     * REVISION_CONFLICT).
     */
    record PowerTable(String id, String key, String name, int version, String state, Instant effectiveFrom,
                      String author, String approver, String dmnXml, List<TestCase> testCases, String deploymentId,
                      long revision) {

        /** Request binding: an omitted optional number takes the documented default above, never a guess. */
        @JsonCreator
        public static PowerTable fromRequest(@JsonProperty("id") String id, @JsonProperty("key") String key,
                                             @JsonProperty("name") String name, @JsonProperty("version") Integer version,
                                             @JsonProperty("state") String state,
                                             @JsonProperty("effectiveFrom") Instant effectiveFrom,
                                             @JsonProperty("author") String author,
                                             @JsonProperty("approver") String approver,
                                             @JsonProperty("dmnXml") String dmnXml,
                                             @JsonProperty("testCases") List<TestCase> testCases,
                                             @JsonProperty("deploymentId") String deploymentId,
                                             @JsonProperty("revision") Long revision) {
            return new PowerTable(id, key, name, version == null ? 0 : version, state, effectiveFrom, author, approver,
                    dmnXml, testCases, deploymentId, revision == null ? 0L : revision);
        }
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
