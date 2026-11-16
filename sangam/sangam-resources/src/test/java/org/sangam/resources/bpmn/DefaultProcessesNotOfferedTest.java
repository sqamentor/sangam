// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * UI-DEMO-7 (the leave-credit batch child in the start catalogue), the same defect in this module: the default
 * booking-approval and roster-publish models are started only by BookingService and RosterService, which pass the
 * variables they need (approvalTimeout, approverSeatId, bookingId, periodId, ...). They have no start form, so offered
 * in the start catalogue every start failed ("Cannot resolve identifier 'approvalTimeout'", 422 on the demo server).
 * Every default model of this module is therefore marked not startable in the tasklist and declares no start form.
 */
class DefaultProcessesNotOfferedTest {

    static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final String OPERATON = "http://operaton.org/schema/1.0/bpmn";

    @Test
    void everyDefaultModelIsServiceStartedAndNotOfferedInTheTasklist() throws Exception {
        assertEquals(2, ResourcesProcesses.RESOURCES.size(), "a new default model must be checked here as well");
        for (String r : ResourcesProcesses.RESOURCES) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(r)) {
                assertNotNull(in, r);
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                Document d = f.newDocumentBuilder().parse(in);
                NodeList processes = d.getElementsByTagNameNS(BPMN, "process");
                assertEquals(1, processes.getLength(), r);
                Element p = (Element) processes.item(0);
                assertEquals("false", p.getAttributeNS(OPERATON, "isStartableInTasklist"),
                        r + ": started by its service only, never from the start catalogue");
                NodeList starts = p.getElementsByTagNameNS(BPMN, "startEvent");
                for (int i = 0; i < starts.getLength(); i++) {
                    assertFalse(((Element) starts.item(i)).hasAttributeNS(OPERATON, "formKey"), r + " declares no start form");
                }
            }
        }
    }
}
