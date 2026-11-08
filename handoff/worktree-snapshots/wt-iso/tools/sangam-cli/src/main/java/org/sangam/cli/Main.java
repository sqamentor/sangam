// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code sangam} command-line tool over the REST API only: no database access. Output is JSON on stdout, with diagnostics on stderr. Exit
 * codes are stable for CI: 0 ok, 1 server refused or test failures, 2 usage, 3 authentication, 4 network.
 *
 * <pre>
 *   sangam login --server URL --issuer URL --client ID            device flow (RFC 8628)
 *   sangam login --server URL --issuer URL --client ID --secret-env VAR   service principal (client credentials)
 *   sangam deploy FILE [--name N]                                 POST /deployments (multipart)
 *   sangam list tasks|process-instances|process-definitions|incidents [--query k=v]...
 *   sangam get PATH                                               any GET under /api/v1
 *   sangam start KEY [--business-key K] [--var k=v]...            POST /process-instances
 *   sangam test run PACKAGE                                       POST /packages/{id}/tests/run; exit 1 when a case fails
 *   sangam batch status ID                                        GET /ops/jobs/{id}
 * </pre>
 * Every mutation sends a fresh {@code Idempotency-Key}, or the value of {@code --idempotency-key} so a CI retry stays one operation.
 */
public final class Main {

    public static final int OK = 0;
    public static final int REFUSED = 1;
    public static final int USAGE = 2;
    public static final int AUTH = 3;
    public static final int NETWORK = 4;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Sends HTTP. Replaced in tests. */
    interface Http {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final Http http;
    private final Path configFile;
    private final PrintStream out;
    private final PrintStream err;
    private final java.util.function.Function<String, String> env;

    Main(Http http, Path configFile, PrintStream out, PrintStream err, java.util.function.Function<String, String> env) {
        this.http = http;
        this.configFile = configFile;
        this.out = out;
        this.err = err;
        this.env = env;
    }

    public static void main(String[] args) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        Path config = Path.of(System.getProperty("user.home"), ".sangam", "cli.json");
        System.exit(new Main(r -> client.send(r, HttpResponse.BodyHandlers.ofString()), config, System.out, System.err, System::getenv).run(args));
    }

    int run(String[] args) {
        try {
            if (args.length == 0) {
                return usage("no command");
            }
            Args a = Args.parse(args);
            return switch (a.positional.get(0)) {
                case "login" -> login(a);
                case "deploy" -> deploy(a);
                case "list" -> list(a);
                case "get" -> a.positional.size() == 2 ? call("GET", a.positional.get(1), null, a) : usage("get PATH");
                case "start" -> start(a);
                case "test" -> testRun(a);
                case "batch" -> a.positional.size() == 3 && a.positional.get(1).equals("status") ? call("GET", "/ops/jobs/" + enc(a.positional.get(2)), null, a) : usage("batch status ID");
                default -> usage("unknown command " + a.positional.get(0));
            };
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("interrupted");
            return NETWORK;
        } catch (IOException e) {
            err.println("network: " + e.getMessage());
            return NETWORK;
        }
    }

    private int usage(String why) {
        err.println("usage: " + why + "\ncommands: login, deploy, list, get, start, test run, batch status");
        return USAGE;
    }

    // ---- commands ------------------------------------------------------------------------------

    private int login(Args a) throws IOException, InterruptedException {
        String server = a.require("server");
        String issuer = a.require("issuer").replaceAll("/+$", "");
        String client = a.require("client");
        requireSecureUrl(server);
        requireSecureUrl(issuer);
        JsonNode conf = JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(issuer + "/.well-known/openid-configuration")).timeout(Duration.ofSeconds(30)).GET().build()).body());
        String tokenEndpoint = conf.path("token_endpoint").asText();
        requireSecureUrl(tokenEndpoint);
        JsonNode token;
        if (a.has("secret-env")) {
            String secret = env.apply(a.require("secret-env"));
            if (secret == null) {
                err.println("environment variable " + a.require("secret-env") + " is not set");
                return AUTH;
            }
            token = form(tokenEndpoint, Map.of("grant_type", "client_credentials", "client_id", client, "client_secret", secret));
        } else {
            JsonNode device = form(conf.path("device_authorization_endpoint").asText(), Map.of("client_id", client, "scope", "openid"));
            err.println("Open " + device.path("verification_uri").asText() + " and enter " + device.path("user_code").asText());
            long interval = device.path("interval").asLong(5);
            long deadline = System.currentTimeMillis() + device.path("expires_in").asLong(600) * 1000;
            token = null;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(interval * 1000);
                JsonNode t = form(tokenEndpoint, Map.of("grant_type", "urn:ietf:params:oauth:grant-type:device_code", "device_code", device.path("device_code").asText(), "client_id", client));
                String error = t.path("error").asText("");
                if (error.isEmpty()) {
                    token = t;
                    break;
                }
                if (error.equals("slow_down")) {
                    interval += 5;
                } else if (!error.equals("authorization_pending")) {
                    err.println("login failed: " + error);
                    return AUTH;
                }
            }
            if (token == null) {
                err.println("login timed out");
                return AUTH;
            }
        }
        if (!token.hasNonNull("access_token")) {
            err.println("login failed: " + token.path("error").asText("no access token"));
            return AUTH;
        }
        save(Map.of("server", server.replaceAll("/+$", ""), "accessToken", token.get("access_token").asText()));
        out.println("{\"loggedIn\":true}");
        return OK;
    }

    private JsonNode form(String url, Map<String, String> fields) throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder();
        fields.forEach((k, v) -> b.append(b.isEmpty() ? "" : "&").append(enc(k)).append('=').append(enc(v)));
        return JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())).build()).body());
    }

    private int deploy(Args a) throws IOException, InterruptedException {
        if (a.positional.size() != 2) {
            return usage("deploy FILE");
        }
        Path file = Path.of(a.positional.get(1));
        String boundary = "sangam" + UUID.randomUUID().toString().replace("-", "");
        String name = a.opt("name", file.getFileName().toString());
        String safeFile = file.getFileName().toString().replace("\"", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"deploymentName\"\r\n\r\n" + name + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"data\"; filename=\"" + safeFile + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] content = Files.readAllBytes(file);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        return send(request("/deployments", a).header("Content-Type", "multipart/form-data; boundary=" + boundary).header("Idempotency-Key", a.opt("idempotency-key", UUID.randomUUID().toString()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), false);
    }

    private int list(Args a) throws IOException, InterruptedException {
        if (a.positional.size() != 2) {
            return usage("list TYPE");
        }
        String path = switch (a.positional.get(1)) {
            case "tasks" -> "/tasks";
            case "process-instances" -> "/process-instances";
            case "process-definitions" -> "/process-definitions";
            case "incidents" -> "/ops/incidents";
            default -> throw new IllegalArgumentException("cannot list " + a.positional.get(1));
        };
        List<String> q = new ArrayList<>();
        for (String kv : a.multi("query")) {
            String[] p = kv.split("=", 2);
            if (p.length != 2) {
                throw new IllegalArgumentException("--query needs k=v");
            }
            q.add(enc(p[0]) + "=" + enc(p[1]));
        }
        return call("GET", path + (q.isEmpty() ? "" : "?" + String.join("&", q)), null, a);
    }

    private int start(Args a) throws IOException, InterruptedException {
        if (a.positional.size() != 2) {
            return usage("start KEY");
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        for (String kv : a.multi("var")) {
            String[] p = kv.split("=", 2);
            if (p.length != 2) {
                throw new IllegalArgumentException("--var needs k=v");
            }
            vars.put(p[0], p[1]);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", a.positional.get(1));
        if (a.has("business-key")) {
            body.put("businessKey", a.require("business-key"));
        }
        body.put("variables", vars);
        return call("POST", "/process-instances", JSON.writeValueAsString(body), a);
    }

    private int testRun(Args a) throws IOException, InterruptedException {
        if (a.positional.size() != 3 || !a.positional.get(1).equals("run")) {
            return usage("test run PACKAGE");
        }
        HttpRequest.Builder b = request("/packages/" + enc(a.positional.get(2)) + "/tests/run", a).header("Content-Type", "application/json")
                .header("Idempotency-Key", a.opt("idempotency-key", UUID.randomUUID().toString())).POST(HttpRequest.BodyPublishers.ofString("{}"));
        return send(b.build(), true);
    }

    private int call(String method, String path, String body, Args a) throws IOException, InterruptedException {
        HttpRequest.Builder b = request(path, a);
        if (method.equals("GET")) {
            b.GET();
        } else {
            b.header("Content-Type", "application/json").header("Idempotency-Key", a.opt("idempotency-key", UUID.randomUUID().toString()))
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return send(b.build(), false);
    }

    // ---- transport -----------------------------------------------------------------------------

    private HttpRequest.Builder request(String path, Args a) throws IOException {
        JsonNode c = loadConfig();
        String server = a.opt("server", c.path("server").asText(""));
        String token = env.apply("SANGAM_TOKEN") != null ? env.apply("SANGAM_TOKEN") : c.path("accessToken").asText("");
        if (server.isEmpty()) {
            throw new IllegalArgumentException("no server: run login or pass --server");
        }
        if (!path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("path must be absolute under /api/v1");
        }
        return HttpRequest.newBuilder(URI.create(server + "/api/v1" + path)).timeout(Duration.ofMinutes(2)).header("Accept", "application/json").header("Authorization", "Bearer " + token);
    }

    private int send(HttpRequest r, boolean failOnTestFailures) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(r);
        int s = resp.statusCode();
        if (s == 401 || s == 403) {
            err.println(resp.body());
            return AUTH;
        }
        if (s >= 400) {
            err.println(resp.body());
            return REFUSED;
        }
        out.println(resp.body());
        if (failOnTestFailures) {
            JsonNode n = JSON.readTree(resp.body());
            if (n.isArray()) {
                // POST /packages/{id}/tests/run answers with an array of TestRun.
                for (JsonNode run : n) {
                    if (!run.path("passed").asBoolean(false)) {
                        return REFUSED;
                    }
                }
                return OK;
            }
            return n.path("failed").asInt(0) > 0 ? REFUSED : OK;
        }
        return OK;
    }

    private JsonNode loadConfig() throws IOException {
        return Files.exists(configFile) ? JSON.readTree(configFile.toFile()) : JSON.createObjectNode();
    }

    /** Stores the token with owner-only permissions where the file system supports them. */
    private void save(Map<String, String> config) throws IOException {
        Files.createDirectories(configFile.getParent());
        if (!Files.exists(configFile)) {
            Files.createFile(configFile);
        }
        try {
            Files.setPosixFilePermissions(configFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            err.println("note: the token file relies on the user profile's permissions on this file system");
        }
        Files.writeString(configFile, JSON.writeValueAsString(config), StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void requireSecureUrl(String url) {
        URI u = URI.create(url);
        boolean loopback = "localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost());
        if (!"https".equals(u.getScheme()) && !loopback) {
            throw new IllegalArgumentException("https is required except on loopback: " + url);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- arguments -----------------------------------------------------------------------------

    static final class Args {
        final List<String> positional = new ArrayList<>();
        final Map<String, List<String>> options = new LinkedHashMap<>();

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                if (argv[i].startsWith("--")) {
                    if (i + 1 >= argv.length) {
                        throw new IllegalArgumentException(argv[i] + " needs a value");
                    }
                    a.options.computeIfAbsent(argv[i].substring(2), k -> new ArrayList<>()).add(argv[++i]);
                } else {
                    a.positional.add(argv[i]);
                }
            }
            if (a.positional.isEmpty()) {
                throw new IllegalArgumentException("no command");
            }
            return a;
        }

        boolean has(String k) {
            return options.containsKey(k);
        }

        String opt(String k, String fallback) {
            return has(k) ? options.get(k).get(0) : fallback;
        }

        String require(String k) {
            if (!has(k)) {
                throw new IllegalArgumentException("--" + k + " is required");
            }
            return options.get(k).get(0);
        }

        List<String> multi(String k) {
            return options.getOrDefault(k, List.of());
        }
    }
}
