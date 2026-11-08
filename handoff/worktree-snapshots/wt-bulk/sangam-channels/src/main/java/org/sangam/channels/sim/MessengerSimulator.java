// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.sim;

import org.sangam.integration.sim.SimHttpServer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loopback simulator of the three public messenger send endpoints, for the simulator profile. It records what was sent and
 * returns each provider's documented answer shape. It does not model provider rules such as service windows.
 */
public final class MessengerSimulator extends SimHttpServer {

    public record Sent(String path, String authorization, String body) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

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
        route("POST", "/cgi-bin/message/custom/send", r -> {
            sent.add(new Sent(r.path(), null, r.bodyString()));
            return "bad".equals(r.queryParam("access_token")) ? Response.json(200, "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}")
                    : Response.json(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        });
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }
}
