import sys

vera, absent = sys.argv[1], sys.argv[2]


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit(p + ' missing: ' + old[:90])
        s = s.replace(old, new, 1)
    open(p, 'w', encoding='utf-8', newline='\n').write(s)


edit(vera, [
    ('''import java.nio.file.Path;
import java.time.Clock;''', '''import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;'''),
    ('''import java.util.ArrayList;
import java.util.List;''', '''import java.util.ArrayList;
import java.util.Base64;
import java.util.List;'''),
    ('''import org.sangam.integration.document.pdf.remote.RemotePdfValidator;
''', '''import org.sangam.integration.document.pdf.remote.RemotePdfValidator;
import org.sangam.integration.sidecar.SidecarAuthenticator;
import org.sangam.integration.sidecar.SidecarClient;
import org.sangam.integration.sidecar.SidecarCredentials;
import org.sangam.integration.sidecar.SidecarServer;
import org.sangam.integration.sidecar.SidecarTransport;
'''),
    (''' * PDF fails both; and the same holds through the validator service and the server's {@link RemotePdfValidator}
 * (SL-178).
 */''', ''' * PDF fails both; and the same holds through the validator service and the server's {@link RemotePdfValidator}
 * (SL-178), authenticated with a synthetic token (owner decision 153) over the loopback address (transport mesh).
 */'''),
    ('''    static List<Path> noto() {''', '''    /** Starts the service with a fresh synthetic token; {@link #remote(PdfValidatorService)} is its client. */
    private PdfValidatorService service(int threads) throws Exception {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        Path tokens = Files.writeString(tmp.resolve("validator-tokens"),
                Base64.getUrlEncoder().withoutPadding().encodeToString(b) + "\\n", StandardCharsets.US_ASCII);
        return new PdfValidatorService(new VeraPdfValidator(), VeraPdfValidator.name(), new SidecarServer(
                new InetSocketAddress("127.0.0.1", 0), SidecarTransport.MESH, null, SidecarAuthenticator.tokenFile(tokens),
                new SidecarServer.Limits(threads, PdfValidatorService.DEFAULT_MAX_BYTES, Duration.ofSeconds(120))));
    }

    private RemotePdfValidator remote(PdfValidatorService service) {
        return new RemotePdfValidator(new SidecarClient.Settings("http://127.0.0.1:" + service.port(), "120000", "mesh",
                SidecarCredentials.tokenFile(tmp.resolve("validator-tokens"))));
    }

    static List<Path> noto() {'''),
    ('''        try (PdfValidatorService service = new PdfValidatorService(new VeraPdfValidator(), VeraPdfValidator.name(),
                new InetSocketAddress("127.0.0.1", 0), 2, PdfValidatorService.DEFAULT_MAX_BYTES)) {
            RemotePdfValidator remote = new RemotePdfValidator("http://127.0.0.1:" + service.port(), Duration.ofSeconds(120));''',
     '''        try (PdfValidatorService service = service(2)) {
            RemotePdfValidator remote = remote(service);'''),
    ('''        try (PdfValidatorService service = new PdfValidatorService(new VeraPdfValidator(), VeraPdfValidator.name(),
                new InetSocketAddress("127.0.0.1", 0), 1, PdfValidatorService.DEFAULT_MAX_BYTES)) {
            PdfRenderer.Report report = new RemotePdfValidator("http://127.0.0.1:" + service.port(), Duration.ofSeconds(120))
                    .validate(plain, PdfRenderer.REQUIRED);''',
     '''        try (PdfValidatorService service = service(1)) {
            PdfRenderer.Report report = remote(service).validate(plain, PdfRenderer.REQUIRED);'''),
])

edit(absent, [
    ('''import java.nio.file.Path;
import java.time.Clock;''', '''import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;'''),
    ('''import java.time.ZoneOffset;
import java.util.List;''', '''import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;'''),
    ('''import org.junit.jupiter.api.Test;
''', '''import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
'''),
    ('''import org.sangam.integration.document.pdf.remote.RemotePdfValidator;
''', '''import org.sangam.integration.document.pdf.remote.RemotePdfValidator;
import org.sangam.integration.sidecar.SidecarClient;
import org.sangam.integration.sidecar.SidecarCredentials;
'''),
    (''' * Owner decision 114, RM-26 acceptance, SL-178: the server has no in-process PDF validator. With the renderer and its
 * fonts in place but no validator service configured, or a configured service that does not answer, a PDF/A document
 * is refused with {@code 422 FORMAT_NOT_AVAILABLE} and nothing is stored.
 */
class PdfValidatorServiceAbsentTest {
''', ''' * Owner decisions 114 and 153, RM-26 acceptance, SL-178: the server has no in-process PDF validator. With the renderer
 * and its fonts in place but no validator service configured, no service credentials, or a configured service that does
 * not answer, a PDF/A document is refused with {@code 422 FORMAT_NOT_AVAILABLE} and nothing is stored. The token is
 * synthetic and made by the test.
 */
class PdfValidatorServiceAbsentTest {

    @TempDir
    Path dir;
'''),
    ('''    @Test
    void aConfiguredServiceThatDoesNotAnswerIs422AndNothingIsStored() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        PdfRendering pdf = new PdfRendering(new PdfBoxRenderer(fonts(), CLOCK),
                new RemotePdfValidator("http://127.0.0.1:" + port, Duration.ofSeconds(5)));
        assertTrue(pdf.available(), String.valueOf(pdf.unavailableReason()));
''', '''    @Test
    void aConfiguredServiceWithoutServerCredentialsIs422() {
        PdfRendering pdf = new PdfRendering(new PdfBoxRenderer(fonts(), CLOCK), new RemotePdfValidator(
                new SidecarClient.Settings("https://validator.internal:8443", "5000", null, null)));
        assertFalse(pdf.available());
        PortsDocumentExtrasBridge b = new PortsDocumentExtrasBridge(null, null, null, null, templates(),
                mock(AuditPort.class), pdf);
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> b.generate(CTX, new DocumentExtrasPort.GenerateRequest("tpl-1", "BK-1", "en")));
        assertEquals("FORMAT_NOT_AVAILABLE", p.code());
        assertTrue(p.detail().contains("credentials"), p.detail());
    }

    @Test
    void aConfiguredServiceThatDoesNotAnswerIs422AndNothingIsStored() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        Path token = Files.writeString(dir.resolve("validator-token"),
                Base64.getUrlEncoder().withoutPadding().encodeToString(random) + "\\n", StandardCharsets.US_ASCII);
        PdfRendering pdf = new PdfRendering(new PdfBoxRenderer(fonts(), CLOCK), new RemotePdfValidator(
                new SidecarClient.Settings("http://127.0.0.1:" + port, Long.toString(Duration.ofSeconds(5).toMillis()),
                        "mesh", SidecarCredentials.tokenFile(token))));
        assertTrue(pdf.available(), String.valueOf(pdf.unavailableReason()));
'''),
    ('''import java.net.ServerSocket;
''', '''import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
'''),
])
print('ok')
