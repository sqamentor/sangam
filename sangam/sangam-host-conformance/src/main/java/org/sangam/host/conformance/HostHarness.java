// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.sangam.assembly.host.Capability;
import org.sangam.assembly.host.HttpBinding;
import org.sangam.rest.port.RequestContext;

/**
 * The one thing a host writes to run the conformance kit (M-144, design note section 10.2): a harness in the host's
 * own test sources that starts the whole host, hands out its ports and takes a required capability away. The kit then
 * runs the same scenarios on every host: {@link HostConformanceKit} (equivalence with the golden results, coverage of
 * the capability catalogue) and {@link HostFailClosedKit} (every removable required capability refuses the start).
 *
 * <p>Contract for an implementation:
 * <ul>
 *   <li>{@link #start()} starts a fresh host on its own database, with the tenants {@link #TENANT_A} and
 *       {@link #TENANT_B} provisioned and {@link #administrator} holding every permission in each; it throws when the
 *       host refuses to start, and never swallows that refusal;</li>
 *   <li>{@link #port} returns the port the host itself wired (the same instance its HTTP binding would call); it
 *       throws when the host does not offer the port;</li>
 *   <li>{@link #withoutCapability} is called before {@link #start()} and makes that start lack the capability the way
 *       an operator's configuration or a missing collaborator would; it never fakes the refusal;</li>
 *   <li>{@link #stop()} is safe after a refused or a successful start.</li>
 * </ul>
 */
public interface HostHarness {

    /** First conformance tenant. */
    String TENANT_A = "conformance-a";

    /** Second conformance tenant (cross-tenant scenarios). */
    String TENANT_B = "conformance-b";

    /** Lower-case host name, as in the host SPI ({@code spring}, {@code quarkus}, ...). */
    String hostName();

    void start();

    void stop();

    /** The host's own instance of {@code port}. */
    <T> T port(Class<T> port);

    /** A request context of an administrator of {@code tenantId} with every permission (provisioned by the host). */
    RequestContext administrator(String tenantId);

    /** Before {@link #start()}: the next start lacks {@code capability}. */
    void withoutCapability(Capability capability);

    /** Required capabilities {@link #withoutCapability} can take away on this host. */
    Set<Capability> removableCapabilities();

    /**
     * For a refused start without {@code capability}: a text the refusal (its message or a cause's) contains, so a
     * start refused for another reason never counts as the fail-closed proof.
     */
    String refusalMarker(Capability capability);

    /**
     * Required capabilities this host cannot take away yet, each with the reason (the extraction phase that makes it
     * possible). Only the baseline host may have any (see {@link #baseline()}); every other host must have none.
     */
    Map<Capability, String> capabilityGaps();

    /** True only for the supported standalone baseline host (Spring today). */
    boolean baseline();

    /** The host's HTTP binding, when it offers one. */
    Optional<HttpBinding> http();

    /**
     * RM-30: the host's own database, for the scenarios that prove the host detects a change made behind its ports
     * (audit tamper). A host that offers no port yet need not offer it.
     */
    default javax.sql.DataSource database() {
        throw new UnsupportedOperationException(hostName() + " offers no database to the conformance kit");
    }

    /** The host module's folder, for the thinness report (main-source classes and lines). */
    Path hostModule();
}
