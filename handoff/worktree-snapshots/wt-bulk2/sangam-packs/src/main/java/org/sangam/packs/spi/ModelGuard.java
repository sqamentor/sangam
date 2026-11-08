// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.BaseElement;
import org.operaton.bpm.model.bpmn.instance.ExtensionElements;
import org.operaton.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.operaton.bpm.model.bpmn.instance.Process;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperties;
import org.operaton.bpm.model.bpmn.instance.operaton.OperatonProperty;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;

/**
 * Deploy-time checks for pack BPMN (ARCHITECTURE.md section 1.6, GP-09):
 * <ul>
 *   <li>every multi-instance activity must declare {@code sangam.maxCardinality} (an {@code operaton:property}) no
 *       larger than {@link #MAX_MULTI_INSTANCE}; large sets use one instance per item plus aggregation
 *       ({@link org.sangam.packs.core.BatchDelegate}), because multi-instance cost grows with n squared;</li>
 *   <li>every executable process must set {@code historyTimeToLive} (Operaton 2.2 rejects deployments without it
 *       when enforcement is on; government retention is governed by R61, not by the engine TTL).</li>
 * </ul>
 */
public final class ModelGuard {

    /** Largest multi-instance cardinality a pack may declare (committees, parallel comments, joint approvals). */
    public static final int MAX_MULTI_INSTANCE = 50;

    private ModelGuard() {
    }

    /** Returns the violations found in one BPMN file (empty when the model is acceptable). */
    public static List<String> check(String resourceName, byte[] bpmn) {
        List<String> out = new ArrayList<>();
        BpmnModelInstance model = Bpmn.readModelFromStream(new ByteArrayInputStream(bpmn));
        for (MultiInstanceLoopCharacteristics mi : model.getModelElementsByType(MultiInstanceLoopCharacteristics.class)) {
            ModelElementInstance parent = mi.getParentElement();
            String id = parent instanceof BaseElement b ? b.getId() : "?";
            Integer max = declaredMax(parent);
            if (max == null) {
                out.add(resourceName + ": multi-instance activity " + id
                        + " has no sangam.maxCardinality (GP-09: use one instance per item plus aggregation)");
            } else if (max > MAX_MULTI_INSTANCE) {
                out.add(resourceName + ": multi-instance activity " + id + " declares sangam.maxCardinality " + max
                        + " > " + MAX_MULTI_INSTANCE + " (GP-09)");
            }
        }
        for (Process p : model.getModelElementsByType(Process.class)) {
            if (p.isExecutable() && p.getOperatonHistoryTimeToLiveString() == null) {
                out.add(resourceName + ": process " + p.getId() + " has no historyTimeToLive");
            }
        }
        return out;
    }

    private static Integer declaredMax(ModelElementInstance activity) {
        if (!(activity instanceof BaseElement b)) {
            return null;
        }
        ExtensionElements ext = b.getExtensionElements();
        if (ext == null) {
            return null;
        }
        for (OperatonProperties props : ext.getElementsQuery().filterByType(OperatonProperties.class).list()) {
            for (OperatonProperty p : props.getOperatonProperties()) {
                if ("sangam.maxCardinality".equals(p.getOperatonName())) {
                    return Integer.parseInt(p.getOperatonValue().trim());
                }
            }
        }
        return null;
    }
}
