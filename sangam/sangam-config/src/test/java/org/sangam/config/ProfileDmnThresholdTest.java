// SPDX-License-Identifier: Apache-2.0
package org.sangam.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.sangam.config.profile.InMemoryProfileRepository;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileLoader;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.profile.ResolvedProfile;

/**
 * SL-102 (ADR-D-20 single source): every money threshold of the GOI rule tables equals its value in the GOI profile,
 * in minor units of the profile currency. packgen takes the pack DMN bands from the profilegen data (gov_ifms.py
 * {@code profile_major}) and profilegen writes the procurement-mode table and the profile rows from one table
 * (skeletons.py {@code GOI_PROC_LIMITS}); this test reads the shipped files, so a hand edit of either copy, or a
 * generator that stops sharing the value, fails here.
 */
class ProfileDmnThresholdTest {

    static final Path RES = Path.of("..", "sangam-packs", "src", "main", "resources");
    static final Path PROFILES = RES.resolve("profiles");
    static final Pattern BAND = Pattern.compile("^(<=|>)\\s*(-?\\d+)$");

    static ResolvedProfile goi;

    @BeforeAll
    static void resolve() throws Exception {
        LayerModel layers = LayerModel.from(ProfileLoader.parseTree(Files.readString(PROFILES.resolve("layers.yaml"))).get("layers"));
        goi = new ProfileResolver(InMemoryProfileRepository.active(ProfileLoader.loadDirectory(PROFILES)), layers)
                .resolve("GOI", LocalDate.of(2026, 11, 5));
    }

    /** The profile amount in minor units; the profile currency is the pack currency (INR packs, IN locale). */
    private static long profile(String path) {
        return goi.money(path).minor();
    }

    @Test
    void reappropriationRulesUseTheProfileLimits() throws Exception {
        List<Map<String, String>> rules = rules(RES.resolve("packs/ifms-reappropriation/ifms-reapp-rules.dmn"));
        int checked = 0;
        for (Map<String, String> r : rules) {
            String amount = r.get("amount");
            if (amount == null || amount.equals("-")) {
                continue;
            }
            String group = r.get("objectGroup");
            String path = group.equals("-") ? "budgetControl.reappropriation.parliamentReport.amountAbove"
                    : "budgetControl.reappropriation.objectGroupLimits." + group.replace("\"", "");
            assertEquals(">", op(amount), path);
            assertEquals(profile(path), value(amount), path);
            checked++;
        }
        assertEquals(4, checked, "three object-group limits and the Parliament report amount");
    }

    @Test
    void appraisalLadderIsTheProfileLadder() throws Exception {
        List<Map<String, String>> rules = rules(RES.resolve("packs/ifms-scheme-appraisal/ifms-scheme-appraisal.dmn"));
        List<Map<String, Object>> ladder = goi.list("appraisal.ladder");
        assertEquals(ladder.size(), rules.size());
        for (int i = 0; i < ladder.size(); i++) {
            Map<String, String> r = rules.get(i);
            String band = r.get("totalOutlay");
            if (ladder.get(i).get("upTo") == null) {
                assertEquals("-", band, "open top band");
            } else {
                assertEquals("<=", op(band));
                assertEquals(profile("appraisal.ladder." + i + ".upTo"), value(band), "band " + i);
            }
            assertEquals("\"" + ladder.get(i).get("appraisal") + "\"", r.get("appraisalForum"), "forum " + i);
            assertEquals("\"" + ladder.get(i).get("approval") + "\"", r.get("approvalAuthority"), "authority " + i);
        }
        long secretary = profile("appraisal.ladder.0.upTo");
        Map<String, String> approval = rules(RES.resolve("packs/ifms-scheme-appraisal/ifms-scheme-approval.dmn")).get(0);
        assertEquals("<=", op(approval.get("amount")));
        assertEquals(secretary, value(approval.get("amount")), "ifms-scheme-approval: the Secretary band");
        Map<String, String> caa = rules(RES.resolve("packs/ifms-scheme-appraisal/ifms-scheme-powers.dmn")).stream()
                .filter(r -> "\"CAA\"".equals(r.get("seatRole"))).findFirst().orElseThrow();
        assertEquals(secretary, Long.parseLong(caa.get("powerLimit")), "ifms-scheme-powers: CAA power limit");
    }

    @Test
    void procurementModeTableIsTheProfileProcurementThresholds() throws Exception {
        List<Map<String, String>> rules = rules(PROFILES.resolve("in/dmn/GOI/ifms-procurement-mode.dmn"));
        List<String> keys = List.of("direct", "gemL1", "limitedTender", "limitedTenderScientific");
        for (int i = 0; i < keys.size(); i++) {
            String band = rules.get(i).get("amount");
            assertEquals("<=", op(band), keys.get(i));
            assertEquals(profile("delegation.rows.procurementThresholds." + keys.get(i)), value(band), keys.get(i));
        }
        assertEquals("-", rules.get(keys.size()).get("amount"), "open tender above every band");
    }

    private static String op(String entry) {
        Matcher m = BAND.matcher(entry.strip());
        if (!m.matches()) {
            throw new AssertionError("not a single money band: '" + entry + "'");
        }
        return m.group(1);
    }

    private static long value(String entry) {
        Matcher m = BAND.matcher(entry.strip());
        if (!m.matches()) {
            throw new AssertionError("not a single money band: '" + entry + "'");
        }
        return Long.parseLong(m.group(2));
    }

    /** Each rule as {input expression or output name: entry text}, in rule order. */
    private static List<Map<String, String>> rules(Path dmn) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document d = f.newDocumentBuilder().parse(dmn.toFile());
        List<String> ins = new ArrayList<>();
        NodeList inputs = d.getElementsByTagNameNS("*", "inputExpression");
        for (int i = 0; i < inputs.getLength(); i++) {
            ins.add(inputs.item(i).getTextContent().strip());
        }
        List<String> outs = new ArrayList<>();
        NodeList outputs = d.getElementsByTagNameNS("*", "output");
        for (int i = 0; i < outputs.getLength(); i++) {
            outs.add(((Element) outputs.item(i)).getAttribute("name"));
        }
        List<Map<String, String>> out = new ArrayList<>();
        NodeList rules = d.getElementsByTagNameNS("*", "rule");
        for (int i = 0; i < rules.getLength(); i++) {
            Element r = (Element) rules.item(i);
            Map<String, String> m = new LinkedHashMap<>();
            NodeList ie = r.getElementsByTagNameNS("*", "inputEntry");
            for (int j = 0; j < ie.getLength(); j++) {
                m.put(ins.get(j), ie.item(j).getTextContent().strip());
            }
            NodeList oe = r.getElementsByTagNameNS("*", "outputEntry");
            for (int j = 0; j < oe.getLength(); j++) {
                m.put(outs.get(j), oe.item(j).getTextContent().strip());
            }
            out.add(m);
        }
        return out;
    }
}
