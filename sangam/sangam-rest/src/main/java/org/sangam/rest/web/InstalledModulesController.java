// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.sangam.rest.port.RequestContext;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner decision 399 (Q3): {@code GET /api/v1/me/capabilities}, the optional modules whose REST API this server serves,
 * so the UI hides the menu entries of a module that is not installed. Read-only; any signed-in caller (the
 * {@code /api/**} gate), no permission: it names modules, not data.
 *
 * <p>Derived from the real wiring, not from configuration: each module's controllers are registered only by its
 * {@code *RestConfiguration} ({@code @ConditionalOnClass} on the module jar and {@code @ConditionalOnBean} on the
 * ports sangam-server binds when {@code sangam.modules.<id>.enabled} holds). A module is listed exactly when every
 * controller below is a bean of this context; without them its routes are not mapped and the UI's first call fails.
 */
@RestController
@RequestMapping("/api/v1")
public class InstalledModulesController {

    /** Module id → the controllers (by class name, so an absent module's classes are never loaded) of its UI screens. */
    static final Map<String, List<String>> MODULE_CONTROLLERS = moduleControllers();

    /** Response body: the installed module ids, sorted. */
    public record Capabilities(List<String> modules) {
    }

    private final ApplicationContext context;
    private volatile List<String> installed;

    public InstalledModulesController(ApplicationContext context) {
        this.context = context;
    }

    @GetMapping("/me/capabilities")
    public Capabilities capabilities(RequestContext ctx) {
        List<String> m = installed;
        if (m == null) {
            // the controllers are fixed once the context is refreshed, so the answer is computed once
            // by bean type, without instantiating anything
            m = installed(java.util.Arrays.stream(context.getBeanNamesForAnnotation(RestController.class))
                    .map(context::getType).filter(java.util.Objects::nonNull)
                    .map(t -> ClassUtils.getUserClass(t).getName()).collect(Collectors.toSet()));
            installed = m;
        }
        return new Capabilities(m);
    }

    /** The module ids whose controllers are all among {@code controllerClassNames}, sorted. */
    static List<String> installed(Collection<String> controllerClassNames) {
        Set<String> present = Set.copyOf(controllerClassNames);
        TreeSet<String> out = new TreeSet<>();
        MODULE_CONTROLLERS.forEach((id, controllers) -> {
            if (present.containsAll(controllers)) {
                out.add(id);
            }
        });
        return List.copyOf(new ArrayList<>(out));
    }

    private static Map<String, List<String>> moduleControllers() {
        String m = "org.sangam.rest.modules.";
        Map<String, List<String>> x = new LinkedHashMap<>();
        x.put("ai", List.of(m + "ai.AiController", m + "ai.AiUiController"));                         // AiRestConfiguration
        x.put("billing", List.of(m + "billing.BillingController"));                                    // BillingRestConfiguration
        x.put("case", List.of(m + "cases.CaseFileController", m + "cases.MeetingController",
                m + "cases.ReferenceController"));                                                     // CaseRestConfiguration
        x.put("channels", List.of(m + "channels.ChannelsController"));                                 // ChannelsRestConfiguration
        x.put("compliance", List.of("org.sangam.rest.compliance.ComplianceController"));               // SangamRestAutoConfiguration
        x.put("field", List.of(m + "field.FieldWorkController"));                                      // FieldRestConfiguration
        x.put("geo", List.of(m + "geo.web.GeoController"));                                            // GeoRestConfiguration
        x.put("ledger", List.of(m + "ledger.LedgerController"));                                       // LedgerRestConfiguration
        x.put("programme", List.of(m + "programme.ProgrammeController"));                              // ProgrammeRestConfiguration
        x.put("registry", List.of(m + "registry.web.RegistryController"));                             // RegistryRestConfiguration
        x.put("resources", List.of(m + "resources.ResourcesController", m + "resources.QueueController",
                m + "resources.RosterController"));                                                    // ResourcesRestConfiguration
        return Map.copyOf(x);
    }
}
