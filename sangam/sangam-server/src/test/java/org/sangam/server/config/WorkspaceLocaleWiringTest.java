// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.time.BusinessClock;
import org.sangam.rest.adapter.operaton.OperatonWorkspaceAdapter;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.sangam.rest.port.WorkspacePort;
import org.sangam.rest.support.RestProperties;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * M-045 wiring: the workspace port gets sangam-config's {@link ProfileResolver} when one is a bean, so {@code /me}
 * carries the tenant's resolved profile locale; without one (config module off) {@code /me} has no locale, as before.
 */
class WorkspaceLocaleWiringTest {

    static Object locales(WorkspacePort port) throws Exception {
        Field f = OperatonWorkspaceAdapter.class.getDeclaredField("locales");
        f.setAccessible(true);
        return f.get(assertInstanceOf(OperatonWorkspaceAdapter.class, port));
    }

    static WorkspacePort build(DefaultListableBeanFactory beans) {
        ProcessEngine engine = (ProcessEngine) Proxy.newProxyInstance(WorkspaceLocaleWiringTest.class.getClassLoader(),
                new Class<?>[] {ProcessEngine.class}, (p, m, a) -> null);
        return new SangamRestAutoConfiguration().sangamWorkspacePort(null, engine, null, null, null, new RestProperties(),
                null, beans.getBeanProvider(BusinessClock.class), beans);
    }

    @Test
    void theProfileResolverGivesMeItsLocale() throws Exception {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("sangamProfileResolver", Mockito.mock(ProfileResolver.class));
        assertNotNull(locales(build(beans)), "setProfiles was called: /me carries locale");
    }

    @Test
    void withoutAProfileResolverMeHasNoLocale() throws Exception {
        assertNull(locales(build(new DefaultListableBeanFactory())));
    }
}
