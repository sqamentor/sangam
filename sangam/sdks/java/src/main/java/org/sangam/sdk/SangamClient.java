// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk;

import org.sangam.sdk.api.AdminApi;
import org.sangam.sdk.api.AuditApi;
import org.sangam.sdk.api.DecisionsApi;
import org.sangam.sdk.api.OpsApi;
import org.sangam.sdk.api.ProcessesApi;
import org.sangam.sdk.api.RepositoryApi;
import org.sangam.sdk.api.TasksApi;
import org.sangam.sdk.api.WorkApi;
import org.sangam.sdk.auth.TokenProvider;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.http.RetryPolicy;
import org.sangam.sdk.log.SangamLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/**
 * Entry point of the Sangam Java SDK: a typed client for {@code /api/v1}. Thread-safe; share one instance.
 *
 * <pre>{@code
 * SangamClient client = SangamClient.builder()
 *     .baseUrl("https://sangam.example.gov.in")
 *     .tokenProvider(ClientCredentialsTokenProvider.builder()
 *         .tokenEndpoint("https://idp/realms/sangam/protocol/openid-connect/token")
 *         .clientId("bill-worker").clientSecret(secret).build())
 *     .build();
 * ProcessInstance pi = client.processes().start("p1_bill", "BILL-2026-0001", Map.of("amount", 125000));
 * }</pre>
 */
public final class SangamClient {

    private final HttpTransport transport;
    private final WorkApi work;
    private final TasksApi tasks;
    private final ProcessesApi processes;
    private final DecisionsApi decisions;
    private final AuditApi audit;
    private final AdminApi admin;
    private final OpsApi ops;
    private final RepositoryApi repository;

    private SangamClient(Builder b) {
        HttpClient http = b.httpClient != null ? b.httpClient : HttpClient.newBuilder()
                .connectTimeout(b.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.transport = new HttpTransport(http, Objects.requireNonNull(b.baseUrl, "baseUrl"), b.tokenProvider,
                b.retryPolicy, b.requestTimeout, b.logger, b.userAgent, b.seatHeaders());
        this.work = new WorkApi(transport);
        this.tasks = new TasksApi(transport);
        this.processes = new ProcessesApi(transport);
        this.decisions = new DecisionsApi(transport);
        this.audit = new AuditApi(transport);
        this.admin = new AdminApi(transport);
        this.ops = new OpsApi(transport);
        this.repository = new RepositoryApi(transport);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Worker API {@code /work/**} (used by {@link org.sangam.sdk.worker.SangamWorker}). */
    public WorkApi work() { return work; }
    /** User tasks {@code /tasks/**}. */
    public TasksApi tasks() { return tasks; }
    /** Process/case instances, messages, signals. */
    public ProcessesApi processes() { return processes; }
    /** DMN decisions. */
    public DecisionsApi decisions() { return decisions; }
    /** Audit timeline and chain verification. */
    public AuditApi audit() { return audit; }
    /** Admin basics. */
    public AdminApi admin() { return admin; }
    /** Incidents, reconciliation, outbox, jobs. */
    public OpsApi ops() { return ops; }
    /** Deployments, events, forms. */
    public RepositoryApi repository() { return repository; }
    /** {@code GET /me}: identity, tenant, seat context and permissions of the caller. */
    public java.util.Map<String, Object> me() {
        org.sangam.sdk.http.ApiResponse<java.util.Map<String, Object>> r = transport.execute(
                org.sangam.sdk.http.ApiRequest.get("/me"),
                transport.json().getTypeFactory().constructMapType(java.util.LinkedHashMap.class, String.class, Object.class));
        return r.body();
    }

    /** Low-level transport for endpoints not yet wrapped. */
    public HttpTransport transport() { return transport; }
    public SangamLogger logger() { return transport.logger(); }

    public static final class Builder {
        private URI baseUrl;
        private TokenProvider tokenProvider = TokenProvider.none();
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private SangamLogger logger = SangamLogger.system();
        private HttpClient httpClient;
        private String userAgent;
        private String seat;
        private String actingForSeat;

        /** Server root, for example {@code https://host:8080}; a trailing {@code /api/v1} is accepted. */
        public Builder baseUrl(String v) { this.baseUrl = URI.create(v); return this; }
        public Builder baseUrl(URI v) { this.baseUrl = v; return this; }
        public Builder tokenProvider(TokenProvider v) { this.tokenProvider = v; return this; }
        /** Static bearer token (dev profile). */
        public Builder token(String v) { this.tokenProvider = TokenProvider.of(v); return this; }
        public Builder retryPolicy(RetryPolicy v) { this.retryPolicy = v; return this; }
        public Builder connectTimeout(Duration v) { this.connectTimeout = v; return this; }
        public Builder requestTimeout(Duration v) { this.requestTimeout = v; return this; }
        public Builder logger(SangamLogger v) { this.logger = v; return this; }
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }
        public Builder userAgent(String v) { this.userAgent = v; return this; }
        /** Default {@code X-Sangam-Seat}: the seat context the caller acts in (IX.5 rule 1). */
        public Builder seat(String v) { this.seat = v; return this; }
        /** Default {@code X-Sangam-Acting-For-Seat}: acting authority for a substituted seat (IX.5 rule 4). */
        public Builder actingForSeat(String v) { this.actingForSeat = v; return this; }

        java.util.Map<String, String> seatHeaders() {
            java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
            if (seat != null) h.put("X-Sangam-Seat", seat);
            if (actingForSeat != null) h.put("X-Sangam-Acting-For-Seat", actingForSeat);
            return h;
        }

        public SangamClient build() {
            return new SangamClient(this);
        }
    }
}
