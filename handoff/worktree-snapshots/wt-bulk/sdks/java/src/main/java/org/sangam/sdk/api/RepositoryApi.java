// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import org.sangam.sdk.http.ApiRequest;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.CloudEvent;
import org.sangam.sdk.model.Deployment;
import org.sangam.sdk.model.PublishResult;
import org.sangam.sdk.model.Page;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deployments, event registry and forms ({@code /deployments}, {@code /events/**}, {@code /forms/{key}}). */
public final class RepositoryApi extends ApiSupport {

    public RepositoryApi(HttpTransport http) {
        super(http);
    }

    /**
     * {@code POST /deployments} as multipart/form-data: parts {@code name}, {@code source}, {@code duplicateFiltering}
     * and one {@code files} part per resource (filename = map key, for example {@code bill.bpmn}). Governed releases
     * use {@code /packages} (R43); the tenant comes from the token.
     */
    public Deployment deploy(String name, String source, Boolean duplicateFiltering, Map<String, byte[]> resources,
                             String idempotencyKey) {
        String boundary = "----sangam" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (name != null) textPart(out, boundary, "name", name);
        if (source != null) textPart(out, boundary, "source", source);
        if (duplicateFiltering != null) textPart(out, boundary, "duplicateFiltering", duplicateFiltering.toString());
        for (Map.Entry<String, byte[]> e : resources.entrySet()) {
            String fn = e.getKey().replace("\"", "");
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + fn
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
            out.writeBytes(e.getValue());
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        return call(ApiRequest.post("/deployments").rawBody(out.toByteArray(), "multipart/form-data; boundary=" + boundary)
                .idempotencyKey(idempotencyKey), Deployment.class);
    }

    public Page<Deployment> deployments(Integer page, Integer size) {
        return readPage(ApiRequest.get("/deployments").query("page", page).query("size", size), Deployment.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> eventChannels() {
        return (List) callList(ApiRequest.get("/events/channels"), Map.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> eventDefinitions() {
        return (List) callList(ApiRequest.get("/events/definitions"), Map.class);
    }

    /** {@code POST /events/{channel}}: a CloudEvent, de-duplicated by (tenant, source, id) (R38). */
    public PublishResult publishEvent(String channel, CloudEvent event, String idempotencyKey) {
        return call(ApiRequest.post("/events/" + seg(channel)).body(event).idempotencyKey(idempotencyKey), PublishResult.class);
    }

    /** Builds a CloudEvents 1.0 event with a random id and the current time. */
    public static CloudEvent cloudEvent(String source, String type, Object data) {
        return new CloudEvent(UUID.randomUUID().toString(), source, type, "1.0", null, java.time.Instant.now(),
                "application/json", null, data, null);
    }

    /** {@code GET /forms/{key}}: the form JSON schema used by the task UI. */
    public Map<String, Object> form(String formKey) {
        return call(ApiRequest.get("/forms/" + seg(formKey)), MAP);
    }

    private static void textPart(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
