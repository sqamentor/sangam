// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.packs.spi.ManifestPack;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.worker.PackWorker;
import org.sangam.packs.worker.PackWorkers;

/**
 * SL-104 (ADR-D-20): a pack's default connector bindings and its external-task topics are pack data in its manifest
 * ({@code connectors[].simulatorBinding}, {@code workers}), parsed by {@link ManifestPack} and registered when the pack
 * is discovered; product Java names no domain system.
 */
class PackDataBindingsTest {

    private static PackDescriptor manifest() {
        return ManifestPack.load(PackDataBindingsTest.class.getClassLoader(), "sl104/test-pack/pack.json").descriptor();
    }

    @Test
    void connectorBindingsAreParsedFromTheManifest() {
        Map<String, PackDescriptor.ConnectorUse> c = new java.util.HashMap<>();
        manifest().connectors().forEach(u -> c.put(u.id(), u));
        assertEquals(new PackDescriptor.SimulatorBinding("xa-pay-sim", "pay"), c.get("test.sl104.pay").simulatorBinding());
        assertEquals(new PackDescriptor.SimulatorBinding("xa-notify-sim", null), c.get("test.sl104.notify").simulatorBinding(),
                "no operation: the pack's own operation name");
        assertNull(c.get("test.sl104.plain").simulatorBinding(), "a connector without a binding has none");
        assertEquals("b", c.get("test.sl104.plain").capabilityClass());
    }

    @Test
    void workersAreParsedFromTheManifest() {
        assertEquals(List.of(new PackDescriptor.WorkerUse("test.sl104.paymentFile", "test.sl104.pay", "submitFile", "c")),
                manifest().workers());
    }

    @Test
    void aDiscoveredPackRegistersItsDeclaredWorkersOnce() {
        // PackRegistry.add calls this for every newly discovered pack; discovery can run more than once
        PackWorkers.registerDeclared(manifest());
        PackWorkers.registerDeclared(manifest()); // same declaration again: no duplicate, no error
        PackWorker w = PackWorkers.forTopic("test.sl104.paymentFile").orElseThrow();
        assertEquals("c", w.capabilityClass());
        assertEquals(1, PackWorkers.all().stream().filter(x -> x.topic().equals("test.sl104.paymentFile")).count());
    }

    @Test
    void aConflictingDeclarationOfATopicIsRefused() {
        PackWorkers.registerDeclared(pack("test.sl104.a", new PackDescriptor.WorkerUse("test.sl104.shared", "x.one", "op", "a")));
        PackWorkers.registerDeclared(pack("test.sl104.b", new PackDescriptor.WorkerUse("test.sl104.shared", "x.one", "op", "a")));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PackWorkers.registerDeclared(
                pack("test.sl104.c", new PackDescriptor.WorkerUse("test.sl104.shared", "x.two", "op", "a"))));
        assertTrue(e.getMessage().contains("test.sl104.c") && e.getMessage().contains("test.sl104.shared"), e.getMessage());
    }

    @Test
    void aPackCannotTakeOverABuiltInTopic() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PackWorkers.registerDeclared(
                pack("test.sl104.d", new PackDescriptor.WorkerUse("sangam.docs.bulk", "x.one", "op", "a"))));
        assertTrue(e.getMessage().contains("built-in"), e.getMessage());
    }

    @Test
    void declarationsWithoutRequiredFieldsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PackDescriptor.WorkerUse(" ", "c", "op", "a"));
        assertThrows(IllegalArgumentException.class, () -> new PackDescriptor.SimulatorBinding("", "op"));
        assertEquals("c", new PackDescriptor.WorkerUse("t", "c", "op", null).capabilityClass(), "class (c) by default");
    }

    private static PackDescriptor pack(String id, PackDescriptor.WorkerUse w) {
        return new PackDescriptor(id, "1.0.0", Map.of("en", id), "test", "", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(w));
    }
}