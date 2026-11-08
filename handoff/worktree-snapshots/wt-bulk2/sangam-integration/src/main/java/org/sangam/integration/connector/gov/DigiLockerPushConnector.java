// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.digilocker.DigiLockerPushSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DigiLocker issuer connector (R59, GOV-9, IX.4 P4 licence/permit): pushes the URI of an issued certificate or
 * licence into the holder's DigiLocker (Issuer Push API v1.7.2 approximation, as simulated by
 * {@link DigiLockerPushSimulator}).
 *
 * <p><b>Operation {@code push}</b>: input {@code docType}, {@code digiLockerId} (the holder), {@code uri} (the issuer's
 * document URI, e.g. {@code in.gov.dept-CERTI-12345}); the txn is the operationId (R36). The push is idempotent by
 * URI: a repeat for the same holder and doc type answers {@code duplicate=true} with no new effect, so the connector is
 * <b>class (a)</b> and the HTTP call is marked idempotent. The same URI for another holder or doc type is refused
 * ({@code URI_CONFLICT} → {@code BusinessFailure}).
 *
 * <p><b>Lookup</b> is a re-push of the same request (safe because it is idempotent): {@code duplicate=true} means the
 * earlier push had happened; {@code duplicate=false} means it had not and has now happened. Both answer
 * "found, succeeded". {@code URI_CONFLICT} → found, failed.
 */
public final class DigiLockerPushConnector implements SangamConnector {

    public static final String TYPE = "digilocker";
    public static final String OP_PUSH = "push";
    public static final String PUSH_PATH = "/digilocker/issuer/push-uri";

    private static final Logger LOG = LoggerFactory.getLogger(DigiLockerPushConnector.class);
    private static final String SYSTEM = "DigiLocker";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Configuration of the issuer account.
     *
     * @param id        connector instance id
     * @param baseUri   DigiLocker (or API Setu) base URI
     * @param orgId     issuer organisation id
     * @param transport HTTP transport
     * @param profile   live or simulator
     * @param timeout   per-request timeout; null = 30 s
     */
    public record Config(String id, URI baseUri, String orgId, HttpTransport transport, ConnectorProfile profile,
                         Duration timeout) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(orgId, "orgId");
            Objects.requireNonNull(transport, "transport");
            profile = profile == null ? ConnectorProfile.LIVE : profile;
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    private sealed interface PushAnswer {
        record Accepted(boolean duplicate, String txn) implements PushAnswer {
        }

        record Refused(int httpStatus, String code, String message) implements PushAnswer {
        }

        record Failed(ConnectorResult result) implements PushAnswer {
        }
    }

    private final Config config;
    private final ConnectorDescriptor descriptor;

    public DigiLockerPushConnector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of(OP_PUSH), config.profile());
    }

    /** Wired to the DigiLocker simulator of {@code sims}. */
    public static DigiLockerPushConnector simulator(SimulatorProfile sims) {
        DigiLockerPushSimulator sim = sims.digiLocker();
        return new DigiLockerPushConnector(new Config("digilocker-sim", sim.baseUri(), SimulatorProfile.DIGILOCKER_ORG_ID,
                HttpTransport.forSimulator(), ConnectorProfile.SIMULATOR, Duration.ofSeconds(10)));
    }

    public Config config() {
        return config;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        if (!OP_PUSH.equals(request.operation())) {
            return ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION",
                    "DigiLocker supports only '" + OP_PUSH + "', got '" + request.operation() + "'");
        }
        String docType;
        String holder;
        String uri;
        try {
            docType = request.required("docType");
            holder = request.required("digiLockerId");
            uri = request.required("uri");
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
        try {
            PushAnswer a = push(request, docType, holder, uri);
            return switch (a) {
                case PushAnswer.Accepted ok -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("uri", uri);
                    out.put("docType", docType);
                    out.put("duplicate", ok.duplicate());
                    out.put("txn", ok.txn());
                    yield new ConnectorResult.Success(out, uri, ok.duplicate());
                }
                case PushAnswer.Refused no -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("httpStatus", no.httpStatus());
                    out.put("uri", uri);
                    yield new ConnectorResult.BusinessFailure(no.code(), "DigiLocker refused the push: " + no.message(),
                            out);
                }
                case PushAnswer.Failed f -> f.result();
            };
        } catch (RuntimeException e) {
            LOG.warn("DigiLocker push for operation {} failed unexpectedly", request.operationId(), e);
            return ConnectorResult.OutcomeUnknown.of("DigiLocker: unexpected " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        String docType = request.str("docType");
        String holder = request.str("digiLockerId");
        String uri = request.str("uri");
        if (docType == null || holder == null || uri == null) {
            return new LookupResult.Unavailable("DigiLocker lookup needs docType, digiLockerId and uri");
        }
        try {
            PushAnswer a = push(request, docType, holder, uri);
            return switch (a) {
                case PushAnswer.Accepted ok -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("uri", uri);
                    out.put("docType", docType);
                    out.put("duplicate", ok.duplicate());
                    out.put("pushedByLookup", !ok.duplicate());
                    yield LookupResult.Found.success(out, uri);
                }
                case PushAnswer.Refused no -> "URI_CONFLICT".equals(no.code())
                        ? LookupResult.Found.failed(no.code(), Map.of("uri", uri, "message", String.valueOf(no.message())))
                        : new LookupResult.Unavailable("DigiLocker refused the lookup push: " + no.code());
                case PushAnswer.Failed f -> new LookupResult.Unavailable("DigiLocker: " + describe(f.result()));
            };
        } catch (RuntimeException e) {
            LOG.warn("DigiLocker lookup for operation {} failed unexpectedly", request.operationId(), e);
            return new LookupResult.Unavailable("DigiLocker: unexpected " + e.getClass().getSimpleName());
        }
    }

    private PushAnswer push(ConnectorRequest request, String docType, String holder, String uri) {
        String txn = request.operationId().toString();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<PushUriRequest ver=\"1.0\" ts=\"" + ZonedDateTime.now(IST).format(TS) + "\" txn=\""
                + GovXml.esc(txn) + "\" orgId=\"" + GovXml.esc(config.orgId()) + "\">"
                + "<UriDetails>"
                + "<DocType>" + GovXml.esc(docType) + "</DocType>"
                + "<DigiLockerId>" + GovXml.esc(holder) + "</DigiLockerId>"
                + "<URI>" + GovXml.esc(uri) + "</URI>"
                + "</UriDetails></PushUriRequest>";
        HttpTransport.Call call = new HttpTransport.Call("POST", GovHttp.join(config.baseUri(), PUSH_PATH),
                Map.of("Content-Type", "application/xml; charset=utf-8", "Accept", "application/xml"),
                xml.getBytes(StandardCharsets.UTF_8), GovHttp.timeout(config.timeout(), request), true);
        HttpTransport.Outcome o = config.transport().execute(request.tenantId(), call, GovHttp.deadline(request));
        if (!(o instanceof HttpTransport.Outcome.Response r)) {
            return new PushAnswer.Failed(GovHttp.failure(SYSTEM, o, true));
        }
        Element root = parse(r.body());
        boolean pushResponse = root != null && "PushUriResponse".equals(root.getTagName());
        if (r.is2xx()) {
            if (!pushResponse) {
                return new PushAnswer.Failed(ConnectorResult.OutcomeUnknown.of("DigiLocker: unreadable answer"));
            }
            if ("1".equals(root.getAttribute("status"))) {
                return new PushAnswer.Accepted(Boolean.parseBoolean(root.getAttribute("duplicate")),
                        root.getAttribute("txn"));
            }
            return new PushAnswer.Refused(r.status(), code(root, "PUSH_REFUSED"), root.getAttribute("errMsg"));
        }
        if (r.status() >= 400 && r.status() < 500 && r.status() != 408 && r.status() != 425 && r.status() != 429
                && pushResponse) {
            return new PushAnswer.Refused(r.status(), code(root, "HTTP_" + r.status()), root.getAttribute("errMsg"));
        }
        String code = pushResponse ? code(root, null) : null;
        return new PushAnswer.Failed(GovHttp.responseFailure(SYSTEM, r, true, code,
                pushResponse ? root.getAttribute("errMsg") : null));
    }

    private static String code(Element root, String fallback) {
        String c = root.getAttribute("errCode");
        return c == null || c.isBlank() ? fallback : c;
    }

    private static Element parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return GovXml.parse(body).getDocumentElement();
        } catch (Exception e) {
            return null;
        }
    }

    private static String describe(ConnectorResult r) {
        return switch (r) {
            case ConnectorResult.Success s -> "success";
            case ConnectorResult.BusinessFailure b -> b.code() + ": " + b.message();
            case ConnectorResult.TransientFailure t -> t.message();
            case ConnectorResult.OutcomeUnknown u -> u.reason();
        };
    }
}
