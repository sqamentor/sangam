// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory profile repository (development, tests, and a read cache in front of the JDBC store). */
public final class InMemoryProfileRepository implements ProfileRepository {

    private final Map<String, Map<String, Stored>> byId = new ConcurrentHashMap<>();

    /** A repository with every given document ACTIVE (shipped reference data). */
    public static InMemoryProfileRepository active(Collection<ProfileDocument> docs) {
        InMemoryProfileRepository r = new InMemoryProfileRepository();
        for (ProfileDocument d : docs) {
            r.save(d, Status.ACTIVE);
        }
        return r;
    }

    @Override
    public void save(ProfileDocument doc, Status status) {
        byId.computeIfAbsent(doc.id(), k -> new ConcurrentHashMap<>())
                .put(doc.version(), new Stored(doc, status, doc.contentHash()));
    }

    @Override
    public void setStatus(String id, String version, Status status) {
        Map<String, Stored> v = byId.get(id);
        Stored s = v == null ? null : v.get(version);
        if (s == null) {
            throw new IllegalArgumentException("no profile " + id + "@" + version);
        }
        v.put(version, new Stored(s.document(), status, s.hash()));
    }

    @Override
    public Optional<Stored> get(String id, String version) {
        Map<String, Stored> v = byId.get(id);
        return Optional.ofNullable(v == null ? null : v.get(version));
    }

    @Override
    public List<Stored> versions(String id) {
        Map<String, Stored> v = byId.get(id);
        if (v == null) {
            return List.of();
        }
        TreeMap<String, Stored> sorted = new TreeMap<>(ProfileRepository::compareVersions);
        sorted.putAll(v);
        return new ArrayList<>(sorted.values());
    }

    @Override
    public List<String> ids() {
        return byId.keySet().stream().sorted().toList();
    }
}
