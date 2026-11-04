package org.sangam.spike.sim;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Tiny HTTP client for the simulator tests. */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Http() { }

    public static HttpResponse<String> post(URI uri, String contentType, String body, String... headers) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return send(b.build());
    }

    public static HttpResponse<String> get(URI uri) throws IOException {
        return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build());
    }

    private static HttpResponse<String> send(HttpRequest r) throws IOException {
        try {
            return CLIENT.send(r, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
