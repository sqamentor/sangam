import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()


def rep(old, new):
    global s
    assert s.count(old) == 1, (old[:80], s.count(old))
    s = s.replace(old, new)


rep('''import com.fasterxml.jackson.databind.ObjectMapper;
''', '''import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
''')
rep('''import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
''', '''import java.time.Clock;
import java.time.Duration;
''')
rep(''' *   sangam login --server URL --issuer URL --client ID --secret-env VAR   service principal (client credentials)
''', ''' *   sangam login --server URL --issuer URL --client ID --secret-env VAR   service principal (client credentials)
 *   sangam logout                                                 forget the saved tokens and the keychain key
''')
rep(''' * Every mutation sends a fresh {@code Idempotency-Key}, or the value of {@code --idempotency-key} so a CI retry stays one operation.
 */''', ''' * Every mutation sends a fresh {@code Idempotency-Key}, or the value of {@code --idempotency-key} so a CI retry stays one operation.
 *
 * <p>Tokens (NG-23, M-093): {@code ~/.sangam/cli.json} holds only non-secret settings (server, token endpoint, client
 * id, account id, the NAME of the secret variable). The tokens go to {@link TokenStore}: encrypted under a key in the OS
 * keychain, or an owner-only file where there is no keychain ({@code SANGAM_TOKEN_STORE=auto|keychain|file}). The access
 * token is renewed shortly before it expires and once after a 401 ({@link TokenSession}). {@code SANGAM_TOKEN}
 * overrides everything and is never stored or renewed. No command prints a token.
 */''')
rep('''    private final java.util.function.Function<String, String> env;

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
''', '''    private final java.util.function.Function<String, String> env;
    private final TokenStore store;
    private final TokenSession session;

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
''')
rep('''                case "login" -> login(a);
''', '''                case "login" -> login(a);
                case "logout" -> logout();
''')
rep('''commands: login, deploy,''', '''commands: login, logout, deploy,''')
rep('''        save(Map.of("server", server.replaceAll("/+$", ""), "accessToken", token.get("access_token").asText()));
        out.println("{\\"loggedIn\\":true}");
        return OK;
    }

    private JsonNode form(String url, Map<String, String> fields) throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder();
        fields.forEach((k, v) -> b.append(b.isEmpty() ? "" : "&").append(enc(k)).append('=').append(enc(v)));
        return JSON.readTree(http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())).build()).body());
    }
''', '''        String serverBase = server.replaceAll("/+$", "");
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
''')
rep('''        JsonNode c = loadConfig();
        String server = a.opt("server", c.path("server").asText(""));
        String token = env.apply("SANGAM_TOKEN") != null ? env.apply("SANGAM_TOKEN") : c.path("accessToken").asText("");
        if (server.isEmpty()) {''', '''        JsonNode c = loadConfig();
        String server = a.opt("server", c.path("server").asText(""));
        if (server.isEmpty()) {''')
rep('''        return HttpRequest.newBuilder(URI.create(server + "/api/v1" + path)).timeout(Duration.ofMinutes(2)).header("Accept", "application/json").header("Authorization", "Bearer " + token);
    }

    private int send(HttpRequest r, boolean failOnTestFailures) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(r);
        int s = resp.statusCode();''', '''        return HttpRequest.newBuilder(URI.create(server + "/api/v1" + path)).timeout(Duration.ofMinutes(2)).header("Accept", "application/json");
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
        int s = resp.statusCode();''')
rep('''    private JsonNode loadConfig() throws IOException {
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
        boolean loopback = "localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost());''', '''    /**
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
        boolean loopback = "localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost()) || "[::1]".equals(u.getHost());''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("ok")
