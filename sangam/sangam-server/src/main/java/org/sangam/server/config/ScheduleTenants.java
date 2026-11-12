// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * SL-117: the ONE reader of the tenants a per-tenant background schedule runs for (compliance, channel runtimes, case,
 * AI worker, field, ops). The result is the explicit list {@code <property>} plus every non-suspended tenant of the
 * tenant directory ({@link AdminPort#tenants}, all pages), read as the system principal of the attribution tenant
 * ({@link AttributionTenant#resolve}).
 *
 * <p>Before, {@code ComplianceWiring} and {@code CaseWiring} each had a copy that built the system context from the raw
 * {@code sangam.tenant.default} ({@code ""} by default, which {@code SecurityIdentity} refuses as a blank tenant), read
 * only the first page, and swallowed every directory failure at DEBUG; with the explicit list empty by default, the
 * schedule then ran for no tenant and nothing was logged. A directory failure is now logged at WARN with its cause.
 * Callers read the tenants on every cycle, so the next cycle retries the directory.
 */
public final class ScheduleTenants {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleTenants.class);
    static final int PAGE_SIZE = Page.PageRequest.MAX_SIZE;
    private static final AdminPort.AdminFilter ALL = new AdminPort.AdminFilter(null, null, null, null, null, null, null,
            false);

    private ScheduleTenants() {
    }

    /**
     * @param property  the explicit tenant list (extra tenants on top of the directory; may be absent or empty)
     * @param principal the system principal id the directory is read as (e.g. {@code system:compliance})
     * @param monitor   the schedule's monitor: counts directory failures and throttles their WARN (at once, then at
     *                  most every 5 minutes while failing; one INFO on recovery)
     */
    public static List<String> resolve(Environment env, ObjectProvider<AdminPort> admin, String property, String principal,
                                       ScheduleTenantsMonitor monitor) {
        List<String> explicit = Binder.get(env).bind(property, Bindable.listOf(String.class)).orElse(List.of());
        Set<String> out = new LinkedHashSet<>(explicit);
        AdminPort port = admin.getIfAvailable();
        if (port != null) {
            try {
                out.addAll(directory(port, systemContext(env, principal)));
                if (monitor.directoryRecovered()) {
                    LOG.info("SL-117: the tenant directory is readable again for the {} schedule ({}, {})",
                            monitor.scheduler(), property, principal);
                }
            } catch (RuntimeException e) {
                if (monitor.directoryFailed()) {
                    LOG.warn("SL-117: the tenant directory could not be read for the {} schedule ({}, {}); this cycle runs"
                            + " only for the explicit tenants {} and the next cycle retries the directory (repeated at"
                            + " most every 5 minutes while it fails)", monitor.scheduler(), property, principal,
                            explicit, e);
                } else {
                    LOG.debug("tenant directory still unreadable for the {} schedule: {}", monitor.scheduler(), e.toString());
                }
            }
        }
        out.removeIf(t -> t == null || t.isBlank());
        return new ArrayList<>(out);
    }

    /** The system context the directory is read in: home tenant from {@link AttributionTenant}, never a raw property. */
    static RequestContext systemContext(Environment env, String principal) {
        return new RequestContext(AttributionTenant.resolve(env), principal, Set.of("sangam-system", "sangam-admin"),
                null, null, null, null, null, "SYSTEM");
    }

    /** Non-suspended tenant ids of every directory page; stops at a short page or a page that adds no new tenant. */
    static List<String> directory(AdminPort port, RequestContext sys) {
        return directory(port, sys, false);
    }

    /**
     * The one directory reader. {@code includeSuspended=false}: the tenants a schedule runs for; {@code true}: every
     * tenant id the database holds (M-016: a dedicated instance checks that its database holds no other tenant).
     */
    static List<String> directory(AdminPort port, RequestContext sys, boolean includeSuspended) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (int p = 0; ; p++) {
            Page<AdminPort.Tenant> page = port.tenants(sys, ALL, new Page.PageRequest(p, PAGE_SIZE));
            boolean progressed = false;
            for (AdminPort.Tenant t : page.items()) {
                if (seen.add(t.id())) {
                    progressed = true;
                    if (includeSuspended || !"SUSPENDED".equals(t.status())) {
                        out.add(t.id());
                    }
                }
            }
            if (page.items().size() < PAGE_SIZE || !progressed) {
                return out;
            }
        }
    }
}
