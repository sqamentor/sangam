// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.FormPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.PortalPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * SL-166 f2 / owner decision 63: {@code GET /portal/services/{key}/form} serves, under {@code portal.use}, the merged form
 * ({@link FormPort#effective}) of a service in the caller's own catalogue and nothing else: an unknown service, a service
 * without a form, or a caller without portal.use never reaches the form port.
 */
class PortalServiceFormTest {

    static final RequestContext APPLICANT = new RequestContext("T1", "citizen-1", Set.of("portal"), null, null, null, null,
            null, "token");
    static final FormPort.FormVersion MERGED = new FormPort.FormVersion("birth-cert", "v1", "s", "l", "p",
            Map.of("type", "object", "properties", Map.of("ward", Map.of("type", "string"))), null, null, Map.of(), "d1", "ACTIVE");

    /** Every form-port call, as "method:formKey". */
    final List<String> formCalls = new CopyOnWriteArrayList<>();

    final PortalPort portal = (PortalPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PortalPort.class},
            (proxy, method, args) -> {
                if (method.getName().equals("services")) {
                    return List.of(
                            new PortalPort.PortalService("birth", "Birth certificate", null, null, null, 7, null, "birth-cert", List.of()),
                            new PortalPort.PortalService("info", "Information only", null, null, null, null, null, null, List.of()));
                }
                throw new UnsupportedOperationException(method.getName());
            });

    final FormPort forms = (FormPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {FormPort.class},
            (proxy, method, args) -> {
                formCalls.add(method.getName() + ":" + args[1]);
                if (method.getName().equals("effective")) {
                    return MERGED;
                }
                throw new UnsupportedOperationException(method.getName());
            });

    static PermissionPort permissions(boolean portalUse) {
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return portalUse && Permissions.PORTAL_USE.equals(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return portalUse ? Set.of(Permissions.PORTAL_USE) : Set.of();
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
    }

    PortalController controller(boolean portalUse) {
        return new PortalController(portal, permissions(portalUse),
                new StaticListableBeanFactory(Map.of("forms", forms)).getBeanProvider(FormPort.class));
    }

    @Test
    void theServiceFormIsTheMergedFormOfItsFormKey() {
        assertSame(MERGED, controller(true).serviceForm(APPLICANT, "birth"));
        assertEquals(List.of("effective:birth-cert"), formCalls, "the merged form, never the raw get");
    }

    @Test
    void onlyAServiceOfTheCatalogueWithAFormIsServed() {
        PortalController c = controller(true);
        assertEquals(404, assertThrows(ApiProblem.class, () -> c.serviceForm(APPLICANT, "birth-cert")).status(),
                "a form key is not a service key: no arbitrary form read");
        assertEquals(404, assertThrows(ApiProblem.class, () -> c.serviceForm(APPLICANT, "info")).status(), "no form");
        assertEquals(List.of(), formCalls);
    }

    @Test
    void withoutPortalUseNothingIsRead() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> controller(false).serviceForm(APPLICANT, "birth"));
        assertEquals(403, p.status());
        assertEquals(List.of(), formCalls);
    }
}
