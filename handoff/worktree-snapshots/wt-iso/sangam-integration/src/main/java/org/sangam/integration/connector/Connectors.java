// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.file.FileDropConnector;
import org.sangam.integration.connector.gov.DltRegistry;
import org.sangam.integration.connector.gov.GovConnectors;
import org.sangam.integration.connector.http.HttpRestConnector;
import org.sangam.integration.connector.http.SoapConnector;
import org.sangam.integration.connector.mail.SmtpEmailConnector;
import org.sangam.integration.sim.SimulatorProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * The built-in connector catalogue (R71) for the {@link ConnectorProfile#SIMULATOR} profile: every connector type wired
 * to its in-process simulator on loopback, for air-gapped development (ARCHITECTURE.md §1.9). Ids:
 * {@code rest}, {@code soap}, {@code smtp}, {@code filedrop}, plus the government set from {@link GovConnectors}
 * (IFMS JIT, PFMS file, eSign, DigiLocker push, DLT SMS, payment gateway).
 *
 * <p>LIVE connectors are built from configuration by sangam-server with their {@code Config} records, an
 * {@link EgressPolicy} with the tenant allow-lists, and a vault-backed {@link SecretResolver}.
 */
public final class Connectors {

    private Connectors() {
    }

    public static List<SangamConnector> simulatorCatalogue(SimulatorProfile sims, DltRegistry dlt, String tenantId) {
        List<SangamConnector> all = new ArrayList<>();
        all.add(HttpRestConnector.simulator("rest", sims.restBaseUri()));
        all.add(SoapConnector.simulator("soap", sims.soapBaseUri()));
        all.add(SmtpEmailConnector.simulator("smtp", sims.smtp().port()));
        all.add(FileDropConnector.simulator("filedrop"));
        all.addAll(GovConnectors.simulator(sims, dlt, tenantId).connectors());
        return all;
    }

    public static ConnectorRegistry simulatorRegistry(SimulatorProfile sims, DltRegistry dlt, String tenantId) {
        return new ConnectorRegistry().registerAll(simulatorCatalogue(sims, dlt, tenantId));
    }
}
