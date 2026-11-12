// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.sangam.compliance.breach.BreachIncidentService;
import org.sangam.compliance.breach.BreachNotificationPlanner;
import org.sangam.compliance.breach.IncidentReportingService;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * M-010c (SL-134): the seat routing of the compliance processes. The starters' role lists match the shipped BPMN
 * (packgen compliance.py), so a process never waits on a seat variable its starter does not bind; and
 * {@link ComplianceConfig} reads a seat only from {@code sangam.compliance.seats.<ROLE>}, with no default.
 */
class ComplianceSeatModelTest {

    static final String OPERATON = "http://operaton.org/schema/1.0/bpmn";
    static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** The candidate groups of every user task of {@code processKey}'s shipped model. */
    static Set<String> groups(String processKey) throws Exception {
        String path = "packs/compliance/" + processKey + ".bpmn";
        try (InputStream in = ComplianceSeatModelTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "no model " + path);
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            NodeList tasks = f.newDocumentBuilder().parse(in).getElementsByTagNameNS(BPMN, "userTask");
            Set<String> out = new TreeSet<>();
            for (int i = 0; i < tasks.getLength(); i++) {
                out.add(((Element) tasks.item(i)).getAttributeNS(OPERATON, "candidateGroups"));
            }
            return out;
        }
    }

    static Set<String> expressions(List<String> roles) {
        return roles.stream().map(r -> "${" + ComplianceConfig.seatVariable(r) + "}")
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Every compliance process and the roles its starter binds: all 12 models of the pack. */
    static Map<String, List<String>> starters() {
        Map<String, List<String>> starters = new LinkedHashMap<>(DataSubjectRightsService.SEAT_ROLES);
        starters.put("sangam-breach-incident", BreachIncidentService.SEAT_ROLES);
        starters.put("sangam-incident-report", IncidentReportingService.SEAT_ROLES);
        starters.put(BreachNotificationPlanner.PROCESS_KEY, BreachNotificationPlanner.SEAT_ROLES);
        return starters;
    }

    @Test
    void everyStarterBindsExactlyTheSeatsItsProcessRoutesTo() throws Exception {
        Map<String, List<String>> starters = starters();
        assertEquals(12, starters.size(), "all 12 compliance models are started with bound seats");
        for (Map.Entry<String, List<String>> e : starters.entrySet()) {
            assertEquals(expressions(e.getValue()), groups(e.getKey()), e.getKey());
        }
    }

    @Test
    void theRoutedModelsNameNoRoleCode() throws Exception {
        for (String key : starters().keySet()) {
            for (String g : groups(key)) {
                assertTrue(g.startsWith("${") && g.endsWith("Seat}"), key + ": " + g);
            }
        }
    }

    /**
     * Owner decision (a): only the registration of a new breach starts the plan process, so nothing else can start it
     * a second time for the same breach: the planner's start is not public.
     */
    @Test
    void thePlanProcessHasNoPublicStart() {
        boolean found = false;
        for (Method m : BreachNotificationPlanner.class.getDeclaredMethods()) {
            if (m.getName().equals("start")) {
                found = true;
                assertFalse(Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers()), m.toString());
            }
        }
        assertTrue(found, "BreachNotificationPlanner.start exists");
        assertEquals(Set.of("${privacyOfficerSeat}"), groupsUnchecked(BreachNotificationPlanner.PROCESS_KEY));
    }

    static Set<String> groupsUnchecked(String key) {
        try {
            return groups(key);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every right of every installed jurisdiction pack starts a process that has a seat mapping (else: refused). */
    @Test
    void everyRightProcessOfEveryPackHasASeatMapping() {
        JurisdictionRegistry packs = JurisdictionRegistry.discover();
        for (String code : List.of("IN", "EU-GDPR", "US-HIPAA")) {
            JurisdictionPack pack = packs.require(code);
            for (JurisdictionPack.Right r : pack.rights()) {
                assertTrue(DataSubjectRightsService.SEAT_ROLES.containsKey(r.processKey()),
                        code + " " + r.code() + " -> " + r.processKey());
            }
        }
    }

    @Test
    void seatVariableNames() {
        assertEquals("dpoSeat", ComplianceConfig.seatVariable("DPO"));
        assertEquals("cisoHeadSeat", ComplianceConfig.seatVariable("CISO-HEAD"));
        assertEquals("privacyOfficerSeat", ComplianceConfig.seatVariable("PRIVACY-OFFICER"));
        assertEquals("grievanceOfficerSeat", ComplianceConfig.seatVariable("GRIEVANCE-OFFICER"));
        assertThrows(IllegalArgumentException.class, () -> ComplianceConfig.seatVariable(" - "));
    }

    @Test
    void aSeatComesOnlyFromTheTenantsSeatsProperty() {
        JurisdictionPack pack = JurisdictionRegistry.discover().require("EU-GDPR");
        ComplianceConfig none = ComplianceConfig.of(pack, Map.of());
        assertTrue(none.seat("DPO").isEmpty(), "no pack, region or role default");
        ComplianceConfig cfg = ComplianceConfig.of(pack, Map.of("sangam.compliance.seats.DPO", " DPO-HQ ",
                "seats.cisO", "ignored-case-mismatch", "seats.ciso-head", "HEAD-1", "seats.RECORDS-OFFICER", " "));
        assertEquals("DPO-HQ", cfg.seat("DPO").orElseThrow());
        assertEquals("HEAD-1", cfg.seat("CISO-HEAD").orElseThrow(), "lower-case key (environment-variable binding)");
        assertTrue(cfg.seat("RECORDS-OFFICER").isEmpty(), "blank is not a seat");
        assertEquals(Map.of("dpoSeat", "DPO-HQ", "cisoHeadSeat", "HEAD-1"), cfg.seatVariables(List.of("DPO", "CISO-HEAD")));
    }

    @Test
    void everyMissingSeatIsNamedInOneRefusal() {
        JurisdictionPack pack = JurisdictionRegistry.discover().require("EU-GDPR");
        ComplianceConfig cfg = ComplianceConfig.of(pack, Map.of("seats.DPO", "DPO-HQ"));
        ComplianceException e = assertThrows(ComplianceException.class,
                () -> cfg.seatVariables(List.of("DPO", "CISO", "PRIVACY-OFFICER")));
        assertEquals(ComplianceException.Code.INVALID_STATE, e.code());
        assertTrue(e.getMessage().contains("sangam.compliance.seats.CISO"), e.getMessage());
        assertTrue(e.getMessage().contains("sangam.compliance.seats.PRIVACY-OFFICER"), e.getMessage());
        assertFalse(e.getMessage().contains("seats.DPO"), e.getMessage());
        ComplianceException one = assertThrows(ComplianceException.class, () -> cfg.requireSeat("CISO"));
        assertEquals(ComplianceException.Code.INVALID_STATE, one.code());
        assertEquals("DPO-HQ", cfg.requireSeat("DPO"));
    }
}
