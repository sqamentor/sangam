// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.profile;

import org.sangam.config.common.Tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ordered profile layers of a deployment, lowest precedence first. The list is configuration, for example
 * {@code [reference, category, donor, jurisdiction, organisation, unit]} or
 * {@code [platform, country, region, organisation, office]}. A resolved chain must respect this order (a document of
 * a lower layer may not follow one of a higher layer); the most specific layer wins.
 *
 * <p>Shape: {@code layers: [{id: reference, overridable: false, required: [locale]}, {id: jurisdiction}, ...]}.
 * {@code overridable: false} makes every path the layer sets locked for later layers.
 */
public record LayerModel(List<Layer> layers) {

    public record Layer(String id, boolean overridable, List<String> required) {
    }

    public LayerModel {
        layers = List.copyOf(layers);
    }

    /** A permissive model accepting any layer names in any order (used when a deployment defines none). */
    public static LayerModel open() {
        return new LayerModel(List.of());
    }

    public static LayerModel from(Object v) {
        List<Layer> out = new ArrayList<>();
        for (Object o : v instanceof List<?> l ? l : List.of()) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> x = Tree.map(m);
                out.add(new Layer(norm(Tree.str(x.get("id"), "")), Tree.bool(x.get("overridable"), true),
                        Tree.strings(x.get("required"))));
            } else if (o != null) {
                out.add(new Layer(norm(o.toString()), true, List.of()));
            }
        }
        return new LayerModel(out);
    }

    public boolean isOpen() {
        return layers.isEmpty();
    }

    /** Rank of a layer (0-based), or -1 if unknown. */
    public int rank(String layer) {
        String n = norm(layer);
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).id().equals(n)) {
                return i;
            }
        }
        return -1;
    }

    public boolean known(String layer) {
        return isOpen() || rank(layer) >= 0;
    }

    public Layer layer(String id) {
        int r = rank(id);
        return r < 0 ? null : layers.get(r);
    }

    public static String norm(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
