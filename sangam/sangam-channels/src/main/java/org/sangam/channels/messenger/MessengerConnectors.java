// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.channels.messenger.ProviderTokens.TokenSource;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Outbound messenger connectors over the providers' public HTTP APIs. All are capability class (c): these send APIs offer
 * no outcome lookup by our operationId, so a lost answer becomes UNKNOWN_OUTCOME, never a blind resend that a person would
 * see twice. Each tenant supplies its own provider account through secret references. Sending to a recipient without a
 * recorded opt-in fails before any call. A token that the provider refuses before processing (HTTP 401, or WeChat's token
 * error codes) is refreshed and the send is repeated once, when the {@link TokenSource} can refresh it (NG-14).
 */
public final class MessengerConnectors {

    /** WeChat codes for an access token that is invalid or expired: the request was refused before processing. */
    static final Set<Integer> WECHAT_TOKEN_ERRORS = Set.of(40001, 40014, 42001);

    private MessengerConnectors() {
    }

    /** {@code optedIn.apply(request)} answers whether the recipient has an active opt-in. */
    private abstract static class Base implements SangamConnector {
        final ConnectorDescriptor descriptor;
        final HttpTransport transport;
        final Function<ConnectorRequest, Boolean> optedIn;

        Base(String id, String type, ConnectorProfile profile, HttpTransport transport, Function<ConnectorRequest, Boolean> optedIn) {
            this.descriptor = new ConnectorDescriptor(id, type, CapabilityClass.C_NEITHER, List.of("send"), profile);
            this.transport = transport;
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
            } catch (ProviderTokens.TokenUnavailableException e) {
                return ConnectorResult.TransientFailure.noEffect("provider token unavailable, request not sent: " + e.getMessage());
            }
        }

        abstract ConnectorResult doSend(ConnectorRequest r);

        HttpTransport.Outcome exchange(ConnectorRequest r, String method, URI uri, Map<String, String> headers, Object body, boolean idempotent) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put("Content-Type", "application/json");
            long deadline = r.deadline() == null ? 0 : r.deadline().toEpochMilli();
            return transport.execute(r.tenantId(), new HttpTransport.Call(method, uri, h, Json.bytes(body), null, idempotent), deadline);
        }

        ConnectorResult post(ConnectorRequest r, URI uri, Map<String, String> headers, Object body, Function<JsonNode, ConnectorResult> onBody) {
            return result(exchange(r, "POST", uri, headers, body, false), onBody);
        }
    }

    /** The common mapping of a send outcome. */
    static ConnectorResult result(HttpTransport.Outcome o, Function<JsonNode, ConnectorResult> onBody) {
        return switch (o) {
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

    private static String abbreviate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    private static boolean status(HttpTransport.Outcome o, int status) {
        return o instanceof HttpTransport.Outcome.Response r && r.status() == status;
    }

    // ---- Teams (Bot Framework) -----------------------------------------------------------------

    /** The bearer token comes from the secret {@code tokenRef}, kept fresh by the secret binding. */
    public static SangamConnector teams(String id, ConnectorProfile profile, HttpTransport transport, SecretResolver secrets, String tokenRef, Function<ConnectorRequest, Boolean> optedIn) {
        return teams(id, profile, transport, TokenSource.ofSecret(secrets, tokenRef), optedIn);
    }

    /**
     * Sends an activity to a conversation with the public Bot Framework REST API. Input: {@code serviceUrl} (from the stored
     * conversation reference, see {@link InboundMessageParsers#teamsReference}), {@code conversationId}, {@code text}, optional
     * {@code botId}, {@code recipientId}, {@code card} (Adaptive Card JSON) and {@code deepLink}. The token comes from
     * {@code tokens}: {@link ProviderTokens#oauth2} with {@link ProviderTokens#BOT_FRAMEWORK_SCOPE} fetches and refreshes it.
     * Only a {@code serviceUrl} from a verified activity may be used, because the token is sent there. Sensitive completions
     * stay in Sangam: a card only carries a deep link.
     */
    public static SangamConnector teams(String id, ConnectorProfile profile, HttpTransport transport, TokenSource tokens, Function<ConnectorRequest, Boolean> optedIn) {
        return new Base(id, "teams", profile, transport, optedIn) {
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
                HttpTransport.Outcome o = exchange(r, "POST", uri, Map.of("Authorization", "Bearer " + tokens.token(r.tenantId())), activity, false);
                if (tokens.refreshable() && status(o, 401)) {
                    // 401: refused before processing, so one repeat with a fresh token cannot send twice
                    tokens.invalidate(r.tenantId());
                    o = exchange(r, "POST", uri, Map.of("Authorization", "Bearer " + tokens.token(r.tenantId())), activity, false);
                }
                return result(o, b -> ConnectorResult.Success.of(Map.of("activityId", b.path("id").asText("")), b.path("id").asText(null)));
            }

            @Override
            public boolean healthy() {
                return true;
            }
        };
    }

    // ---- WhatsApp Cloud API --------------------------------------------------------------------

    public static SangamConnector whatsApp(String id, ConnectorProfile profile, URI apiBase, String phoneNumberId, String tokenRef, HttpTransport transport, SecretResolver secrets,
                                           Function<ConnectorRequest, Boolean> optedIn) {
        return whatsApp(id, profile, apiBase, phoneNumberId, TokenSource.ofSecret(secrets, tokenRef), TemplateRules.providerOnly(), transport, optedIn);
    }

    /**
     * Public WhatsApp Cloud API: {@code POST {base}/{phoneNumberId}/messages}. A template message is sent when input
     * {@code template} is set, since a business-initiated message outside the service window needs an approved template;
     * {@code rules} refuses templates the channel entry does not list as approved.
     */
    public static SangamConnector whatsApp(String id, ConnectorProfile profile, URI apiBase, String phoneNumberId, TokenSource tokens, TemplateRules rules,
                                           HttpTransport transport, Function<ConnectorRequest, Boolean> optedIn) {
        return new Base(id, "whatsapp", profile, transport, optedIn) {
            @Override
            ConnectorResult doSend(ConnectorRequest r) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("messaging_product", "whatsapp");
                body.put("recipient_type", r.str("recipientType", "individual"));
                body.put("to", r.required("to"));
                if (r.str("template") != null) {
                    Object components = r.input().getOrDefault("components", List.of());
                    Optional<String[]> no = rules.check(r.str("template"), r.str("language", "en"), TemplateRules.whatsAppBodyParams(components));
                    if (no.isPresent()) {
                        return ConnectorResult.BusinessFailure.of(no.get()[0], no.get()[1]);
                    }
                    body.put("type", "template");
                    body.put("template", Map.of("name", r.str("template"), "language", Map.of("code", r.str("language", "en")), "components", components));
                } else {
                    body.put("type", "text");
                    body.put("text", Map.of("body", r.required("text")));
                }
                URI uri = URI.create(apiBase.toString().replaceAll("/+$", "") + "/" + encode(phoneNumberId) + "/messages");
                HttpTransport.Outcome o = exchange(r, "POST", uri, Map.of("Authorization", "Bearer " + tokens.token(r.tenantId())), body, false);
                if (tokens.refreshable() && status(o, 401)) {
                    tokens.invalidate(r.tenantId());
                    o = exchange(r, "POST", uri, Map.of("Authorization", "Bearer " + tokens.token(r.tenantId())), body, false);
                }
                return result(o, b -> {
                    String mid = b.path("messages").path(0).path("id").asText(null);
                    return ConnectorResult.Success.of(Map.of("messageId", String.valueOf(mid)), mid);
                });
            }
        };
    }

    // ---- WeChat Official Account ---------------------------------------------------------------

    public static SangamConnector weChat(String id, ConnectorProfile profile, URI apiBase, String accessTokenRef, HttpTransport transport, SecretResolver secrets,
                                         Function<ConnectorRequest, Boolean> optedIn) {
        return weChat(id, profile, apiBase, TokenSource.ofSecret(secrets, accessTokenRef), TemplateRules.providerOnly(), transport, optedIn);
    }

    /**
     * Public WeChat Official Account sends. Without input {@code template}: the customer-service message
     * {@code POST {base}/cgi-bin/message/custom/send} (text). With {@code template} (a template id) and {@code data}: the
     * template message {@code POST {base}/cgi-bin/message/template/send}, optional {@code url}; {@code rules} refuses
     * templates the channel entry does not list. WeChat reports errors as HTTP 200 with {@code errcode}; the access token is
     * in the query because the API requires it. A token error code (40001, 40014, 42001) refreshes the token and repeats once.
     */
    public static SangamConnector weChat(String id, ConnectorProfile profile, URI apiBase, TokenSource tokens, TemplateRules rules, HttpTransport transport,
                                         Function<ConnectorRequest, Boolean> optedIn) {
        String base = apiBase.toString().replaceAll("/+$", "");
        return new Base(id, "wechat", profile, transport, optedIn) {
            @Override
            ConnectorResult doSend(ConnectorRequest r) {
                String path;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("touser", r.required("to"));
                if (r.str("template") != null) {
                    Object data = r.input().getOrDefault("data", Map.of());
                    Optional<String[]> no = rules.check(r.str("template"), r.str("language"), TemplateRules.weChatDataFields(data));
                    if (no.isPresent()) {
                        return ConnectorResult.BusinessFailure.of(no.get()[0], no.get()[1]);
                    }
                    path = "/cgi-bin/message/template/send";
                    body.put("template_id", r.str("template"));
                    if (r.str("url") != null) {
                        body.put("url", r.str("url"));
                    }
                    body.put("data", data);
                } else {
                    path = "/cgi-bin/message/custom/send";
                    body.put("msgtype", "text");
                    body.put("text", Map.of("content", r.required("text")));
                }
                HttpTransport.Outcome o = exchange(r, "POST", URI.create(base + path + "?access_token=" + encode(tokens.token(r.tenantId()))), Map.of(), body, false);
                if (tokens.refreshable() && tokenRefused(o)) {
                    tokens.invalidate(r.tenantId());
                    o = exchange(r, "POST", URI.create(base + path + "?access_token=" + encode(tokens.token(r.tenantId()))), Map.of(), body, false);
                }
                return result(o, b -> {
                    int code = b.path("errcode").asInt(0);
                    if (code == -1 || code == 45009) {
                        return new ConnectorResult.TransientFailure("errcode " + code, true, null);
                    }
                    String msgId = b.hasNonNull("msgid") ? b.path("msgid").asText() : null;
                    return code == 0 ? ConnectorResult.Success.of(msgId == null ? Map.of("errcode", 0) : Map.of("errcode", 0, "msgid", msgId), msgId)
                            : ConnectorResult.BusinessFailure.of("WECHAT_" + code, b.path("errmsg").asText(""));
                });
            }
        };
    }

    private static boolean tokenRefused(HttpTransport.Outcome o) {
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            return false;
        }
        JsonNode t = Json.tree(r.body());
        return t != null && WECHAT_TOKEN_ERRORS.contains(t.path("errcode").asInt(0));
    }

    // ---- Matrix (client-server API) ------------------------------------------------------------

    /**
     * Public Matrix client-server API: {@code PUT {homeserver}/_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}}
     * with the bridge user's access token. Input {@code to} is the room id (the opt-in key), {@code text}, and optional
     * {@code html} (sent as {@code org.matrix.custom.html}). The transaction id is the operationId, so the homeserver answers a
     * repeat of the same operation with the first event instead of posting twice; there is still no lookup, so a lost answer
     * stays UNKNOWN_OUTCOME (class (c)).
     */
    public static SangamConnector matrix(String id, ConnectorProfile profile, URI homeserver, TokenSource tokens, HttpTransport transport,
                                         Function<ConnectorRequest, Boolean> optedIn) {
        String base = homeserver.toString().replaceAll("/+$", "");
        return new Base(id, "matrix", profile, transport, optedIn) {
            @Override
            ConnectorResult doSend(ConnectorRequest r) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("msgtype", "m.text");
                content.put("body", r.required("text"));
                if (r.str("html") != null) {
                    content.put("format", "org.matrix.custom.html");
                    content.put("formatted_body", r.str("html"));
                }
                URI uri = URI.create(base + "/_matrix/client/v3/rooms/" + encode(r.required("to")) + "/send/m.room.message/" + encode(r.operationId().toString()));
                HttpTransport.Outcome o = exchange(r, "PUT", uri, Map.of("Authorization", "Bearer " + tokens.token(r.tenantId())), content, true);
                if (o instanceof HttpTransport.Outcome.Response resp && resp.status() >= 400 && resp.status() < 500) {
                    JsonNode e = Json.tree(resp.body());
                    String code = e == null ? "HTTP_" + resp.status() : e.path("errcode").asText("HTTP_" + resp.status());
                    if (resp.status() == 429) {
                        long ms = e == null ? 0 : e.path("retry_after_ms").asLong(0);
                        return new ConnectorResult.TransientFailure(code, true, ms > 0 ? Duration.ofMillis(ms) : resp.retryAfter().orElse(null));
                    }
                    return ConnectorResult.BusinessFailure.of("MATRIX_" + code, e == null ? "" : e.path("error").asText(""));
                }
                return result(o, b -> {
                    String eventId = b.path("event_id").asText(null);
                    return ConnectorResult.Success.of(Map.of("eventId", String.valueOf(eventId)), eventId);
                });
            }
        };
    }

    // ---- Slack (Web API) -----------------------------------------------------------------------

    /** Slack's public Web API base. */
    public static final URI SLACK_API = URI.create("https://slack.com/api");

    /**
     * Public Slack Web API {@code chat.postMessage} with the bot token. Input {@code to} is the channel or user id (the opt-in
     * key), {@code text}, and optional {@code blocks} and {@code threadTs}. Slack answers HTTP 200 with {@code ok:false} and an
     * {@code error} code for refusals; {@code ratelimited} and HTTP 429 are retryable with {@code Retry-After}.
     */
    public static SangamConnector slack(String id, ConnectorProfile profile, URI apiBase, TokenSource tokens, HttpTransport transport,
                                        Function<ConnectorRequest, Boolean> optedIn) {
        String base = (apiBase == null ? SLACK_API : apiBase).toString().replaceAll("/+$", "");
        return new Base(id, "slack", profile, transport, optedIn) {
            @Override
            ConnectorResult doSend(ConnectorRequest r) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("channel", r.required("to"));
                body.put("text", r.required("text"));
                if (r.input().get("blocks") != null) {
                    body.put("blocks", r.input().get("blocks"));
                }
                if (r.str("threadTs") != null) {
                    body.put("thread_ts", r.str("threadTs"));
                }
                Map<String, String> h = Map.of("Authorization", "Bearer " + tokens.token(r.tenantId()));
                return result(exchange(r, "POST", URI.create(base + "/chat.postMessage"), h, body, false), b -> {
                    if (b.path("ok").asBoolean(false)) {
                        String ts = b.path("ts").asText(null);
                        return ConnectorResult.Success.of(Map.of("ts", String.valueOf(ts), "channel", b.path("channel").asText("")), ts);
                    }
                    String error = b.path("error").asText("unknown_error");
                    return "ratelimited".equals(error) ? new ConnectorResult.TransientFailure("ratelimited", true, null)
                            : ConnectorResult.BusinessFailure.of("SLACK_" + error, error);
                });
            }
        };
    }

    static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
