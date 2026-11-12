// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.sim;

import org.sangam.integration.connector.Json;
import org.sangam.integration.sim.SimHttpServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback simulator of the public messenger endpoints, for the simulator profile. It records what was sent and returns
 * each provider's documented answer shape. It does not model provider rules such as service windows.
 *
 * <ul>
 *   <li>Teams {@code /v3/conversations/...}, WhatsApp {@code /wa/...} (send), {@code /wa-media/{id}} and {@code /wa-file/{id}}
 *       (media), WeChat {@code /cgi-bin/message/custom/send}, {@code /cgi-bin/message/template/send},
 *       {@code /cgi-bin/stable_token} and {@code /cgi-bin/media/get}, Matrix {@code /_matrix/client/v3/rooms/...}, Slack
 *       {@code /slack/chat.postMessage}.</li>
 *   <li>WeChat tokens: {@code stable_token} issues {@code sim-token-<n>} and a new one only on {@code force_refresh}; a token
 *       passed to {@link #revoke} (or the literal {@code bad}) is answered with errcode 40001.</li>
 *   <li>Matrix: a repeated transaction id returns the first event id, as the specification requires.</li>
 * </ul>
 */
public final class MessengerSimulator extends SimHttpServer {

    public record Sent(String path, String authorization, String body) {
    }

    /** The bytes served for every simulated media id. */
    public static final byte[] MEDIA = "simulated media".getBytes(StandardCharsets.UTF_8);

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();
    private final AtomicInteger tokenSerial = new AtomicInteger(1);
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final Map<String, String> matrixTxn = new ConcurrentHashMap<>();

    public MessengerSimulator() {
        super("messenger");
        prefixRoute("POST", "/v3/conversations/", r -> {
            sent.add(new Sent(r.path(), r.header("Authorization"), r.bodyString()));
            return Response.json(201, "{\"id\":\"" + UUID.randomUUID() + "\"}");
        });
        prefixRoute("POST", "/wa/", r -> {
            sent.add(new Sent(r.path(), r.header("Authorization"), r.bodyString()));
            return Response.json(200, "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\"sim\",\"wa_id\":\"sim\"}],"
                    + "\"messages\":[{\"id\":\"wamid." + UUID.randomUUID() + "\",\"message_status\":\"accepted\"}]}");
        });
        prefixRoute("GET", "/wa-media/", r -> Response.json(200, Json.write(Map.of("url", baseUri() + "/wa-file/" + r.pathAfter("/wa-media/"),
                "mime_type", "image/png", "sha256", sha256(MEDIA), "file_size", MEDIA.length, "id", r.pathAfter("/wa-media/")))));
        prefixRoute("GET", "/wa-file/", r -> new Response(200, "image/png", MEDIA));
        route("POST", "/cgi-bin/message/custom/send", r -> {
            sent.add(new Sent(r.path(), null, r.bodyString()));
            return weChatTokenOk(r.queryParam("access_token")) ? Response.json(200, "{\"errcode\":0,\"errmsg\":\"ok\"}")
                    : Response.json(200, "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");
        });
        route("POST", "/cgi-bin/message/template/send", r -> {
            sent.add(new Sent(r.path(), null, r.bodyString()));
            return weChatTokenOk(r.queryParam("access_token")) ? Response.json(200, "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":200228332}")
                    : Response.json(200, "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");
        });
        route("POST", "/cgi-bin/stable_token", r -> {
            tokenRequests.incrementAndGet();
            Map<String, Object> in = Json.readMap(r.bodyString());
            if ("bad".equals(String.valueOf(in.get("secret")))) {
                return Response.json(200, "{\"errcode\":40125,\"errmsg\":\"invalid appsecret\"}");
            }
            if (Boolean.TRUE.equals(in.get("force_refresh"))) {
                tokenSerial.incrementAndGet();
            }
            return Response.json(200, "{\"access_token\":\"sim-token-" + tokenSerial.get() + "\",\"expires_in\":7200}");
        });
        route("GET", "/cgi-bin/media/get", r -> weChatTokenOk(r.queryParam("access_token")) ? new Response(200, "image/jpeg", MEDIA)
                : Response.json(200, "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}"));
        prefixRoute("PUT", "/_matrix/client/v3/rooms/", r -> {
            sent.add(new Sent(r.path(), r.header("Authorization"), r.bodyString()));
            String txn = r.path().substring(r.path().lastIndexOf('/') + 1);
            String event = matrixTxn.computeIfAbsent(txn, t -> "$" + UUID.randomUUID());
            return Response.json(200, "{\"event_id\":\"" + event + "\"}");
        });
        route("POST", "/slack/chat.postMessage", r -> {
            sent.add(new Sent(r.path(), r.header("Authorization"), r.bodyString()));
            Map<String, Object> in = Json.readMap(r.bodyString());
            return "C-unknown".equals(String.valueOf(in.get("channel"))) ? Response.json(200, "{\"ok\":false,\"error\":\"channel_not_found\"}")
                    : Response.json(200, "{\"ok\":true,\"channel\":\"" + in.get("channel") + "\",\"ts\":\"1700000000.000100\"}");
        });
    }

    private boolean weChatTokenOk(String token) {
        return token != null && !"bad".equals(token) && !revoked.contains(token);
    }

    /** Makes the simulator refuse {@code token} from now on (errcode 40001), as WeChat does after a newer token was issued. */
    public void revoke(String token) {
        revoked.add(token);
    }

    /** How many {@code stable_token} requests arrived. */
    public int tokenRequests() {
        return tokenRequests.get();
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
