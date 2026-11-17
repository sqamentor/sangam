// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.FormPort;
import org.sangam.rest.port.PortalCompliancePort;
import org.sangam.rest.port.PortalPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Owner decisions 339 and 341 at the portal endpoint: a submission to a service that names a consent notice records the
 * applicant's consent (through the data-protection module) and starts the application in ONE transaction, the
 * recorded consent travelling with the application; a refused consent starts nothing. A draft is unchanged: it takes no
 * consent and reaches no consent recording. A consent sent to a service that takes none, a draft carrying a consent,
 * and a noticed service without the module are refused by name.
 */
class PortalConsentSubmissionTest {

    static final RequestContext APPLICANT = new RequestContext("T1", "citizen-1", Set.of("portal"), null, null, null, null,
            null, "token");
    static final PortalPort.PortalConsent CONSENT = new PortalPort.PortalConsent("svc-notice", 3, "en", Set.of("P_APPLY"),
            "ADULT", null);
    static final PortalPort.ConsentRecord RECORDED = new PortalPort.ConsentRecord("consent-9", "ADULT", null);
    static final PortalPort.Application APPLICATION = new PortalPort.Application("pi-1", "BIRTH-2026-000001", "birth",
            "Birth certificate", null, null, null, null, null, "SUBMITTED", List.of(), List.of(), null, null, "NOT_REQUIRED");

    /** Calls in order: "tx:begin", "consent:...", "apply:...", "tx:commit" / "tx:rollback". */
    final List<String> calls = new CopyOnWriteArrayList<>();
    int depth;
    RuntimeException consentRefusal;

    final TxSupport tx = new TxSupport(null, null) {
        @Override
        public <T> T inTxWithStatus(Function<TxStatus, T> work) {
            calls.add("tx:begin");
            depth++;
            try {
                T r = work.apply(null);
                calls.add("tx:commit");
                return r;
            } catch (RuntimeException e) {
                calls.add("tx:rollback");
                throw e;
            } finally {
                depth--;
            }
        }

        @Override
        public <T> T inReadTx(org.sangam.core.api.ReadSupplier<T> work) {
            calls.add("tx:begin");
            depth++;
            try {
                T r = work.read();
                calls.add("tx:commit");
                return r;
            } catch (RuntimeException e) {
                calls.add("tx:rollback");
                throw e;
            } finally {
                depth--;
            }
        }

        @Override
        public <T> T withReadConnection(org.sangam.core.api.ReadWork<T> work) {
            calls.add("tx:begin");
            depth++;
            try {
                T r = work.read(connection());
                calls.add("tx:commit");
                return r;
            } catch (RuntimeException e) {
                calls.add("tx:rollback");
                throw e;
            } finally {
                depth--;
            }
        }

        @Override
        public <T> T inNewTx(Supplier<T> work) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T inNewReadTx(org.sangam.core.api.ReadWork<T> work) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection connection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void afterCommit(Runnable work) {
            throw new UnsupportedOperationException();
        }
    };

    final PortalPort portal = (PortalPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PortalPort.class},
            (proxy, m, args) -> switch (m.getName()) {
                case "services" -> List.of(
                        new PortalPort.PortalService("birth", "Birth certificate", null, null, null, 7, null, null, List.of(),
                                "svc-notice"),
                        new PortalPort.PortalService("info", "Information only", null, null, null, null, null, null, List.of(),
                                null));
                case "apply" -> {
                    PortalPort.ApplyRequest r = (PortalPort.ApplyRequest) args[1];
                    Object consent = args.length > 2 ? args[2] : "none";
                    calls.add("apply:" + r.serviceKey() + ":" + r.submit() + ":" + consent + ":tx=" + depth
                            + ":requestConsent=" + r.consent());
                    yield APPLICATION;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });

    final PortalCompliancePort compliance = (PortalCompliancePort) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {PortalCompliancePort.class}, (proxy, m, args) -> {
                if (m.getName().equals("recordConsent")) {
                    calls.add("consent:" + args[1] + ":" + args[2] + ":tx=" + depth);
                    if (consentRefusal != null) {
                        throw consentRefusal;
                    }
                    return RECORDED;
                }
                if (m.getName().equals("notifications")) {
                    return List.of();
                }
                throw new UnsupportedOperationException(m.getName());
            });

    @BeforeEach
    void reset() {
        calls.clear();
        consentRefusal = null;
    }

    PortalController controller(boolean withCompliance) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(withCompliance
                ? Map.of("compliance", compliance) : Map.of());
        return new PortalController(portal, PortalServiceFormTest.permissions(true), beans.getBeanProvider(FormPort.class),
                beans.getBeanProvider(PortalCompliancePort.class), tx);
    }

    @Test
    void aSubmissionRecordsTheConsentAndStartsTheApplicationInOneTransaction() {
        var r = controller(true).apply(APPLICANT, new PortalPort.ApplyRequest("birth", Map.of(), true, CONSENT));
        assertSame(APPLICATION, r.getBody());
        assertEquals(List.of("tx:begin", "consent:birth:svc-notice:tx=1", "apply:birth:true:" + RECORDED + ":tx=1:requestConsent=null",
                "tx:commit"), calls, "the consent the server recorded, never the request's, goes with the application");
    }

    @Test
    void aRefusedConsentStartsNothing() {
        consentRefusal = ApiProblem.conflict("NOTICE_NOT_CURRENT", "the current published version is 4");
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> controller(true).apply(APPLICANT, new PortalPort.ApplyRequest("birth", Map.of(), true, CONSENT)));
        assertEquals("NOTICE_NOT_CURRENT", p.code());
        assertEquals(List.of("tx:begin", "consent:birth:svc-notice:tx=1", "tx:rollback"), calls);
    }

    @Test
    void aSubmissionWithoutConsentStillReachesTheModuleWhichRefusesIt() {
        consentRefusal = ApiProblem.forbidden("CONSENT_REQUIRED", "none was given");
        assertEquals("CONSENT_REQUIRED", assertThrows(ApiProblem.class,
                () -> controller(true).apply(APPLICANT, new PortalPort.ApplyRequest("birth", Map.of(), true, null))).code());
        assertEquals(List.of("tx:begin", "consent:birth:svc-notice:tx=1", "tx:rollback"), calls, "never a start without consent");
    }

    @Test
    void aDraftIsUnchangedAndTakesNoConsent() {
        controller(true).apply(APPLICANT, new PortalPort.ApplyRequest("birth", Map.of(), false, null));
        assertEquals(List.of("apply:birth:false:none:tx=0:requestConsent=null"), calls,
                "a draft is saved as before (decision 341: whether a draft may precede consent is open for counsel)");
        calls.clear();
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> controller(true).apply(APPLICANT, new PortalPort.ApplyRequest("birth", Map.of(), false, CONSENT)));
        assertEquals(400, p.status());
        assertEquals(List.of(), calls);
    }

    @Test
    void aServiceWithoutANoticeTakesNoConsent() {
        controller(true).apply(APPLICANT, new PortalPort.ApplyRequest("info", Map.of(), true, null));
        assertEquals(List.of("apply:info:true:none:tx=0:requestConsent=null"), calls);
        calls.clear();
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> controller(true).apply(APPLICANT, new PortalPort.ApplyRequest("info", Map.of(), true, CONSENT)));
        assertEquals(409, p.status());
        assertEquals("NOTICE_NOT_CURRENT", p.code());
        assertEquals(List.of(), calls);
    }

    @Test
    void aNoticedServiceWithoutTheModuleIsRefusedAndStartsNothing() {
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> controller(false).apply(APPLICANT, new PortalPort.ApplyRequest("birth", Map.of(), true, CONSENT)));
        assertEquals(ApiProblem.notInstalled("x", "y").status(), p.status());
        assertEquals(List.of(), calls);
    }

    @Test
    void theNoticeEndpointServesOnlyANoticedServiceAndNotificationsNeedNoModule() {
        assertEquals(404, assertThrows(ApiProblem.class, () -> controller(true).serviceNotice(APPLICANT, "info", null)).status());
        assertEquals(404, assertThrows(ApiProblem.class, () -> controller(true).serviceNotice(APPLICANT, "nope", null)).status());
        assertEquals(List.of(), controller(false).notifications(APPLICANT).getBody());
    }
}
