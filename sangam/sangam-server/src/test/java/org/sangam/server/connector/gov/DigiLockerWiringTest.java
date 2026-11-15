// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.connector.gov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.integration.connector.gov.DigiLockerRequesterConnector;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.digilocker.DigiLockerRequesterSimulator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * M-072 wiring of the document-wallet requester. SL-104: the business zone of the age check comes from configuration
 * only; nothing in product Java supplies one country's zone when the key is missing.
 */
class DigiLockerWiringTest {

    private static final String P = "sangam.connectors.digilocker.requester.";

    private static ApplicationContextRunner requester() {
        return new ApplicationContextRunner().withUserConfiguration(DigiLockerWiring.class).withPropertyValues(
                P + "enabled=true", P + "base-url=https://requester.example", P + "client-id=client-1",
                P + "client-secret-ref=secret://wallet/client-secret", P + "redirect-uri=https://sangam.example/callback");
    }

    @Test
    void theZoneOfTheAgeCheckIsTheConfiguredZone() {
        for (String zone : new String[] {"Pacific/Auckland", "America/Bogota", "UTC"}) {
            requester().withPropertyValues(P + "zone=" + zone).run(ctx -> {
                assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
                assertEquals(ZoneId.of(zone), ctx.getBean(DigiLockerRequesterConnector.class).config().zone());
                assertNotNull(ctx.getBean(CompliancePorts.AdultIdentityVerifier.class));
            });
        }
    }

    /** Before SL-104 a missing zone silently became one country's zone and the server started. */
    @Test
    void aMissingZoneRefusesTheStartByName() {
        requester().run(ctx -> {
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure, "a requester without a configured zone must not start");
            assertTrue(rootMessage(failure).contains(P + "zone is required"), rootMessage(failure));
        });
        requester().withPropertyValues(P + "zone=  ").run(ctx -> {
            assertNotNull(ctx.getStartupFailure(), "a blank zone is a missing zone");
            assertTrue(rootMessage(ctx.getStartupFailure()).contains(P + "zone is required"),
                    rootMessage(ctx.getStartupFailure()));
        });
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DigiLockerWiring.zone(new MockEnvironment()));
        assertEquals(P + "zone is required", e.getMessage());
    }

    @Test
    void anUnknownZoneIdIsRefusedByName() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DigiLockerWiring.zone(new MockEnvironment().withProperty(P + "zone", "Mars/Olympus")));
        assertEquals(P + "zone is not a zone id: Mars/Olympus", e.getMessage());
        assertEquals(ZoneId.of("Europe/Lisbon"),
                DigiLockerWiring.zone(new MockEnvironment().withProperty(P + "zone", " Europe/Lisbon ")));
    }

    @Test
    void theWiringIsOffUnlessEnabled() {
        new ApplicationContextRunner().withUserConfiguration(DigiLockerWiring.class).run(ctx -> {
            assertNull(ctx.getStartupFailure(), String.valueOf(ctx.getStartupFailure()));
            assertTrue(ctx.getBeansOfType(DigiLockerRequesterConnector.class).isEmpty());
        });
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    @Test
    void theAdultVerifierAnswersOnlyItsMethodCodesAndMapsConsentFailures() {
        try (SimulatorProfile sims = new SimulatorProfile()) {
            DigiLockerRequesterSimulator sim = sims.digiLockerRequester();
            DigiLockerRequesterConnector c = DigiLockerRequesterConnector.simulator(sims);
            CompliancePorts.AdultIdentityVerifier v = DigiLockerWiring.verifier(c, Set.of("DIGILOCKER_TOKEN"));

            CompliancePorts.AdultVerification other = v.verifyAdult("t1", "p1", "AADHAAR_OTP", Map.of());
            assertFalse(other.identifiable());
            assertEquals("METHOD_NOT_SUPPORTED", other.detail());

            DigiLockerRequesterConnector.AuthorizationRequest a = c.authorization("s");
            String code = sim.consent(new DigiLockerRequesterSimulator.Person("DL-9", "P", LocalDate.of(1985, 3, 3), Map.of()),
                    DigiLockerRequesterSimulator.challengeOf(a.codeVerifier()));
            Map<String, String> ev = Map.of("code", code, "codeVerifier", a.codeVerifier(),
                    "redirectUri", DigiLockerRequesterSimulator.REDIRECT_URI);
            CompliancePorts.AdultVerification ok = v.verifyAdult("t1", "p1", "digilocker_token", ev);
            assertTrue(ok.identifiable() && ok.adult(), ok.toString());
            assertNotNull(ok.tokenRef());

            CompliancePorts.AdultVerification reused = v.verifyAdult("t1", "p1", "DIGILOCKER_TOKEN", ev);
            assertFalse(reused.adult());
            assertEquals("CONSENT_INVALID", reused.detail());
        }
    }
}
