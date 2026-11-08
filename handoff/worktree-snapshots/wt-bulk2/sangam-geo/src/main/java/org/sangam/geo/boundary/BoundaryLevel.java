// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.boundary;

import java.util.Map;

/**
 * One tier of a layer (data, not code): for example tier 0 "region", tier 1 "district", tier 2 "block", tier 3
 * "village" — whatever the deploying organisation's hierarchy is called, in any language.
 *
 * @param depth       0 = root
 * @param parentLevel code of the level above (null for the root)
 */
public record BoundaryLevel(String code, int depth, Map<String, String> names, String parentLevel) {

    public BoundaryLevel {
        names = names == null ? Map.of() : Map.copyOf(names);
    }
}
