// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Storage of profile documents by (id, version) with a lifecycle status. */
public interface ProfileRepository {

    enum Status { DRAFT, SUBMITTED, APPROVED, ACTIVE, RETIRED, REJECTED }

    record Stored(ProfileDocument document, Status status, String hash) {
    }

    /** Inserts or replaces (id, version). */
    void save(ProfileDocument doc, Status status);

    void setStatus(String id, String version, Status status);

    Optional<Stored> get(String id, String version);

    List<Stored> versions(String id);

    /** All ids known to the repository. */
    List<String> ids();

    /**
     * The ACTIVE version effective on {@code asOf}; among several, the one with the latest {@code effectiveFrom},
     * then the highest version string.
     */
    default Optional<ProfileDocument> find(String id, LocalDate asOf) {
        return versions(id).stream()
                .filter(s -> s.status() == Status.ACTIVE && s.document().isEffective(asOf))
                .map(Stored::document)
                .max((a, b) -> {
                    LocalDate fa = a.effectiveFrom() == null ? LocalDate.MIN : a.effectiveFrom();
                    LocalDate fb = b.effectiveFrom() == null ? LocalDate.MIN : b.effectiveFrom();
                    int c = fa.compareTo(fb);
                    return c != 0 ? c : compareVersions(a.version(), b.version());
                });
    }

    /** Dotted numeric-aware version comparison (2026.11.10 &gt; 2026.11.9). */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-]");
        String[] y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c;
            if (p.matches("\\d+") && q.matches("\\d+")) {
                c = Long.compare(Long.parseLong(p), Long.parseLong(q));
            } else {
                c = p.compareTo(q);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
