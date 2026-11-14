// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.EnvironmentPolicy;
import org.sangam.core.security.ProductionMode;
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

    /**
     * Profile names the deprecated profile-list constructors treat as development (the host adapters own the real
     * classification, decision 143).
     */
    @Deprecated(forRemoval = true)
    public static final Set<String> DEVELOPMENT_PROFILES = EnvironmentPolicy.DEVELOPMENT_PROFILES;

    /**
     * Wiring with the active profiles read from {@code spring.profiles.active} / {@code sangam.profile} (system
     * properties) or {@code SPRING_PROFILES_ACTIVE} / {@code SANGAM_PROFILE} (environment), and the production switch
     * from {@code sangam.security.production} / {@code SANGAM_SECURITY_PRODUCTION}; a missing switch refuses the start
     * (decision 143).
     *
     * @param sideTransactions independent short transactions (a plain pool) for unique-only number blocks; may be null
     * @deprecated embedding without a host adapter; pass the resolved environment
     *             ({@link #RegistryModule(Dialect, RegistryPorts, Transactions, EnvironmentPolicy.Resolution)})
     */
    @Deprecated(forRemoval = true)
    public RegistryModule(Dialect dialect, RegistryPorts ports, Transactions sideTransactions) {
        this(dialect, ports, sideTransactions, activeProfilesFromEnvironment());
    }

    /**
     * Wiring with explicit profile names. The production switch is not invented (decision 143: development-only
     * defaults need a development profile <em>and</em> an explicit non-production switch; a missing switch refuses the
     * start): it is read from trusted deployment configuration, the system property {@code sangam.security.production}
     * or the environment variable {@code SANGAM_SECURITY_PRODUCTION}, and resolved by {@link EnvironmentPolicy}.
     *
     * @deprecated tests and embeddings only; hosts pass the resolution of their adapter
     *             ({@link #RegistryModule(Dialect, RegistryPorts, Transactions, EnvironmentPolicy.Resolution)})
     */
    @Deprecated(forRemoval = true)
    public RegistryModule(Dialect dialect, RegistryPorts ports, Transactions sideTransactions,
                          Collection<String> activeProfiles) {
        this(dialect, ports, sideTransactions, legacy(activeProfiles));
    }

    /**
     * Wiring with the platform's resolved environment (decision 143, RM-06): the host adapter resolves it once from
     * trusted deployment configuration ({@code ProductionEnvironment.resolve(env)} in sangam-server).
     *
     * <p>Development-only defaults (the ephemeral signer, the random-pepper vault, the permit-all consent port) are
     * allowed only when {@link EnvironmentPolicy.Resolution#developmentDefaultsAllowed()}: a recognised development or
     * test profile and an explicit non-production switch. Production, staging (non-production without a development
     * profile) and any unresolved configuration refuse them.
     *
     * @param environment the resolved environment; null is refused like a production one
     * @throws IllegalStateException when a development-only default is bound outside a development environment
     */
    public RegistryModule(Dialect dialect, RegistryPorts ports, Transactions sideTransactions,
                          EnvironmentPolicy.Resolution environment) {
        RegistryPorts p = ports == null ? RegistryPorts.defaults() : ports;
        List<String> reasons = p.developmentOnlyReasons();
        if (!reasons.isEmpty() && (environment == null || !environment.developmentDefaultsAllowed())) {
            throw new IllegalStateException("sangam-registry refuses to start "
                    + (environment == null ? "without a resolved environment"
                    : environment.production() ? "in production" : "outside a development environment")
                    + " with development-only defaults: " + String.join("; ", reasons)
                    + ". Allowed only in a development or test environment (decision 143"
                    + (environment == null ? "" : "; " + environment.describe()) + ").");
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

    /** Environment variable form of {@link ProductionMode#PROPERTY} (what the Helm chart and the image set). */
    static final String SWITCH_ENV = "SANGAM_SECURITY_PRODUCTION";

    /** The deprecated profile-list path: the switch from deployment configuration, never invented (decision 143). */
    private static EnvironmentPolicy.Resolution legacy(Collection<String> profiles) {
        String property = System.getProperty(ProductionMode.PROPERTY);
        if (property != null && !property.isBlank()) {
            return legacy(profiles, property, "system property " + ProductionMode.PROPERTY);
        }
        String variable = System.getenv(SWITCH_ENV);
        return legacy(profiles, variable, variable == null ? null : "environment variable " + SWITCH_ENV);
    }

    /**
     * The resolution of the profile-list path for a given switch: a missing, invalid or contradictory switch throws
     * {@link EnvironmentPolicy.ConfigurationError} (an {@link IllegalStateException}), so the start is refused.
     */
    static EnvironmentPolicy.Resolution legacy(Collection<String> profiles, String rawSwitch, String switchSource) {
        return EnvironmentPolicy.resolve(rawSwitch, switchSource, EnvironmentPolicy.signals(profiles));
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
