// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.SangamPack;

/** SL-104 (ADR-D-20): the default pack connector bindings come from pack data; site configuration overrides them. */
class PackConnectorBindingsPackDataTest {

    private static SangamPack pack(String id, PackDescriptor.ConnectorUse... uses) {
        PackDescriptor d = new PackDescriptor(id, "1.0.0", Map.of("en", id), "test", "", List.of(), List.of(), List.of(),
                List.of(), List.of(uses), List.of(), List.of(), List.of(), Map.of());
        return () -> d;
    }

    private static PackDescriptor.ConnectorUse use(String id, String cls, String connector, String operation) {
        return new PackDescriptor.ConnectorUse(id, cls, "", "",
                connector == null ? null : new PackDescriptor.SimulatorBinding(connector, operation));
    }

    @Test
    void packDeclaredBindingsBecomeTheDefaults() {
        PackConnectorBindings b = PackConnectorBindings.fromPackData(List.of(
                pack("p1", use("x.pay", "c", "xa-pay-sim", "pay"), use("x.plain", "a", null, null)),
                pack("p2", use("x.pay", "c", "xa-pay-sim", "pay"))));
        PackConnectorBindings.Binding pay = b.binding("x.pay").orElseThrow();
        assertEquals("xa-pay-sim", pay.connectorId());
        assertEquals("pay", pay.operation());
        assertEquals(Optional.empty(), b.binding("x.plain"), "no pack data, no default");
        assertEquals(CapabilityClass.C_NEITHER, b.declaredClass("x.pay"));
    }

    @Test
    void siteConfigurationOverridesThePackDefault() {
        PackConnectorBindings b = PackConnectorBindings.fromPackData(List.of(pack("p1", use("x.pay", "c", "xa-pay-sim", "pay"))));
        b.bind("x.pay", "site-pay", "submit"); // what IntegrationWiring does for sangam.packs.bindings.x.pay
        assertEquals("site-pay", b.binding("x.pay").orElseThrow().connectorId());
        assertEquals("submit", b.binding("x.pay").orElseThrow().operation());
    }

    @Test
    void conflictingPackBindingsAreRefusedNamingBothPacks() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PackConnectorBindings.fromPackData(List.of(
                pack("p1", use("x.pay", "c", "xa-pay-sim", "pay")),
                pack("p2", use("x.pay", "c", "xb-pay-sim", "pay")))));
        assertTrue(e.getMessage().contains("p1@1.0.0") && e.getMessage().contains("p2@1.0.0"), e.getMessage());
    }
}