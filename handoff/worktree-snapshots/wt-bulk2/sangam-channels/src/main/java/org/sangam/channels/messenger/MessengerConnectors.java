// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Outbound messenger connectors over the providers' public HTTP APIs. All three are capability class (c): these send APIs
 * offer no idempotency key or outcome lookup by our operationId, so a lost answer becomes UNKNOWN_OUTCOME, never a blind
 * resend that a person would see twice. Each tenant supplies its own provider account through secret references. Sending to
 * a recipient without a recorded opt-in fails before any call.
 */
public final class MessengerConnectors {

    private MessengerConnectors() {
    }

    /** {@code optedIn.apply(request)} answers whether the recipient has an active opt-in. */
    private abstract static class Base implements SangamConnector {
        final ConnectorDescriptor descriptor;
        final HttpTransport transport;
        final SecretResolver secrets;
        final Function<ConnectorRequest, Boolean> optedIn;

        Base(String id, String type, ConnectorProfile profile, HttpTransport transport, SecretResolver secrets, Function<ConnectorRequest, Boolean> optedIn) {
            this.descriptor = new ConnectorDescriptor(id, type, CapabilityClass.C_NEITHER, List.of("send"), profile);
            this.transport = transport;
            this.secrets = secrets;
            this.optedIn = optedIn;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public final ConnectorResult invoke(ConnectorRequest r) {
            if (!Boolean.TRUE.equals(optedIn.apply(r))) {
                return ConnectorResult.BusinessFailure.of("RECIPIENT_NOT_OPTED_IN", "no active opt-in for this recipient");
            }
            try {
                return doSend(r);
            } catch (IllegalArgumentException e) {
                return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
            }
        }

        abstract ConnectorResult doSend(ConnectorRequest r);

        ConnectorResult post(ConnectorRequest r, URI uri, Map<String, String> headers, Object body, Function<JsonNode, ConnectorResult> onBody) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put("Content-Type", "application/json");
            long deadline = r.deadline() == null ? 0 : r.deadline().toEpochMilli();
            return switch (transport.execute(r.tenantId(), HttpTransport.Call.post(uri, h, Json.bytes(body), false), deadline)) {
                case HttpTransport.Outcome.NoEffect n -> ConnectorResult.TransientFailure.noEffect(n.reason());
                case HttpTransport.Outcome.Unknown u -> ConnectorResult.OutcomeUnknown.of(u.reason());
                case HttpTransport.Outcome.Response resp -> {
                    int s = resp.status();
                    if (s == 429 || s == 503) {
                        yield new ConnectorResult.TransientFailure("HTTP " + s, true, resp.retryAfter().orElse(null));
                    }
                    if (s >= 500) {
                        yield ConnectorResult.OutcomeUnknown.of("HTTP " + s + " after the request was sent");
                    }
                    if (s >= 400) {
                        yield ConnectorResult.BusinessFailure.of("HTTP_" + s, abbreviate(resp.bodyString()));
                    }
                    JsonNode t = Json.tree(resp.body());
                    yield onBody.apply(t == null ? Json.MAPPER.createObjectNode() : t);
                }
            };
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    // ---- Teams (Bot Framework) -----------------------------------------------------------------

    /**
     * Sends an activity to a conversation with the public Bot Framework REST API. Input: {@code serviceUrl} (from the stored
     * conversation reference, see {@link InboundMessageParsers#teamsReference}), {@code conversationId}, {@code text}, optional
     * {@code botId}, {@code recipientId}, {@code card} (Adaptive Card JSON) and {@code deepLink}. The bearer token comes from
     * {@code tokenRef}. The secret binding must fetch it by client credentials (scope
     * {@code https://api.botframework.com/.default}) and refresh it before expiry. Only a {@code serviceUrl} from a verified
     * activity may be used, because the token is sent there. Sensitive completions stay in Sangam: a card only carries a deep link.
     */
    public static SangamConnector teams(String id, ConnectorProfile profile, HttpTransport transport, SecretResolver secrets, String tokenRef, Function<ConnectorRequest, Boolean> optedIn) {
        return new Base(id, "teams", profile, transport, secrets, optedIn) {
            @Override
            ConnectorResult doSend(ConnectorRequest r) {
                URI base = URI.create(r.required("serviceUrl").replaceAll("/+$", ""));
                URI uri = URI.create(base + "/v3/conversations/" + encode(r.required("conversationId")) + "/activities");
                Map<String, Object> activity = new LinkedHashMap<>();
                activity.put("type", "message");
                activity.put("conversation", Map.of("id", r.required("conversationId")));
                if (r.str("botId") != null) {
                    activity.put("from", Map.of("id", r.str("botId")));
                }
                if (r.str("recipientId") != null) {
                    activity.put("recipient", Map.of("id", r.str("recipientId")));
                }
                activity.put("text", r.str("text", ""));
                if (r.input().get("card") != null) {
                    activity.put("attachments", List.of(Map.of("contentType", "application/vnd.microsoft.card.adaptive", "content", r.input().get("card"))));
                }
                return post(r, uri, Map.of("Authorization", "Bearer " + secrets.require(tokenRef)), activity,
                        b -> ConnectorResult.Success.of(Map.of("activityId", b.path("id").asText("")), b.path("id").asText(null)));
            }

            @Override
            public boolean healthy() {
                return true;
            }
        };
    }

    // ---- WhatsApp Cloud API --------------------------------------------------------------------

    /**
     * Public WhatsApp Cloud API: {@code POST {base}/{phoneNumberId}/messages}. A template message is sent when input
     * {@code template} is set, since a business-initiated message outside the service window needs an approved template.
     */
    public static SangamConnector whatsApp(String id, ConnectorProfile profile, URI apiBase, String phoneNumberId, String tokenRef, HttpTransport transport, SecretResolver secrets,
                                           Function<ConnectorRequest, Boolean> optedIn) {
        return new Base(id, "whatsapp", profile, transport, secrets, optedIn) {
            @Override
            ConnectorResult doSend(ConnectorRequest r) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("messaging_product", "whatsapp");
                body.put("recipient_type", r.str("recipientType", "individual"));
                body.put("to", r.required("to"));
                if (r.str("template") != null) {
                    body.put("type", "template");
                    body.put("template", Map.of("name", r.str("template"), "language", Map.of("code", r.str("language", "en")),
                            "components", r.input().getOrDefault("components", List.of())));
                } else {
                    body.put("type", "text");
                    body.put("text", Map.of("body", r.required("text")));
                }
                URI uri = URI.create(apiBase.toString().replaceAll("/+$", "") + "/" + encode(phoneNumberId) + "/messages");
                return post(r, uri, Map.of("Authorization", "Bearer " + secrets.require(tokenRef)), body, b -> {
                    String mid = b.path("messages").path(0).path("id").asText(null);
                    return ConnectorResult.Success.of(Map.of("messageId", String.valueOf(mid)), mid);
                });
            }
        };
    }

    // ---- WeChat Official Account ---------------------------------------------------------------

    /**
     * Public WeChat Official Account customer-service send: {@code POST {base}/cgi-bin/message/custom/send?access_token=}.
     * WeChat reports errors as HTTP 200 with {@code errcode}. The access token is put in the query because the API requires
     * it; {@code accessTokenRef} resolves to a token kept fresh by the secret binding.
     */
    public static SangamConnector weChat(String id, ConnectorProfile profile, URI apiBase, String accessTokenRef, HttpTransport transport, SecretResolver secrets,
                                         Function<ConnectorRequest, Boolean> optedIn) {
        return new Base(id, "wechat", profile, transport, secrets, optedIn) {
            @Override
            ConnectorResult doSend(ConnectorRequest r) {
                URI uri = URI.create(apiBase.toString().replaceAll("/+$", "") + "/cgi-bin/message/custom/send?access_token=" + encode(secrets.require(accessTokenRef)));
                Map<String, Object> body = Map.of("touser", r.required("to"), "msgtype", "text", "text", Map.of("content", r.required("text")));
                return post(r, uri, Map.of(), body, b -> {
                    int code = b.path("errcode").asInt(0);
                    if (code == -1 || code == 45009) {
                        return new ConnectorResult.TransientFailure("errcode " + code, true, null);
                    }
                    return code == 0 ? ConnectorResult.Success.of(Map.of("errcode", 0), null)
                            : ConnectorResult.BusinessFailure.of("WECHAT_" + code, b.path("errmsg").asText(""));
                });
            }
        };
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
