// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackDeployer.PackLifecycleException;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackDescriptor.PackResource;
import org.sangam.packs.spi.PackOperationLock;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;
import org.sangam.rest.port.ApiProblem;

/**
 * SL-116: tenant provisioning renders a refused pack step like the pack lifecycle API: a held operation lock is 409
 * {@code PACK_OPERATION_IN_PROGRESS}, a rejected pack stays 422 {@code PACK_REJECTED}. Both refusals happen before the
 * deployer touches the engine, so no engine is needed.
 */
class PacksTenantDeployerTest {

    /** A pack whose process has no historyTimeToLive, so ModelGuard rejects it before any engine call. */
    static SangamPack pack(String id) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " targetNamespace=\"urn:sangam:test\"><process id=\"p116\" isExecutable=\"true\"><startEvent id=\"s\"/>"
                + "</process></definitions>";
        PackDescriptor d = new PackDescriptor(id, "1.0.0", Map.of("en", id), "test", "SL-116", List.of("p116"),
                List.of(new PackResource(PackResource.Kind.BPMN, "sl116/p116.bpmn")), null, null, null, List.of("SL-116"),
                null, List.of(), null);
        return new SangamPack() {
            @Override
            public PackDescriptor descriptor() {
                return d;
            }

            @Override
            public InputStream open(String p) {
                return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    static PackRegistry registry(SangamPack p) {
        PackRegistry r = PackRegistry.discover(new ClassLoader(null) { });
        r.add(p);
        return r;
    }

    static final PackOperationLock BUSY = new PackOperationLock() {
        @Override
        public <T> T exclusive(String tenantId, String packId, Supplier<T> work) {
            throw new PackLifecycleException(PACK_OPERATION_IN_PROGRESS, "busy", List.of(packId));
        }
    };

    @Test
    void aHeldOperationLockIs409InProgressWithItsBlockers() {
        PackRegistry reg = registry(pack("sl116.tenant"));
        PacksTenantDeployer t = new PacksTenantDeployer(new PackDeployer(null, reg, BUSY), reg, null);
        ApiProblem p = assertThrows(ApiProblem.class, () -> t.deploy("t1"));
        assertEquals(409, p.status());
        assertEquals(PackOperationLock.PACK_OPERATION_IN_PROGRESS, p.code());
        assertEquals(List.of("sl116.tenant"), p.extensions().get("blockers"));
    }

    @Test
    void aRejectedPackIsStill422PackRejected() {
        PackRegistry reg = registry(pack("sl116.bad"));
        PacksTenantDeployer t = new PacksTenantDeployer(new PackDeployer(null, reg, PackOperationLock.NONE), reg, null);
        ApiProblem p = assertThrows(ApiProblem.class, () -> t.deploy("t1"));
        assertEquals(422, p.status());
        assertEquals("PACK_REJECTED", p.code());
        assertTrue(p.extensions().containsKey("violations"), p.extensions().toString());
    }

    @Test
    void theMappingMatchesTheLifecycleApi() {
        ApiProblem notInstalled = PacksTenantDeployer.lifecycleProblem(
                new PackLifecycleException("PACK_NOT_INSTALLED", "x", List.of()));
        assertEquals(404, notInstalled.status());
        assertTrue(!notInstalled.extensions().containsKey("blockers"), "no empty blockers");
        assertEquals(409, PacksTenantDeployer.lifecycleProblem(
                new PackLifecycleException("PACK_HAS_DEPENDANTS", "x", List.of("a"))).status());
    }
}
