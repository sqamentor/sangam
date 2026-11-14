// SPDX-License-Identifier: Apache-2.0
package org.sangam.kube;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KubeHttpTest {

    static final ObjectMapper JSON = new ObjectMapper();

    final List<HttpRequest> sent = new ArrayList<>();
    final Deque<Object[]> answers = new ArrayDeque<>();
    final AtomicInteger tokenReads = new AtomicInteger();
    final KubeHttp kube = new KubeHttp(r -> {
        sent.add(r);
        Object[] a = answers.removeFirst();
        return response((int) a[0], (String) a[1], r);
    }, "https://10.0.0.1:443/", () -> "tok-" + tokenReads.incrementAndGet(), "sangam-operator");

    @Test
    void getAnswersNullOn404AndSendsTheCurrentToken() throws Exception {
        answers.add(new Object[]{404, "{\"kind\":\"Status\",\"code\":404}"});
        answers.add(new Object[]{200, "{\"metadata\":{\"name\":\"x\"}}"});
        assertNull(kube.get("/apis/batch/v1/namespaces/a/jobs/x"));
        assertEquals("x", kube.get("/apis/batch/v1/namespaces/a/jobs/x").path("metadata").path("name").asText());
        assertEquals("https://10.0.0.1:443/apis/batch/v1/namespaces/a/jobs/x", sent.get(0).uri().toString());
        assertEquals("Bearer tok-1", sent.get(0).headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer tok-2", sent.get(1).headers().firstValue("Authorization").orElseThrow(),
                "the rotated service-account token is read for every request");
    }

    @Test
    void createIsIdempotentOn409() throws Exception {
        answers.add(new Object[]{409, "{\"reason\":\"AlreadyExists\"}"});
        ObjectNode body = JSON.createObjectNode();
        body.putObject("metadata").put("name", "j");
        assertNull(kube.create("/apis/batch/v1/namespaces/a/jobs", body));
        assertEquals("POST", sent.get(0).method());
        assertEquals("application/json", sent.get(0).headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void applyIsServerSideApplyWithTheConfiguredFieldManager() throws Exception {
        answers.add(new Object[]{200, "{}"});
        kube.apply("/apis/apps/v1/namespaces/a/deployments/d", JSON.createObjectNode());
        HttpRequest r = sent.get(0);
        assertEquals("PATCH", r.method());
        assertEquals("application/apply-patch+yaml", r.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("fieldManager=sangam-operator&force=true", r.uri().getQuery());
    }

    @Test
    void statusIsAMergePatchOfTheSubresource() throws Exception {
        answers.add(new Object[]{200, "{}"});
        kube.patchStatus("/apis/sangam.io/v1alpha1/namespaces/a/sangaminstallations/s",
                JSON.createObjectNode().put("phase", "Ready"));
        assertTrue(sent.get(0).uri().getPath().endsWith("/sangaminstallations/s/status"));
        assertEquals("application/merge-patch+json", sent.get(0).headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void otherErrorsCarryTheApiMessage() {
        answers.add(new Object[]{403, "{\"kind\":\"Status\",\"message\":\"jobs.batch is forbidden\"}"});
        Kube.KubeException e = assertThrows(Kube.KubeException.class, () -> kube.list("/apis/batch/v1/namespaces/a/jobs"));
        assertEquals(403, e.status());
        assertTrue(e.getMessage().contains("jobs.batch is forbidden"));
        assertFalse(e.getMessage().contains("tok-"), "the token is never in an error");
    }

    @Test
    void relativeOrTraversingPathsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> kube.get("apis/x"));
        assertThrows(IllegalArgumentException.class, () -> kube.get("/api/v1/../x"));
    }

    @Test
    void aLabelSelectorIsEncodedAndListReturnsTheItems() throws Exception {
        answers.add(new Object[]{200, "{\"items\":[{\"metadata\":{\"name\":\"a\"}}]}"});
        String path = "/apis/batch/v1/namespaces/a/cronjobs" + Kube.labelSelector("app.kubernetes.io/component=backup,x=y");
        assertEquals(1, kube.list(path).size());
        assertEquals("labelSelector=app.kubernetes.io/component=backup,x=y", sent.get(0).uri().getQuery());
        assertTrue(sent.get(0).uri().getRawQuery().contains("%2C"), "the selector is URL-encoded on the wire");
    }

    @Test
    void aFieldManagerIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new KubeHttp(r -> null, "https://h", () -> "t", " "));
    }

    static HttpResponse<String> response(int status, String body, HttpRequest r) {
        return new HttpResponse<>() {
            public int statusCode() {
                return status;
            }

            public HttpRequest request() {
                return r;
            }

            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            public String body() {
                return body;
            }

            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            public URI uri() {
                return r.uri();
            }

            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
