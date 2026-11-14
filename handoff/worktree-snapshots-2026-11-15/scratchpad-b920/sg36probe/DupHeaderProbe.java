import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SG-36 side probe (not product code): what the JDK HttpClient sends when a case-sensitive header map holds both
 * "authorization" and "Authorization", as HttpRestConnector.headers() can produce, and HttpTransport adds each entry
 * with HttpRequest.Builder.header(name, value).
 */
public class DupHeaderProbe {
    public static void main(String[] args) throws Exception {
        AtomicReference<List<String>> got = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            got.set(ex.getRequestHeaders().get("Authorization"));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("authorization", "from-default-or-request-header");   // e.g. default-headers.authorization or input headers
            h.put("Authorization", "Bearer from-auth");                  // the connector's own auth header
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            h.forEach(rb::header);
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                    .send(rb.GET().build(), HttpResponse.BodyHandlers.discarding());
            System.out.println("Authorization values received: " + got.get());
        } finally {
            server.stop(0);
        }
    }
}
