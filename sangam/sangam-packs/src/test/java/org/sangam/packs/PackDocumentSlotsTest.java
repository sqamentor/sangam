// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.CallActivity;
import org.operaton.bpm.model.bpmn.instance.Process;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonField;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonIn;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonTaskListener;

import org.sangam.packs.core.DscSignatureGuardListener;
import org.sangam.packs.spi.ManifestPack;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackDescriptor.DocumentSlot;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.packs.spi.SangamPack;

/**
 * Decision 269: each pack declares its DSC documentType-to-slot pairs in its manifest ({@code documentSlots}); the server
 * reads them, there is no server table. The core pack publishes NOTING = notingDocumentId and SANCTION_NOTE =
 * sanctionDocumentId, and every pack's manifest pairs are exactly those of its own DSC gates and (decision 382) of every
 * DSC gate its processes reach through call activities that pass {@code in variables="all"} (decision 385 (1): only those
 * copy the slot), so the slot is filled on the calling instance.
 */
class PackDocumentSlotsTest {

    @Test
    void theCorePackPublishesTheNotingAndSanctionNoteSlots() {
        PackDescriptor core = PackRegistry.discover().find("sangam.core").orElseThrow().descriptor();
        assertEquals(List.of(new DocumentSlot("NOTING", "notingDocumentId"),
                new DocumentSlot("SANCTION_NOTE", "sanctionDocumentId")), core.documentSlots());
        assertEquals(Optional.of("notingDocumentId"), core.slotFor("NOTING"));
        assertEquals(Optional.of("sanctionDocumentId"), core.slotFor("SANCTION_NOTE"));
        assertEquals(Optional.empty(), core.slotFor("OTHER"));
    }

    @Test
    void everyPacksManifestPairsAreExactlyThoseOfItsOwnAndItsCalledDscGates() throws Exception {
        // read from the deployed models, independent of packgen: each process's own gates and its call activities
        Map<String, Set<DocumentSlot>> own = new HashMap<>();
        Map<String, Set<String>> calls = new HashMap<>();
        Map<SangamPack, List<String>> processes = new LinkedHashMap<>();
        for (SangamPack p : PackRegistry.discover().all()) {
            List<String> ids = new ArrayList<>();
            for (PackDescriptor.PackResource r : p.descriptor().resources(PackDescriptor.PackResource.Kind.BPMN)) {
                try (InputStream in = p.open(r.path())) {
                    BpmnModelInstance m = Bpmn.readModelFromStream(in);
                    for (Process proc : m.getModelElementsByType(Process.class)) {
                        ids.add(proc.getId());
                        own.put(proc.getId(), gatesOf(m));
                        Set<String> called = new TreeSet<>();
                        for (CallActivity c : m.getModelElementsByType(CallActivity.class)) {
                            // decision 385 (1): only a call that passes in variables="all" carries the slot
                            if (passesAll(c)) {
                                called.add(c.getCalledElement());
                            }
                        }
                        calls.put(proc.getId(), called);
                    }
                }
            }
            processes.put(p, ids);
        }
        int gated = 0;
        int byCall = 0;
        for (Map.Entry<SangamPack, List<String>> e : processes.entrySet()) {
            Set<DocumentSlot> expected = new LinkedHashSet<>();
            Set<DocumentSlot> ownOnly = new LinkedHashSet<>();
            for (String pid : e.getValue()) {
                ownOnly.addAll(own.get(pid));
                reach(pid, own, calls, new HashSet<>(), expected);
            }
            gated += ownOnly.isEmpty() ? 0 : 1;
            byCall += expected.size() > ownOnly.size() ? 1 : 0;
            assertEquals(expected, new LinkedHashSet<>(e.getKey().descriptor().documentSlots()),
                    e.getKey().descriptor().id() + ": manifest documentSlots must equal its own and its called DSC gates");
        }
        assertTrue(gated >= 1, "at least the core pack has DSC gates");
        assertTrue(byCall >= 1, "at least one pack reaches a DSC gate only through a call");
    }

    @Test
    void aPackThatCallsTheCoreSanctionDeclaresTheSanctionNoteSlot() {
        // decision 382: a SANCTION_NOTE uploaded on the audit-para instance fills the slot that its call into
        // sangam-core-sanction (sn_sanction) copies to the child, so the gate captures a filled slot at create
        PackDescriptor auditPara = PackRegistry.discover().find("gov.audit-para").orElseThrow().descriptor();
        assertEquals(Optional.of("sanctionDocumentId"), auditPara.slotFor("SANCTION_NOTE"));
    }

    private static void reach(String pid, Map<String, Set<DocumentSlot>> own, Map<String, Set<String>> calls,
            Set<String> seen, Set<DocumentSlot> out) {
        if (!seen.add(pid)) {
            return;
        }
        assertTrue(own.containsKey(pid), "called process " + pid + " is in no pack");
        out.addAll(own.get(pid));
        for (String c : calls.get(pid)) {
            reach(c, own, calls, seen, out);
        }
    }

    static boolean passesAll(CallActivity c) {
        return c.getExtensionElements() != null && c.getExtensionElements().getElementsQuery()
                .filterByType(OperatonIn.class).list().stream().anyMatch(i -> "all".equals(i.getOperatonVariables()));
    }

    @Test
    void everyCallIntoADscGateProcessPassesAllVariables() throws Exception {
        // decision 385 (1): a call into a process that holds a DSC gate copies the slot filled on the caller
        Set<String> gated = new TreeSet<>();
        List<CallActivity> calls = new ArrayList<>();
        for (SangamPack p : PackRegistry.discover().all()) {
            for (PackDescriptor.PackResource r : p.descriptor().resources(PackDescriptor.PackResource.Kind.BPMN)) {
                try (InputStream in = p.open(r.path())) {
                    BpmnModelInstance m = Bpmn.readModelFromStream(in);
                    if (!gatesOf(m).isEmpty()) {
                        m.getModelElementsByType(Process.class).forEach(x -> gated.add(x.getId()));
                    }
                    calls.addAll(m.getModelElementsByType(CallActivity.class));
                }
            }
        }
        int intoGates = 0;
        for (CallActivity c : calls) {
            if (gated.contains(c.getCalledElement())) {
                intoGates++;
                assertTrue(passesAll(c), c.getId() + " calls the DSC gate process " + c.getCalledElement()
                        + " without in variables=\"all\"");
            }
        }
        assertTrue(intoGates >= 50, "the packs call the core DSC gates: " + intoGates);
    }

    private static Set<DocumentSlot> gatesOf(BpmnModelInstance m) {
        Set<DocumentSlot> gates = new LinkedHashSet<>();
        for (OperatonTaskListener tl : m.getModelElementsByType(OperatonTaskListener.class)) {
            if (!DscSignatureGuardListener.class.getName().equals(tl.getOperatonClass())
                    || !"create".equals(tl.getOperatonEvent())) {
                continue;
            }
            String type = null;
            String slot = null;
            for (OperatonField f : tl.getOperatonFields()) {
                if ("documentType".equals(f.getOperatonName())) {
                    type = f.getOperatonStringValue() != null ? f.getOperatonStringValue()
                            : f.getOperatonString().getTextContent();
                } else if ("documentSlot".equals(f.getOperatonName())) {
                    slot = f.getOperatonStringValue() != null ? f.getOperatonStringValue()
                            : f.getOperatonString().getTextContent();
                }
            }
            gates.add(new DocumentSlot(type.trim(), slot.trim()));
        }
        return gates;
    }

    @Test
    void aRepeatedTypeOrSlotOrAMalformedEntryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> load(
                "[{\"documentType\":\"NOTING\",\"slot\":\"a\"},{\"documentType\":\"NOTING\",\"slot\":\"b\"}]"));
        assertThrows(IllegalArgumentException.class, () -> load(
                "[{\"documentType\":\"NOTING\",\"slot\":\"a\"},{\"documentType\":\"SANCTION_NOTE\",\"slot\":\"a\"}]"));
        assertThrows(IllegalArgumentException.class, () -> load("[{\"documentType\":\"NOTING\"}]"));
        assertEquals(List.of(), load("[]").documentSlots());
        assertEquals(List.of(), new PackDescriptor("t", "1.0.0", Map.of("en", "t"), "test", "", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), List.of())
                .documentSlots(), "the constructor without slots declares none");
    }

    private static PackDescriptor load(String slots) {
        byte[] manifest = ("{\"id\":\"t.pack\",\"version\":\"1.0.0\",\"documentSlots\":" + slots + "}")
                .getBytes(StandardCharsets.UTF_8);
        ClassLoader cl = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return "t/pack.json".equals(name) ? new ByteArrayInputStream(manifest) : null;
            }
        };
        return ManifestPack.load(cl, "t/pack.json").descriptor();
    }
}
