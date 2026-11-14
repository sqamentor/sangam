// SPDX-License-Identifier: Apache-2.0
package org.sangam.kube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The Kubernetes API calls Sangam needs, by REST path (for example {@code /apis/batch/v1/namespaces/ns/jobs/name}).
 * {@link KubeHttp} is the real client; tests use in-memory fakes. Shared by the operator (M-090) and the backup console
 * (M-102), owner decision 155.
 */
public interface Kube {

    /** The object, or {@code null} when the API server answers 404. */
    JsonNode get(String path) throws IOException;

    /** A list call ({@code path} is a collection path, optionally with {@code ?labelSelector=...}); its {@code items}. */
    JsonNode list(String path) throws IOException;

    /**
     * POST to a collection. Returns the created object, or {@code null} when it already exists (409 AlreadyExists), so a
     * create with a deterministic name is idempotent.
     */
    JsonNode create(String collectionPath, ObjectNode body) throws IOException;

    /** Server-side apply with the client's field manager, forced: the object's managed fields become {@code body}. */
    JsonNode apply(String path, ObjectNode body) throws IOException;

    /** JSON merge patch of the {@code status} subresource. */
    JsonNode patchStatus(String path, ObjectNode status) throws IOException;

    /** {@code ?labelSelector=<selector>} with the selector URL-encoded, to append to a collection path. */
    static String labelSelector(String selector) {
        return "?labelSelector=" + URLEncoder.encode(selector, StandardCharsets.UTF_8);
    }

    /** An API answer other than the ones the methods above turn into a value. */
    final class KubeException extends IOException {
        private final int status;

        public KubeException(int status, String message) {
            super("Kubernetes API answered " + status + ": " + message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
