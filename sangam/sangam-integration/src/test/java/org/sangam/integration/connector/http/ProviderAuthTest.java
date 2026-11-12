// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.sim.serverless.ServerlessSimulator;

/** M-067: the provider auth modes and the async callback of the HTTP connector against the serverless simulator. */
class ProviderAuthTest {

    private ServerlessSimulator sim;
    private final Map<String, String> secrets = new LinkedHashMap<>();

    @BeforeEach
    void start() {
        sim = new ServerlessSimulator("cb-secret").start();
        secrets.put("secret://aws/access", ServerlessSimulator.AWS_ACCESS_KEY);
        secrets.put("secret://aws/secret", ServerlessSimulator.AWS_SECRET_KEY);
        secrets.put("secret://fn/key", ServerlessSimulator.FUNCTION_KEY);
        secrets.put("secret://gcp/sa", sim.serviceAccountKeyJson());
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    private LiveHttpConnectors.Spec spec(LiveHttpConnectors.Auth auth, LiveHttpConnectors.Callback callback,
                                         String failureHeader) {
        return new LiveHttpConnectors.Spec("fn", sim.baseUri().toString(), "POST", "c", null, null, auth, null,
                Duration.ofSeconds(2), Duration.ofSeconds(5), 1, List.of(), true, Map.of(), Map.of(), null, callback,
                failureHeader);
    }

    private static ConnectorResult call(HttpRestConnector c, UUID op, String path, Map<String, Object> headers,
                                        Map<String, Object> body) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("path", path);
        in.put("headers", headers);
        in.put("body", body);
        return c.invoke(new ConnectorRequest("t1", op, op.toString(), 1, null, "bk", "invoke", in, null));
    }

    private static LiveHttpConnectors.Provider provider(String region, String service, String headerName,
                                                        String audience, String saRef) {
        return new LiveHttpConnectors.Provider(region, service, "secret://aws/access", "secret://aws/secret", null,
                headerName, audience, saRef, null);
    }

    @Test
    void awsSigV4SignsALambdaInvokeTheSimulatorVerifies() {
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec(new LiveHttpConnectors.Auth(
                LiveHttpConnectors.AuthType.AWS_SIGV4, null, null, null, null, null,
                provider(ServerlessSimulator.AWS_REGION, "lambda", null, null, null)), null, null),
                SecretResolver.of(secrets), false);
        ConnectorResult r = call(c, UUID.randomUUID(), "/2015-03-31/functions/greet/invocations", Map.of(),
                Map.of("name", "x"));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r, String.valueOf(r));
        assertTrue(String.valueOf(s.output().get("body")).contains("greet"), String.valueOf(s.output()));
    }

    @Test
    void aWrongAwsSecretIsRefusedBySignature() {
        secrets.put("secret://aws/secret", "not-the-key");
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec(new LiveHttpConnectors.Auth(
                LiveHttpConnectors.AuthType.AWS_SIGV4, null, null, null, null, null,
                provider(ServerlessSimulator.AWS_REGION, "lambda", null, null, null)), null, null),
                SecretResolver.of(secrets), false);
        assertFalse(call(c, UUID.randomUUID(), "/2015-03-31/functions/greet/invocations", Map.of(), Map.of())
                instanceof ConnectorResult.Success);
    }

    @Test
    void aFunctionErrorHeaderIsABusinessFailure() {
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec(new LiveHttpConnectors.Auth(
                LiveHttpConnectors.AuthType.AWS_SIGV4, null, null, null, null, null,
                provider(ServerlessSimulator.AWS_REGION, "lambda", null, null, null)), null, "X-Amz-Function-Error"),
                SecretResolver.of(secrets), false);
        ConnectorResult r = call(c, UUID.randomUUID(), "/2015-03-31/functions/greet/invocations", Map.of(),
                Map.of("fail", true));
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r, String.valueOf(r));
        assertEquals("FUNCTION_ERROR", f.code());
    }

    @Test
    void theFunctionKeyIsSentFromTheSecretPort() {
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec(new LiveHttpConnectors.Auth(
                LiveHttpConnectors.AuthType.FUNCTION_KEY, "secret://fn/key", null, null, null, null, null), null, null),
                SecretResolver.of(secrets), false);
        assertInstanceOf(ConnectorResult.Success.class, call(c, UUID.randomUUID(), "/api/hello", Map.of(), Map.of("a", 1)));
        secrets.put("secret://fn/key", "rotated-wrong");
        assertFalse(call(c, UUID.randomUUID(), "/api/hello", Map.of(), Map.of()) instanceof ConnectorResult.Success,
                "the key is read on every call");
    }

    @Test
    void anUnknownFunctionKeyReferenceFailsTheBuild() {
        assertThrows(IllegalStateException.class, () -> LiveHttpConnectors.build("t1", spec(new LiveHttpConnectors.Auth(
                LiveHttpConnectors.AuthType.FUNCTION_KEY, "secret://fn/missing", null, null, null, null, null), null, null),
                SecretResolver.of(secrets), false));
    }

    @Test
    void aGcpIdTokenFromTheServiceAccountKeyIsAccepted() {
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec(new LiveHttpConnectors.Auth(
                LiveHttpConnectors.AuthType.GCP_ID_TOKEN, null, null, null, null, null,
                provider(null, null, null, sim.runAudience(), "secret://gcp/sa")), null, null),
                SecretResolver.of(secrets), false);
        assertInstanceOf(ConnectorResult.Success.class, call(c, UUID.randomUUID(), "/run/svc", Map.of(), Map.of("q", 1)));
    }

    @Test
    void aGcpIdTokenForAnotherAudienceIsRefused() {
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec(new LiveHttpConnectors.Auth(
                LiveHttpConnectors.AuthType.GCP_ID_TOKEN, null, null, null, null, null,
                provider(null, null, null, "https://other.example", "secret://gcp/sa")), null, null),
                SecretResolver.of(secrets), false);
        assertFalse(call(c, UUID.randomUUID(), "/run/svc", Map.of(), Map.of()) instanceof ConnectorResult.Success);
    }

    @Test
    void anAsyncInvokeCarriesTheCallbackAndTheSimulatorCallsBackWithTheCorrelationKey() {
        LiveHttpConnectors.Callback cb = new LiveHttpConnectors.Callback(sim.uri("/api/callback-sink").toString(),
                "sangam.fn.done", "body", null);
        HttpRestConnector c = LiveHttpConnectors.build("t1", spec(new LiveHttpConnectors.Auth(
                LiveHttpConnectors.AuthType.AWS_SIGV4, null, null, null, null, null,
                provider(ServerlessSimulator.AWS_REGION, "lambda", null, null, null)), cb, null),
                SecretResolver.of(secrets), false);
        UUID op = UUID.randomUUID();
        ConnectorResult r = call(c, op, "/2015-03-31/functions/greet/invocations",
                Map.of("X-Amz-Invocation-Type", "Event"), Map.of("n", 1));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r, String.valueOf(r));
        assertTrue(s.output().containsKey("callback"), "the success output says a callback is awaited");
        assertEquals(1, sim.callbacks().size());
        ServerlessSimulator.Callback sent = sim.callbacks().get(0);
        assertEquals("sangam.fn.done", sent.eventType());
        assertEquals(op.toString(), sent.correlationKey());
    }
}
