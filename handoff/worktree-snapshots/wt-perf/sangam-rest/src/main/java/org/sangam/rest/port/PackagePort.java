// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Executable packages (R43 / X.2.4; FLOWABLE-INTO-OPERATON FC-41 app bundles): a hash-pinned bundle of BPMN, DMN, CMMN, forms, event and
 * channel models, worker contracts and connector configuration, with the lifecycle
 * DRAFT → IN_REVIEW → APPROVED → DEPLOYED → ACTIVE → RETIRED → ARCHIVED (plus REJECTED→DRAFT, WITHDRAWN, SUSPENDED).
 * Reviewer, approver and deployer must differ from the author, by person and seat.
 */
public interface PackagePort {

    record PackageEvent(String transition, String fromState, String toState, String actor, String seatId,
                        String reason, Instant at) {
    }

    record Package(String id, String key, String name, long revision, String state, String packageHash,
                   Map<String, String> resourceHashes, String author, String reviewer, String approver,
                   String deployer, String environment, Instant activateAt, String deploymentId, Instant createdAt,
                   Instant updatedAt, List<PackageEvent> history, Map<String, String> resources) {
    }

    /**
     * {@code resources}: resource name → content. Text resources (.bpmn, .dmn, .cmmn, .form, .json, .xml, .yaml) are
     * sent and returned as UTF-8 text; any other resource as base64. In {@link Package#resources()} the same rule
     * applies.
     */
    record CreatePackageRequest(String key, String name, Map<String, String> resources) {
    }

    record UpdatePackageRequest(String name, Map<String, String> resources) {
    }

    /** action: submit | approve | reject | deploy | activate | suspend | resume | withdraw | retire. */
    record TransitionRequest(String action, String reason, String environment, Instant activateAt) {
    }

    record DiffEntry(String resource, String change, String oldHash, String newHash) {
    }

    Package create(RequestContext ctx, CreatePackageRequest request);

    Page<Package> list(RequestContext ctx, String key, String state, Page.PageRequest page);

    Package get(RequestContext ctx, String packageId);

    /** DRAFT only; {@code expectedRevision} from {@code If-Match} ({@code 409 REVISION_CONFLICT} when stale). */
    Package update(RequestContext ctx, String packageId, long expectedRevision, UpdatePackageRequest request);

    Package transition(RequestContext ctx, String packageId, TransitionRequest request);

    /** Semantic diff against the current ACTIVE version of the same key (viewed before approval). */
    List<DiffEntry> diff(RequestContext ctx, String packageId);
}
