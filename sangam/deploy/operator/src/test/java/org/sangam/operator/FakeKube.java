// SPDX-License-Identifier: Apache-2.0
package org.sangam.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.kube.Kube;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** In-memory API server: objects by path; records every write. */
final class FakeKube implements Kube {

    static final ObjectMapper JSON = new ObjectMapper();
    final Map<String, ObjectNode> objects = new TreeMap<>();
    final List<String> writes = new ArrayList<>();

    @Override
    public JsonNode get(String path) {
        ObjectNode o = objects.get(path);
        return o == null ? null : o.deepCopy();
    }

    @Override
    public JsonNode list(String path) {
        String collection = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String selector = path.contains("labelSelector=") ? java.net.URLDecoder.decode(
                path.substring(path.indexOf("labelSelector=") + 14), java.nio.charset.StandardCharsets.UTF_8) : null;
        ArrayNode items = JSON.createArrayNode();
        objects.forEach((p, o) -> {
            if (p.startsWith(collection + "/") && p.indexOf('/', collection.length() + 1) < 0 && matches(o, selector)) {
                items.add(o.deepCopy());
            }
        });
        return items;
    }

    private static boolean matches(JsonNode o, String selector) {
        if (selector == null) {
            return true;
        }
        for (String term : selector.split(",")) {
            String[] kv = term.split("=", 2);
            if (!kv[1].equals(o.path("metadata").path("labels").path(kv[0]).asText(null))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public JsonNode create(String collectionPath, ObjectNode body) {
        String path = collectionPath + "/" + body.path("metadata").path("name").asText();
        writes.add("create " + path);
        if (objects.containsKey(path)) {
            return null;
        }
        objects.put(path, body.deepCopy());
        return body;
    }

    @Override
    public JsonNode apply(String path, ObjectNode body) {
        writes.add("apply " + path);
        ObjectNode stored = body.deepCopy();
        ObjectNode old = objects.get(path);
        long generation = old == null ? 1 : old.path("metadata").path("generation").asLong(1)
                + (old.path("spec").equals(body.path("spec")) ? 0 : 1);
        ((ObjectNode) stored.path("metadata")).put("generation", generation);
        if (old != null && old.has("status")) {
            stored.set("status", old.get("status"));
        }
        objects.put(path, stored);
        return stored.deepCopy();
    }

    @Override
    public JsonNode patchStatus(String path, ObjectNode status) {
        writes.add("status " + path);
        ObjectNode o = objects.get(path);
        o.set("status", status.deepCopy());
        return o;
    }

    /** Adds a custom resource. */
    ObjectNode put(String path, String json) {
        try {
            ObjectNode o = (ObjectNode) JSON.readTree(json);
            objects.put(path, o);
            return o;
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
