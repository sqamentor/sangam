// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.sim.SimulatorProfile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the government and payment connectors (R57 eSign, R58 IFMS / PFMS / payment gateway, R59 DigiLocker, R60
 * DLT SMS) for a {@link ConnectorProfile}: {@link ConnectorProfile#SIMULATOR SIMULATOR} wires each to its in-process
 * simulator of a {@link SimulatorProfile} (air-gapped development), {@link ConnectorProfile#LIVE LIVE} builds it from
 * the site's configuration. E-mail is not built here (the gov.in mail adapter belongs to the notification module).
 */
public final class GovConnectors {

    private GovConnectors() {
    }

    /**
     * Live configuration of a site. Any connector config may be null when the site does not use that system; the
     * matching bundle entry is then null.
     *
     * @param ifms                IFMS JIT site
     * @param ifmsFeedSinceHours  window of the IFMS failure feed; 0 = 72 h
     * @param pfms                PFMS / IFMIS file exchange
     * @param esign               eSign ESP
     * @param digiLocker          DigiLocker issuer account
     * @param dlt                 DLT SMS gateway (its registry is part of the config)
     * @param paymentGateway      payment gateway merchant account
     * @param pgCallbackSecretRef reference of the gateway's callback secret; null = no callback verifier
     * @param secrets             resolver for {@code pgCallbackSecretRef}
     */
    public record LiveConfig(IfmsJitConnector.Config ifms, int ifmsFeedSinceHours, PfmsFileConnector.Config pfms,
                             EsignConnector.Config esign, DigiLockerPushConnector.Config digiLocker,
                             DltSmsConnector.Config dlt, PaymentGatewayConnector.Config paymentGateway,
                             String pgCallbackSecretRef, SecretResolver secrets) {
        public LiveConfig {
            secrets = secrets == null ? SecretResolver.fromEnvironment() : secrets;
        }
    }

    /** The connectors of one tenant / site, plus the inbound helpers (status poller, callback verifier, bank feed). */
    public record Bundle(IfmsJitConnector ifms, PfmsFileConnector pfms, PfmsStatusPoller pfmsStatusPoller,
                         EsignConnector esign, DigiLockerPushConnector digiLocker, DltSmsConnector dlt,
                         PaymentGatewayConnector paymentGateway, PaymentGatewayCallbackVerifier pgCallbackVerifier,
                         BankAcknowledgementFeed bankAcknowledgementFeed) {

        /** The outbound connectors that are present, for the connector registry (R71). */
        public List<SangamConnector> connectors() {
            List<SangamConnector> out = new ArrayList<>();
            for (SangamConnector c : new SangamConnector[]{ifms, pfms, esign, digiLocker, dlt, paymentGateway}) {
                if (c != null) {
                    out.add(c);
                }
            }
            return List.copyOf(out);
        }
    }

    // ---- whole bundles -------------------------------------------------------------------------

    /**
     * All connectors for {@code profile}. SIMULATOR needs {@code sims}, {@code registry} and {@code tenantId}; LIVE
     * needs {@code live} and {@code tenantId} (the tenant the PFMS exchange and IFMS feed belong to).
     */
    public static Bundle build(ConnectorProfile profile, SimulatorProfile sims, LiveConfig live, DltRegistry registry,
                               String tenantId) {
        Objects.requireNonNull(profile, "profile");
        return profile == ConnectorProfile.SIMULATOR ? simulator(sims, registry, tenantId) : live(live, tenantId);
    }

    /** Every connector wired to its simulator; the IFMS site is class (a). */
    public static Bundle simulator(SimulatorProfile sims, DltRegistry registry, String tenantId) {
        Objects.requireNonNull(sims, "sims");
        Objects.requireNonNull(tenantId, "tenantId");
        DltRegistry reg = registry == null ? new DltRegistry() : registry;
        IfmsJitConnector ifms = IfmsJitConnector.simulator(sims);
        PfmsStatusPoller poller = PfmsStatusPoller.simulator(sims, tenantId);
        BankAcknowledgementFeed feed = new BankAcknowledgementFeed(List.of(poller),
                List.of(new BankAcknowledgementFeed.IfmsSource(ifms, tenantId, 72)));
        return new Bundle(ifms, PfmsFileConnector.simulator(sims), poller, EsignConnector.simulator(sims),
                DigiLockerPushConnector.simulator(sims), DltSmsConnector.simulator(sims, reg, tenantId),
                PaymentGatewayConnector.simulator(sims), PaymentGatewayCallbackVerifier.simulator(sims), feed);
    }

    /** Every configured connector from the live configuration. */
    public static Bundle live(LiveConfig live, String tenantId) {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(tenantId, "tenantId");
        IfmsJitConnector ifms = live.ifms() == null ? null : new IfmsJitConnector(live.ifms());
        PfmsFileConnector pfms = live.pfms() == null ? null : new PfmsFileConnector(live.pfms());
        PfmsStatusPoller poller = live.pfms() == null ? null : new PfmsStatusPoller(live.pfms().transport(), tenantId);
        List<PfmsStatusPoller> pollers = poller == null ? List.of() : List.of(poller);
        List<BankAcknowledgementFeed.IfmsSource> sources = ifms == null ? List.of()
                : List.of(new BankAcknowledgementFeed.IfmsSource(ifms, tenantId, live.ifmsFeedSinceHours()));
        BankAcknowledgementFeed feed = pollers.isEmpty() && sources.isEmpty() ? null
                : new BankAcknowledgementFeed(pollers, sources);
        PaymentGatewayCallbackVerifier verifier = live.pgCallbackSecretRef() == null ? null
                : PaymentGatewayCallbackVerifier.fromSecretRef(live.secrets(), live.pgCallbackSecretRef(),
                PaymentGatewayCallbackVerifier.DEFAULT_TOLERANCE, null);
        return new Bundle(ifms, pfms, poller,
                live.esign() == null ? null : new EsignConnector(live.esign()),
                live.digiLocker() == null ? null : new DigiLockerPushConnector(live.digiLocker()),
                live.dlt() == null ? null : new DltSmsConnector(live.dlt()),
                live.paymentGateway() == null ? null : new PaymentGatewayConnector(live.paymentGateway()),
                verifier, feed);
    }

    // ---- single connectors ---------------------------------------------------------------------

    public static IfmsJitConnector ifmsJit(ConnectorProfile profile, SimulatorProfile sims,
                                           IfmsJitConnector.Config live) {
        return profile == ConnectorProfile.SIMULATOR ? IfmsJitConnector.simulator(requireSims(sims))
                : new IfmsJitConnector(Objects.requireNonNull(live, "live IFMS config"));
    }

    /** IFMS connector whose simulator is switched to {@code simulatorClass} (b/c site rehearsal, A-2). */
    public static IfmsJitConnector ifmsJit(ConnectorProfile profile, SimulatorProfile sims,
                                           IfmsJitConnector.Config live, CapabilityClass simulatorClass) {
        return profile == ConnectorProfile.SIMULATOR ? IfmsJitConnector.simulator(requireSims(sims), simulatorClass)
                : new IfmsJitConnector(Objects.requireNonNull(live, "live IFMS config"));
    }

    public static PfmsFileConnector pfms(ConnectorProfile profile, SimulatorProfile sims,
                                         PfmsFileConnector.Config live) {
        return profile == ConnectorProfile.SIMULATOR ? PfmsFileConnector.simulator(requireSims(sims))
                : new PfmsFileConnector(Objects.requireNonNull(live, "live PFMS config"));
    }

    public static PfmsStatusPoller pfmsStatusPoller(ConnectorProfile profile, SimulatorProfile sims,
                                                    PfmsFileConnector.Config live, String tenantId) {
        return profile == ConnectorProfile.SIMULATOR ? PfmsStatusPoller.simulator(requireSims(sims), tenantId)
                : new PfmsStatusPoller(Objects.requireNonNull(live, "live PFMS config").transport(), tenantId);
    }

    public static EsignConnector esign(ConnectorProfile profile, SimulatorProfile sims, EsignConnector.Config live) {
        return profile == ConnectorProfile.SIMULATOR ? EsignConnector.simulator(requireSims(sims))
                : new EsignConnector(Objects.requireNonNull(live, "live eSign config"));
    }

    public static DigiLockerPushConnector digiLocker(ConnectorProfile profile, SimulatorProfile sims,
                                                     DigiLockerPushConnector.Config live) {
        return profile == ConnectorProfile.SIMULATOR ? DigiLockerPushConnector.simulator(requireSims(sims))
                : new DigiLockerPushConnector(Objects.requireNonNull(live, "live DigiLocker config"));
    }

    public static DltSmsConnector dltSms(ConnectorProfile profile, SimulatorProfile sims, DltRegistry registry,
                                         String tenantId, DltSmsConnector.Config live) {
        return profile == ConnectorProfile.SIMULATOR
                ? DltSmsConnector.simulator(requireSims(sims), Objects.requireNonNull(registry, "registry"), tenantId)
                : new DltSmsConnector(Objects.requireNonNull(live, "live DLT config"));
    }

    public static PaymentGatewayConnector paymentGateway(ConnectorProfile profile, SimulatorProfile sims,
                                                         PaymentGatewayConnector.Config live) {
        return profile == ConnectorProfile.SIMULATOR ? PaymentGatewayConnector.simulator(requireSims(sims))
                : new PaymentGatewayConnector(Objects.requireNonNull(live, "live payment gateway config"));
    }

    public static PaymentGatewayCallbackVerifier paymentGatewayCallbacks(ConnectorProfile profile,
                                                                         SimulatorProfile sims, SecretResolver secrets,
                                                                         String secretRef, Duration tolerance) {
        return profile == ConnectorProfile.SIMULATOR ? PaymentGatewayCallbackVerifier.simulator(requireSims(sims))
                : PaymentGatewayCallbackVerifier.fromSecretRef(Objects.requireNonNull(secrets, "secrets"),
                Objects.requireNonNull(secretRef, "secretRef"), tolerance, null);
    }

    private static SimulatorProfile requireSims(SimulatorProfile sims) {
        return Objects.requireNonNull(sims, "SIMULATOR profile needs a SimulatorProfile");
    }
}
