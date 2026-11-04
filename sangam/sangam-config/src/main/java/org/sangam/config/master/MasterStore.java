// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.master;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Storage of master codes. A scope is the configuration scope that owns the codes ({@code IN} for a national reference
 * list, {@code RJ} for a region's own sub-codes); lookups walk the scope's prefixes, so a region sees the reference
 * codes plus its own.
 */
public interface MasterStore {

    void put(MasterCode code);

    /** All versions of a code in one scope. */
    List<MasterCode> find(String scope, String scheme, String segment, String parentCode, String code);

    List<MasterCode> list(String scope, String scheme, String segment);

    /** In-memory implementation. */
    final class InMemory implements MasterStore {
        private final Map<String, List<MasterCode>> rows = new ConcurrentHashMap<>();

        @Override
        public void put(MasterCode c) {
            rows.computeIfAbsent(c.scope() + "|" + c.key(), k -> new ArrayList<>());
            List<MasterCode> l = rows.get(c.scope() + "|" + c.key());
            synchronized (l) {
                l.removeIf(x -> java.util.Objects.equals(x.validFrom(), c.validFrom()));
                l.add(c);
            }
        }

        @Override
        public List<MasterCode> find(String scope, String scheme, String segment, String parentCode, String code) {
            List<MasterCode> l = rows.get(scope + "|" + scheme + "|" + segment + "|" + (parentCode == null ? "" : parentCode)
                    + "|" + code);
            if (l == null) {
                return List.of();
            }
            synchronized (l) {
                return List.copyOf(l);
            }
        }

        @Override
        public List<MasterCode> list(String scope, String scheme, String segment) {
            String p = scope + "|" + scheme + "|" + segment + "|";
            List<MasterCode> out = new ArrayList<>();
            rows.forEach((k, v) -> {
                if (k.startsWith(p)) {
                    synchronized (v) {
                        out.addAll(v);
                    }
                }
            });
            return out;
        }
    }
}
