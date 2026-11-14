import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * SL-65 probe (not product code): which HttpRequest.Builder calls refuse what, and whether the JDK's message echoes the
 * refused header VALUE (it must not reach an outcome reason if it can carry a secret).
 */
public class RequestBuildProbe {
    public static void main(String[] args) {
        System.out.println("java " + System.getProperty("java.version"));
        URI u = URI.create("http://127.0.0.1:1/x");
        probe("value with LF", () -> HttpRequest.newBuilder(u).header("X-Edge-Token", "s3cret-token-VALUE\n"));
        probe("value with CR LF injection", () -> HttpRequest.newBuilder(u).header("X-A", "a\r\nX-Injected: 1"));
        probe("value with trailing space", () -> HttpRequest.newBuilder(u).header("X-A", "abc "));
        probe("value with NUL", () -> HttpRequest.newBuilder(u).header("X-A", "a\u0000b"));
        probe("value with char > 0xFF", () -> HttpRequest.newBuilder(u).header("X-A", "a€b"));
        probe("value with obs-text 0xE9", () -> HttpRequest.newBuilder(u).header("X-A", "café"));
        probe("name with space", () -> HttpRequest.newBuilder(u).header("X Bad", "v"));
        probe("name with colon", () -> HttpRequest.newBuilder(u).header("X:Bad", "v"));
        probe("name empty", () -> HttpRequest.newBuilder(u).header("", "v"));
        probe("restricted name host", () -> HttpRequest.newBuilder(u).header("Host", "v"));
        probe("restricted name date", () -> HttpRequest.newBuilder(u).header("Date", "v"));
        probe("restricted name via", () -> HttpRequest.newBuilder(u).header("Via", "v"));
        probe("restricted name warning", () -> HttpRequest.newBuilder(u).header("Warning", "v"));
        probe("restricted name from", () -> HttpRequest.newBuilder(u).header("From", "v"));
        probe("restricted name referer", () -> HttpRequest.newBuilder(u).header("Referer", "v"));
        probe("method with space", () -> HttpRequest.newBuilder(u).method("GE T", HttpRequest.BodyPublishers.noBody()));
        probe("method CONNECT", () -> HttpRequest.newBuilder(u).method("CONNECT", HttpRequest.BodyPublishers.noBody()));
        probe("method lower-case get", () -> HttpRequest.newBuilder(u).method("get", HttpRequest.BodyPublishers.noBody()));
        probe("zero timeout", () -> HttpRequest.newBuilder(u).timeout(Duration.ZERO));
        probe("ftp uri", () -> HttpRequest.newBuilder(URI.create("ftp://127.0.0.1/x")));
    }

    static void probe(String what, Runnable r) {
        try {
            r.run();
            System.out.println("OK      " + what);
        } catch (RuntimeException e) {
            System.out.println("REFUSED " + what + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
