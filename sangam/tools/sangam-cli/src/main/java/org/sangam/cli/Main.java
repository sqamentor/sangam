// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.time.Clock;
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
 *   sangam logout                                                 forget the saved tokens and the keychain key
 *   sangam deploy FILE [--name N]                                 POST /deployments (multipart)
 *   sangam list tasks|process-instances|process-definitions|incidents [--query k=v]...
 *   sangam get PATH                                               any GET under /api/v1
 *   sangam start KEY [--business-key K] [--var k=v]...            POST /process-instances
 *   sangam test run PACKAGE                                       POST /packages/{id}/tests/run; exit 1 when a case fails
 *   sangam batch status ID                                        GET /ops/jobs/{id}
 *   sangam upgrade check [--models DIR]... [--config FILE]...      offline pre-upgrade scan ({@link UpgradeCheck}, M-092)
 *   sangam upgrade check ... --schema DATABASE [--server URL]       plus the server's applied changesets ({@link SchemaCheck})
 *   sangam diff BEFORE AFTER [--json] [--fail-on LEVEL,...]        offline semantic model diff ({@link ModelDiffCommand}, M-125)
 * </pre>
 * Every mutation sends a fresh {@code Idempotency-Key}, or the value of {@code --idempotency-key} so a CI retry stays one operation.
 *
 * <p>Tokens (NG-23, M-093): {@code ~/.sangam/cli.json} holds only non-secret settings (server, token endpoint, client
 * id, account id, the NAME of the secret variable). The tokens go to {@link TokenStore}: encrypted under a key in the OS
 * keychain, or an owner-only file where there is no keychain ({@code SANGAM_TOKEN_STORE=auto|keychain|file}). The access
 * token is renewed shortly before it expires and once after a 401 ({@link TokenSession}). {@code SANGAM_TOKEN}
 * overrides everything and is never stored or renewed. No command prints a token.
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
    private final TokenStore store;
    private final TokenSession session;
    private final Clock clock;

    /** Tokens in an owner-only file next to the config file (no keychain); the system clock. */
    Main(Http http, Path configFile, PrintStream out, PrintStream err, java.util.function.Function<String, String> env) {
        this(http, configFile, out, err, env, null, Clock.systemUTC());
    }

    /**
     * @param protector the OS keychain front end, or {@code null} for none; the mode comes from {@code SANGAM_TOKEN_STORE}
     */
    Main(Http http, Path configFile, PrintStream out, PrintStream err, java.util.function.Function<String, String> env,
         KeyProtector protector, Clock clock) {
        this.http = http;
        this.configFile = configFile;
        this.out = out;
        this.err = err;
        this.env = env;
        this.clock = clock;
        this.store = new TokenStore(configFile.toAbsolutePath().getParent().resolve("credentials"), protector,
                TokenStore.Mode.parse(env.apply("SANGAM_TOKEN_STORE")), err);
        this.session = new TokenSession(http, store, clock, env, err);
    }

    public static void main(String[] args) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        Path config = Path.of(System.getProperty("user.home"), ".sangam", "cli.json");
        KeyProtector keychain = KeyProtector.forOs(System.getProperty("os.name"), ProcessRunner.system());
        System.exit(new Main(r -> client.send(r, HttpResponse.BodyHandlers.ofString()), config, System.out, System.err, System::getenv,
                keychain, Clock.systemUTC()).run(args));
    }

    int run(String[] args) {
        try {
            if (args.length == 0) {
                return usage("no command");
            }
            if (args[0].equals("diff")) { // offline, own flags (--json takes no value)
                return new ModelDiffCommand(out, err).run(List.of(args).subList(1, args.length));
            }
            Args a = Args.parse(args);
            return switch (a.positional.get(0)) {
                case "login" -> login(a);
                case "logout" -> logout();
                case "deploy" -> deploy(a);
                case "list" -> list(a);
                case "get" -> a.positional.size() == 2 ? call("GET", a.positional.get(1), null, a) : usage("get PATH");
                case "start" -> start(a);
                case "test" -> testRun(a);
                case "batch" -> a.positional.size() == 3 && a.positional.get(1).equals("status") ? call("GET", "/ops/jobs/" + enc(a.positional.get(2)), null, a) : usage("batch status ID");
                case "upgrade" -> a.positional.size() == 2 && a.positional.get(1).equals("check")
                        ? upgradeCheck(a)
                        : usage("upgrade check [--models DIR]... [--config FILE]... [--rules FILE] [--deprecations FILE] [--as-of yyyy-MM-dd]"
                                + " [--schema postgresql|oracle|mssql|mysql|h2 [--server URL]]");
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
        err.println("usage: " + why + "\ncommands: login, logout, deploy, list, get, start, test run, batch status, upgrade check, diff");
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
            String deviceEndpoint = conf.path("device_authorization_endpoint").asText();
            requireSecureUrl(deviceEndpoint);
            JsonNode device = form(deviceEndpoint, Map.of("client_id", client, "scope", "openid"));
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
        String serverBase = server.replaceAll("/+$", "");
        String account = TokenStore.account(serverBase, client);
        ObjectNode config = JSON.createObjectNode().put("server", serverBase).put("issuer", issuer).put("tokenEndpoint", tokenEndpoint)
                .put("clientId", client).put("account", account);
        if (a.has("secret-env")) {
            config.put("secretEnv", a.require("secret-env")); // the variable's name, never its value
        }
        String where;
        try {
            where = store.save(account, session.credentials(token, null));
        } catch (IOException e) {
            err.println("login failed: " + e.getMessage());
            return AUTH;
        }
        writeConfig(config);
        out.println(JSON.createObjectNode().put("loggedIn", true).put("tokenStore", where));
        return OK;
    }

    private int logout() throws IOException, InterruptedException {
        JsonNode c = loadConfig();
        String account = c.path("account").asText("");
        boolean had = !account.isEmpty() && store.clear(account);
        if (c.size() > 0) {
            ObjectNode kept = JSON.createObjectNode();
            if (c.hasNonNull("server")) {
                kept.put("server", c.get("server").asText());
            }
            writeConfig(kept);
        }
        out.println(JSON.createObjectNode().put("loggedOut", true).put("hadTokens", had));
        return OK;
    }

    private JsonNode form(String url, Map<String, String> fields) throws IOException, InterruptedException {
        return TokenSession.form(http, url, fields);
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

    /**
     * M-092: offline checks, plus with {@code --schema DATABASE} the installation's applied changesets read from
     * {@code GET /ops/schema-status} (platform operators, the session's token) and compared with this release's changelog.
     */
    private int upgradeCheck(Args a) throws IOException, InterruptedException {
        String database = null;
        JsonNode status = null;
        if (a.has("schema")) {
            database = SchemaCheck.database(a.opt("schema", null));
            HttpResponse<String> resp = authorized(request("/ops/schema-status", a).GET().build());
            int s = resp.statusCode();
            if (s == 401 || s == 403) {
                err.println(resp.body());
                return AUTH;
            }
            if (s >= 400) {
                err.println(resp.body());
                return REFUSED;
            }
            status = JSON.readTree(resp.body());
        }
        return new UpgradeCheck(out, err, clock).run(a.multi("models"), a.multi("config"), a.opt("rules", null),
                a.opt("deprecations", null), a.opt("as-of", null), database, status);
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

    private HttpRequest.Builder request(String path, Args a) throws IOException, InterruptedException {
        JsonNode c = loadConfig();
        String server = a.opt("server", c.path("server").asText(""));
        if (server.isEmpty()) {
            throw new IllegalArgumentException("no server: run login or pass --server");
        }
        if (!path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("path must be absolute under /api/v1");
        }
        return HttpRequest.newBuilder(URI.create(server + "/api/v1" + path)).timeout(Duration.ofMinutes(2)).header("Accept", "application/json");
    }

    /**
     * Sends with the session's bearer token. After a 401 the session is renewed once and the same request (same
     * {@code Idempotency-Key}) is sent again: a 401 means the server did not act on it.
     */
    private HttpResponse<String> authorized(HttpRequest r) throws IOException, InterruptedException {
        String fixed = env.apply("SANGAM_TOKEN");
        if (fixed != null) {
            return http.send(withBearer(r, fixed));
        }
        JsonNode c = loadConfig();
        String token = session.bearer(c);
        if (token == null) {
            err.println("note: not logged in; run sangam login or set SANGAM_TOKEN");
            return http.send(r);
        }
        HttpResponse<String> resp = http.send(withBearer(r, token));
        if (resp.statusCode() == 401) {
            String renewed = session.renewAfterRejection(c);
            if (renewed != null) {
                return http.send(withBearer(r, renewed));
            }
        }
        return resp;
    }

    private static HttpRequest withBearer(HttpRequest r, String token) {
        return HttpRequest.newBuilder(r, (name, value) -> !name.equalsIgnoreCase("Authorization")).header("Authorization", "Bearer " + token).build();
    }

    private int send(HttpRequest r, boolean failOnTestFailures) throws IOException, InterruptedException {
        HttpResponse<String> resp = authorized(r);
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

    /**
     * The settings. A {@code cli.json} written before M-093 held the access token itself: it is moved into the token
     * store once (no refresh is possible for it) and the file is rewritten without it.
     */
    private JsonNode loadConfig() throws IOException, InterruptedException {
        if (!Files.exists(configFile)) {
            return JSON.createObjectNode();
        }
        JsonNode c = JSON.readTree(configFile.toFile());
        if (c == null || !c.isObject()) {
            return JSON.createObjectNode();
        }
        if (c.hasNonNull("accessToken")) {
            ObjectNode moved = ((ObjectNode) c).deepCopy();
            String legacy = moved.remove("accessToken").asText();
            String account = TokenStore.account(moved.path("server").asText(""), "");
            store.save(account, new TokenStore.Credentials(legacy, null, 0));
            moved.put("account", account);
            writeConfig(moved);
            return moved;
        }
        return c;
    }

    /** The settings file holds no secret, but is still written owner-only. */
    private void writeConfig(JsonNode config) throws IOException {
        if (OwnerOnlyFiles.write(configFile, JSON.writeValueAsString(config)) == OwnerOnlyFiles.Protection.PROFILE_ONLY) {
            err.println("note: this file system has no owner-only permissions; cli.json relies on the user profile's permissions");
        }
    }

    static void requireSecureUrl(String url) {
        URI u = URI.create(url);
        boolean loopback = "localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost()) || "[::1]".equals(u.getHost());
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
