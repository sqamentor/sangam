import io, os
os.chdir(r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam")


def edit(p, pairs):
    s = io.open(p, encoding='utf-8', newline='').read()
    for old, new in pairs:
        old = old.replace('\r\n', '\n'); new = new.replace('\r\n', '\n')
        if '\r\n' in s:
            old = old.replace('\n', '\r\n'); new = new.replace('\n', '\r\n')
        assert s.count(old) == 1, (p, old[:60], s.count(old))
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)


edit('sangam-rest/src/main/java/org/sangam/rest/modules/connectorshealth/HealthHooksController.java', [
("""import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.web.RequestContexts;
import org.springframework.http.HttpStatus;
""", """import org.sangam.rest.port.ApiProblem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
"""),
(""" *   <li>{@code GET /cds-services} and {@code POST /cds-services/{id}}: the CDS Hooks service endpoints (HL7 CDS Hooks
 *       2.0 fixes these paths at the server root). The caller must be an authenticated client principal.</li>
 * </ul>
 */""", """ *   <li>{@code GET /cds-services} and {@code POST /cds-services/{id}}: the CDS Hooks service endpoints (HL7 CDS Hooks
 *       2.0 fixes these paths at the server root). M-006: the caller is a CDS client (the EHR) authenticated by the JWT
 *       it signs with its own key ({@code Authorization: Bearer}), verified by {@link CdsClientVerifier} against that
 *       client's registered JWKS ({@code iss} allow-list, {@code aud} = the URL of this endpoint, {@code exp}/{@code iat},
 *       {@code jti} replay). A platform login is not a CDS client. Without a registered client the routes refuse every
 *       call (401). This check applies to these two routes only; the other hooks keep their own checks.</li>
 * </ul>
 */"""),
("""    private final HealthHooksPort hooks;
    private final org.sangam.rest.port.PermissionPort permissions;

    public HealthHooksController(HealthHooksPort hooks, org.sangam.rest.port.PermissionPort permissions) {
        this.hooks = hooks;
        this.permissions = permissions;
    }
""", """    static final String CDS_SERVICES = "/cds-services";
    private static final String BEARER = "Bearer ";

    private final HealthHooksPort hooks;
    private final org.sangam.rest.port.PermissionPort permissions;
    private final ObjectProvider<CdsClientVerifier> cdsClients;

    public HealthHooksController(HealthHooksPort hooks, org.sangam.rest.port.PermissionPort permissions,
                                 ObjectProvider<CdsClientVerifier> cdsClients) {
        this.hooks = hooks;
        this.permissions = permissions;
        this.cdsClients = cdsClients;
    }
"""),
("""    public ResponseEntity<String> cdsDiscovery(HttpServletRequest request) {
        client(request);
""", """    public ResponseEntity<String> cdsDiscovery(HttpServletRequest request) {
        cdsClient(request, CDS_SERVICES);
"""),
("""        HealthHooksPort.CdsReply r = hooks.cdsCall(id, body, client(request));
""", """        HealthHooksPort.CdsReply r = hooks.cdsCall(id, body, cdsClient(request, CDS_SERVICES + "/" + id));
"""),
("""    private static String client(HttpServletRequest request) {
        SangamPrincipal p = RequestContexts.principal(request);
        if (p == null) {
            throw new ApiProblem(401, "UNAUTHENTICATED", "CDS Hooks calls need an authenticated client (JWT)");
        }
        return p.principalId();
    }
""", """    /** The verified CDS client ({@code iss}) of this call, or 401. */
    private String cdsClient(HttpServletRequest request, String endpointPath) {
        CdsClientVerifier verifier = cdsClients.getIfAvailable();
        if (verifier == null) {
            throw new ApiProblem(401, "CDS_CLIENT_NOT_CONFIGURED",
                    "no CDS Hooks client is registered (sangam.connectors-health.cds-service.clients)");
        }
        String header = request.getHeader("Authorization");
        if (header == null || header.length() <= BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new ApiProblem(401, "UNAUTHENTICATED", "CDS Hooks calls need the client's signed JWT (Authorization: Bearer)");
        }
        try {
            return verifier.verify(header.substring(BEARER.length()).trim(), endpointPath);
        } catch (CdsClientVerifier.Rejected e) {
            throw new ApiProblem(401, "CDS_CLIENT_REJECTED", e.getMessage()).with("reason", e.reason());
        }
    }
"""),
])

edit('sangam-connectors-health/src/main/java/org/sangam/connectors/health/cdshooks/CdsHooksService.java', [
(""" * per service id); it returns cards and system actions. JWT verification of the caller is done before
 * {@link #handle} by the REST layer with the client's JWKS (the {@code authorizedClient} argument is the verified
 * {@code iss}).
""", """ * per service id); it returns cards and system actions. JWT verification of the caller is done before
 * {@link #handle} by the REST layer with the client's JWKS (M-006: sangam-rest {@code CdsClientVerifier} over
 * sangam-security {@code ClientJwtVerifier}; the {@code authorizedClient} argument is the verified {@code iss}).
"""),
])
print("ok")
