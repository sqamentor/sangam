// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health;

import org.sangam.connectors.health.cdshooks.CdsHooksConnector;
import org.sangam.connectors.health.fhir.FhirClient;
import org.sangam.connectors.health.fhir.FhirConnector;
import org.sangam.connectors.health.fhir.SmartBackendAuth;
import org.sangam.connectors.health.pas.PasConnector;
import org.sangam.connectors.health.sim.HealthSimulator;
import org.sangam.connectors.health.x12.X12Connector;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.HttpTransport;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wiring of the health connectors. {@link #simulator(HealthSimulator)} returns the SIMULATOR profile (loopback only,
 * air-gapped); live instances are built from their {@code Config} records by the integrator with tenant egress rules
 * and secrets from {@code SecretResolver} (R73).
 *
 * <p>Pack connector ids of the {@code sec.ushealth.*} packs and the connector each one maps to
 * ({@code PackConnectorBindings} in sangam-integration, with the pack's {@code op}):
 * <pre>
 *   x12.eligibility    → x12-sim            op eligibility     (270/271)
 *   x12.serviceReview  → x12-sim            op serviceReview   (278)
 *   x12.claim          → x12-sim            op submitClaim     (837P/I → 999)
 *   x12.claimStatus    → x12-sim            op claimStatus     (276/277)
 *   x12.remit          → x12-sim            op parse           (835)
 *   cdshooks.crd       → crd-sim            op call            (CRD order-sign)
 *   davinci.dtr        → pas-sim            op questionnairePackage
 *   davinci.pas        → pas-sim            op submit / inquire / cancel
 *   fhir.*             → fhir-sim           op read / search / create / update / transaction / operation
 * </pre>
 */
public final class HealthConnectors {

    private HealthConnectors() {
    }

    /** All connectors bound to one running simulator. */
    public static Map<String, SangamConnector> simulator(HealthSimulator sim) {
        HttpTransport t = HttpTransport.forSimulator();
        SmartBackendAuth auth = new SmartBackendAuth(new SmartBackendAuth.Config(SmartBackendAuth.Mode.CLIENT_SECRET,
                java.net.URI.create(sim.fhirBase() + "/token"), "sangam-sim", "system/*.rs system/*.cud", null, null,
                "sim-secret"), t, null);
        FhirClient client = new FhirClient(new FhirClient.Config(sim.fhirBase(), t, auth, Duration.ofSeconds(10), null));
        Map<String, SangamConnector> m = new LinkedHashMap<>();
        m.put("fhir-sim", new FhirConnector("fhir-sim", client, ConnectorProfile.SIMULATOR));
        m.put("pas-sim", new PasConnector("pas-sim", client, ConnectorProfile.SIMULATOR));
        m.put("crd-sim", new CdsHooksConnector(new CdsHooksConnector.Config("crd-sim", sim.baseUri(), "urn:sangam:sim",
                null, null, t, ConnectorProfile.SIMULATOR, Duration.ofSeconds(5))));
        m.put("x12-sim", new X12Connector(new X12Connector.Config("x12-sim", sim.baseUri(), "SANGAM", "SIMPAYER", false,
                null, null, null, null, t, ConnectorProfile.SIMULATOR, Duration.ofSeconds(20))));
        return m;
    }

    /** Pack connector id → (registry connector id, operation), for {@code PackConnectorBindings}. */
    public static Map<String, String[]> packBindings() {
        Map<String, String[]> b = new LinkedHashMap<>();
        b.put("x12.eligibility", new String[]{"x12-sim", "eligibility"});
        b.put("x12.serviceReview", new String[]{"x12-sim", "serviceReview"});
        b.put("x12.claim", new String[]{"x12-sim", "submitClaim"});
        b.put("x12.claimStatus", new String[]{"x12-sim", "claimStatus"});
        b.put("x12.remit", new String[]{"x12-sim", "parse"});
        b.put("cdshooks.crd", new String[]{"crd-sim", "call"});
        b.put("davinci.dtr", new String[]{"pas-sim", "questionnairePackage"});
        b.put("davinci.pas", new String[]{"pas-sim", "submit"});
        return b;
    }
}
