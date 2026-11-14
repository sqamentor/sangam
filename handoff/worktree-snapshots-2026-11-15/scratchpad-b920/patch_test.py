import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = '''        assertEquals(Main.OK, main(Map.of("S", "secret")).run(new String[]{"login", "--server", "https://s.example/", "--issuer", "https://i.example", "--client", "c", "--secret-env", "S"}));
        String stored = Files.readString(dir.resolve("c/cli.json"));
        assertTrue(stored.contains("\\"T\\"") && stored.contains("https://s.example\\""), stored);
        assertFalse(stored.contains("secret"));
'''
new = '''        assertEquals(Main.OK, main(Map.of("S", "secret")).run(new String[]{"login", "--server", "https://s.example/", "--issuer", "https://i.example", "--client", "c", "--secret-env", "S"}));
        // M-093: cli.json keeps settings only; the token lives in the token store (here the owner-only file fallback).
        String settings = Files.readString(dir.resolve("c/cli.json"));
        assertTrue(settings.contains("https://s.example\\""), settings);
        assertFalse(settings.contains("\\"T\\""), "no token in cli.json: " + settings);
        assertFalse(settings.contains("secret\\""), "only the variable name is stored: " + settings);
        assertTrue(settings.contains("\\"secretEnv\\":\\"S\\""), settings);
        String account = TokenStore.account("https://s.example", "c");
        String stored = Files.readString(dir.resolve("c/credentials/" + account + ".json"));
        assertTrue(stored.contains("\\"T\\""), stored);
        assertFalse(stored.contains("secret\\""));
        assertFalse(out.toString().contains("\\"T\\"") || err.toString().contains("\\"T\\""), "the token is never printed");
'''
assert s.count(old) == 1
s = s.replace(old, new)

old2 = '''        assertEquals(Main.AUTH, main(Map.of()).run(new String[]{"login", "--server", "https://s.example", "--issuer", "https://i.example", "--client", "c", "--secret-env", "MISSING"}));
    }
}'''
new2 = '''        assertEquals(Main.AUTH, main(Map.of()).run(new String[]{"login", "--server", "https://s.example", "--issuer", "https://i.example", "--client", "c", "--secret-env", "MISSING"}));
    }

    // ---- M-093: refresh ------------------------------------------------------------------------

    Instant now = Instant.parse("2026-11-12T10:00:00Z");

    Main session(Map<String, String> env) {
        return new Main(r -> {
            sent.add(r);
            Object[] a = answers.poll();
            if (a == null) {
                throw new IOException("refused");
            }
            return response((int) a[0], (String) a[1], r);
        }, dir.resolve("c/cli.json"), new PrintStream(out), new PrintStream(err), env::get, null, Clock.fixed(now, ZoneOffset.UTC));
    }

    void loginWithRefresh(long expiresIn) {
        answer(200, "{\\"token_endpoint\\":\\"https://i.example/token\\"}");
        answer(200, "{\\"access_token\\":\\"A1\\",\\"refresh_token\\":\\"R1\\",\\"expires_in\\":" + expiresIn + "}");
        assertEquals(Main.OK, session(Map.of("S", "secret")).run(new String[]{"login", "--server", "https://s.example", "--issuer", "https://i.example", "--client", "c", "--secret-env", "S"}));
        sent.clear();
    }

    static String form(HttpRequest r) {
        return r.bodyPublisher().map(p -> {
            java.util.concurrent.CompletableFuture<String> body = new java.util.concurrent.CompletableFuture<>();
            p.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                final StringBuilder b = new StringBuilder();

                public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                public void onNext(java.nio.ByteBuffer item) {
                    byte[] x = new byte[item.remaining()];
                    item.get(x);
                    b.append(new String(x, java.nio.charset.StandardCharsets.UTF_8));
                }

                public void onError(Throwable t) {
                    body.completeExceptionally(t);
                }

                public void onComplete() {
                    body.complete(b.toString());
                }
            });
            return body.join();
        }).orElse("");
    }

    @Test
    void validTokenIsUsedWithoutRefresh() {
        loginWithRefresh(3600);
        answer(200, "[]");
        assertEquals(Main.OK, session(Map.of()).run(new String[]{"list", "tasks"}));
        assertEquals(1, sent.size());
        assertEquals("Bearer A1", sent.get(0).headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void tokenNearExpiryIsRefreshedFirstAndRotatedRefreshTokenIsKept() throws Exception {
        loginWithRefresh(30); // inside the 60 s skew
        answer(200, "{\\"access_token\\":\\"A2\\",\\"refresh_token\\":\\"R2\\",\\"expires_in\\":3600}");
        answer(200, "[]");
        assertEquals(Main.OK, session(Map.of("S", "secret")).run(new String[]{"list", "tasks"}));
        assertEquals("https://i.example/token", sent.get(0).uri().toString());
        String f = form(sent.get(0));
        assertTrue(f.contains("grant_type=refresh_token") && f.contains("refresh_token=R1") && f.contains("client_id=c"), f);
        assertTrue(f.contains("client_secret=secret"), "a confidential client authenticates the refresh: " + f);
        assertEquals("Bearer A2", sent.get(1).headers().firstValue("Authorization").orElseThrow());
        String stored = Files.readString(dir.resolve("c/credentials/" + TokenStore.account("https://s.example", "c") + ".json"));
        assertTrue(stored.contains("\\"R2\\"") && !stored.contains("\\"R1\\""), "the rotated refresh token replaces the old one");
        assertFalse(out.toString().contains("A2") || err.toString().contains("A2") || err.toString().contains("R2"));
    }

    @Test
    void unauthorizedTriggersOneRefreshAndTheSameRequestIsRetried() {
        loginWithRefresh(3600);
        answer(401, "{}");
        answer(200, "{\\"access_token\\":\\"A2\\",\\"expires_in\\":3600}");
        answer(201, "{}");
        assertEquals(Main.OK, session(Map.of("S", "secret")).run(new String[]{"start", "k", "--idempotency-key", "ci-1"}));
        assertEquals(3, sent.size());
        assertEquals("Bearer A1", sent.get(0).headers().firstValue("Authorization").orElseThrow());
        assertEquals("Bearer A2", sent.get(2).headers().firstValue("Authorization").orElseThrow());
        assertEquals("ci-1", sent.get(2).headers().firstValue("Idempotency-Key").orElseThrow(), "the retry is the same operation");
        assertEquals(1, sent.get(2).headers().allValues("Authorization").size());
    }

    @Test
    void failedRefreshEndsWithAuthAndNamesOnlyTheErrorCode() {
        loginWithRefresh(3600);
        answer(401, "{}");
        answer(400, "{\\"error\\":\\"invalid_grant\\",\\"error_description\\":\\"R1 expired\\"}");
        assertEquals(Main.AUTH, session(Map.of()).run(new String[]{"get", "/x"}));
        assertTrue(err.toString().contains("invalid_grant"), err.toString());
        assertFalse(err.toString().contains("R1") || out.toString().contains("R1"), "no token material is printed");
        assertEquals(2, sent.size(), "no second retry");
    }

    @Test
    void servicePrincipalWithoutRefreshTokenRepeatsClientCredentials() {
        answer(200, "{\\"token_endpoint\\":\\"https://i.example/token\\"}");
        answer(200, "{\\"access_token\\":\\"A1\\",\\"expires_in\\":10}");
        assertEquals(Main.OK, session(Map.of("S", "secret")).run(new String[]{"login", "--server", "https://s.example", "--issuer", "https://i.example", "--client", "c", "--secret-env", "S"}));
        sent.clear();
        answer(200, "{\\"access_token\\":\\"A2\\",\\"expires_in\\":300}");
        answer(200, "[]");
        assertEquals(Main.OK, session(Map.of("S", "secret")).run(new String[]{"list", "tasks"}));
        String f = form(sent.get(0));
        assertTrue(f.contains("grant_type=client_credentials") && f.contains("client_secret=secret"), f);
        assertEquals("Bearer A2", sent.get(1).headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void sangamTokenOverridesAndIsNeverRenewed() {
        loginWithRefresh(3600);
        answer(401, "{}");
        assertEquals(Main.AUTH, session(Map.of("SANGAM_TOKEN", "E")).run(new String[]{"get", "/x"}));
        assertEquals(1, sent.size());
        assertEquals("Bearer E", sent.get(0).headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void legacyCliJsonTokenIsMovedOutOfTheSettingsFile() throws Exception {
        Files.createDirectories(dir.resolve("c"));
        Files.writeString(dir.resolve("c/cli.json"), "{\\"server\\":\\"https://s.example\\",\\"accessToken\\":\\"OLD\\"}");
        answer(200, "[]");
        assertEquals(Main.OK, session(Map.of()).run(new String[]{"list", "tasks"}));
        assertEquals("Bearer OLD", sent.get(0).headers().firstValue("Authorization").orElseThrow());
        assertFalse(Files.readString(dir.resolve("c/cli.json")).contains("OLD"));
        assertTrue(Files.readString(dir.resolve("c/credentials/" + TokenStore.account("https://s.example", "") + ".json")).contains("OLD"));
    }

    @Test
    void logoutForgetsTokensButKeepsTheServer() throws Exception {
        loginWithRefresh(3600);
        assertEquals(Main.OK, session(Map.of()).run(new String[]{"logout"}));
        assertTrue(out.toString().contains("\\"hadTokens\\":true"), out.toString());
        assertFalse(Files.exists(dir.resolve("c/credentials/" + TokenStore.account("https://s.example", "c") + ".json")));
        String settings = Files.readString(dir.resolve("c/cli.json"));
        assertTrue(settings.contains("https://s.example") && !settings.contains("account"), settings);
    }

    @Test
    void plainHttpTokenEndpointIsRefusedForRefresh() throws Exception {
        loginWithRefresh(10);
        String cfg = Files.readString(dir.resolve("c/cli.json")).replace("https://i.example/token", "http://i.example/token");
        Files.writeString(dir.resolve("c/cli.json"), cfg);
        assertEquals(Main.USAGE, session(Map.of()).run(new String[]{"list", "tasks"}));
        assertTrue(sent.isEmpty(), "no refresh token sent over plain http");
    }
}'''
assert s.count(old2) == 1
s = s.replace(old2, new2)
s = s.replace('''import java.nio.file.Path;
''', '''import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
''')
open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
