// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.registry.asset.AssetService;
import org.sangam.registry.asset.MaintenanceService;
import org.sangam.registry.custody.CustodyService;
import org.sangam.registry.instrument.InstrumentService;
import org.sangam.registry.instrument.VerificationService;
import org.sangam.registry.kernel.ObjectTypeService;
import org.sangam.registry.kernel.RegisteredObjectService;
import org.sangam.registry.numbering.NumberingService;
import org.sangam.registry.party.PartyService;
import org.sangam.registry.register.RegisterBookService;
import org.sangam.registry.support.RegistryAudit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wiring of the whole registry module: one instance per engine (and JVM). The integrator builds it in sangam-server
 * with the dialect, the ports and a side transaction source, exposes the services as Spring beans, and calls
 * {@link #install} so the BPMN delegates find it.
 */
public final class RegistryModule {

    private static final AtomicReference<RegistryModule> INSTANCE = new AtomicReference<>();

    private final Dialect dialect;
    private final RegistryPorts ports;
    private final RegistryAudit audit;
    private final ObjectTypeService types;
    private final NumberingService numbering;
    private final RegisteredObjectService objects;
    private final RegisterBookService registers;
    private final InstrumentService instruments;
    private final VerificationService verification;
    private final PartyService parties;
    private final AssetService assets;
    private final MaintenanceService maintenance;
    private final CustodyService custody;

    /** Profiles on which the development-only defaults (ephemeral key, random pepper) are allowed. */
    public static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "h2", "test", "local");

    /**
     * Wiring with the active profiles read from {@code spring.profiles.active} / {@code sangam.profile} (system
     * properties) or {@code SPRING_PROFILES_ACTIVE} / {@code SANGAM_PROFILE} (environment). No profile counts as
     * non-development.
     *
     * @param sideTransactions independent short transactions (a plain pool) for unique-only number blocks; may be null
     */
    public RegistryModule(Dialect dialect, RegistryPorts ports, Transactions sideTransactions) {
        this(dialect, ports, sideTransactions, activeProfilesFromEnvironment());
    }

    /**
     * Wiring with explicit active profiles (the integrator passes Spring's {@code Environment.getActiveProfiles()}).
     *
     * @throws IllegalStateException on a non-development profile when a development-only default is bound (the
     *                               ephemeral Ed25519 signer or the random-pepper identifier vault): nothing signed
     *                               or hashed with them would survive a restart
     */
    public RegistryModule(Dialect dialect, RegistryPorts ports, Transactions sideTransactions,
                          Collection<String> activeProfiles) {
        RegistryPorts p = ports == null ? RegistryPorts.defaults() : ports;
        boolean dev = activeProfiles != null && activeProfiles.stream()
                .anyMatch(x -> x != null && DEVELOPMENT_PROFILES.contains(x.trim().toLowerCase()));
        List<String> reasons = p.developmentOnlyReasons();
        if (!dev && !reasons.isEmpty()) {
            throw new IllegalStateException("sangam-registry refuses to start on profile(s) " + activeProfiles
                    + " with development-only defaults: " + String.join("; ", reasons)
                    + ". Allowed only on profiles " + DEVELOPMENT_PROFILES + ".");
        }
        this.dialect = dialect;
        this.ports = p;
        this.audit = new RegistryAudit(dialect, this.ports);
        this.types = new ObjectTypeService(dialect, audit);
        this.numbering = new NumberingService(dialect, audit, this.ports, sideTransactions);
        this.objects = new RegisteredObjectService(dialect, types, numbering, audit, this.ports);
        this.registers = new RegisterBookService(dialect, numbering, audit, this.ports);
        this.verification = new VerificationService(dialect, objects, audit, this.ports);
        this.instruments = new InstrumentService(dialect, objects, verification, audit, this.ports);
        this.parties = new PartyService(dialect, audit, this.ports);
        this.assets = new AssetService(dialect, objects, audit, this.ports);
        this.maintenance = new MaintenanceService(dialect, assets, objects, audit, this.ports);
        this.custody = new CustodyService(dialect, objects, audit, this.ports);
    }

    static List<String> activeProfilesFromEnvironment() {
        List<String> out = new ArrayList<>();
        for (String v : new String[]{System.getProperty("spring.profiles.active"), System.getProperty("sangam.profile"),
                System.getenv("SPRING_PROFILES_ACTIVE"), System.getenv("SANGAM_PROFILE")}) {
            if (v != null) {
                for (String s : v.split(",")) {
                    if (!s.isBlank()) {
                        out.add(s.trim());
                    }
                }
            }
        }
        return out;
    }

    /** Makes this module the one the BPMN delegates use. */
    public RegistryModule install() {
        INSTANCE.set(this);
        return this;
    }

    public static RegistryModule instance() {
        RegistryModule m = INSTANCE.get();
        if (m == null) {
            throw new IllegalStateException("RegistryModule not installed (integrator: call install() at start-up)");
        }
        return m;
    }

    public Dialect dialect() {
        return dialect;
    }

    public RegistryPorts ports() {
        return ports;
    }

    public RegistryAudit audit() {
        return audit;
    }

    public ObjectTypeService types() {
        return types;
    }

    public NumberingService numbering() {
        return numbering;
    }

    public RegisteredObjectService objects() {
        return objects;
    }

    public RegisterBookService registers() {
        return registers;
    }

    public InstrumentService instruments() {
        return instruments;
    }

    public VerificationService verification() {
        return verification;
    }

    public PartyService parties() {
        return parties;
    }

    public AssetService assets() {
        return assets;
    }

    public MaintenanceService maintenance() {
        return maintenance;
    }

    public CustodyService custody() {
        return custody;
    }
}
