// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;

/**
 * Modeller tooling around packages: deployment validation (R44 profile, X.2.4.6 closure check, FLOWABLE-INTO-OPERATON FC-46), model tests
 * (R80), Git sync (R69), review comments (R69) and the migration assessment of foreign models (EC-21, R48).
 */
public interface PackageToolsPort {

    /** severity: ERROR | WARNING | INFO. */
    record ValidationIssue(String severity, String artifact, String elementId, String message, String code) {
    }

    record TestRun(String name, boolean passed, String message, long durationMs) {
    }

    record GitStatus(String remote, String branch, int ahead, int behind, Instant lastSync, List<String> conflicts) {
    }

    /** provider: github | gitlab | gitea | generic. credentialRef names a secret; secrets are never stored inline. */
    record GitConfig(String remote, String branch, String credentialRef, String provider) {
    }

    record GitSyncRequest(String message) {
    }

    record GitSyncResult(int ahead, int behind, List<String> conflicts) {
    }

    record PackageComment(String id, String author, String seatName, String actedFor, Instant createdAt, String text,
                          String elementId) {
    }

    record AddCommentRequest(String text, String elementId) {
    }

    record AssessmentFile(String name, byte[] content) {
    }

    /** classification: SUPPORTED | MAPPED | PATTERN | REJECTED. */
    record AssessmentRow(String file, String element, String construct, String classification, String note) {
    }

    List<ValidationIssue> validate(RequestContext ctx, String packageId);

    List<TestRun> runTests(RequestContext ctx, String packageId);

    GitStatus gitStatus(RequestContext ctx, String packageId);

    GitStatus configureGit(RequestContext ctx, String packageId, GitConfig config);

    /** direction: pull | push. */
    GitSyncResult gitSync(RequestContext ctx, String packageId, String direction, GitSyncRequest request);

    List<PackageComment> comments(RequestContext ctx, String packageId);

    PackageComment addComment(RequestContext ctx, String packageId, AddCommentRequest request);

    List<AssessmentRow> assess(RequestContext ctx, List<AssessmentFile> files);
}
