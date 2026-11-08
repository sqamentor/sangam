// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.adhoc;

import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParser;
import org.operaton.bpm.engine.impl.cfg.BpmnParseFactory;

/** Installs {@link SangamBpmnParse} (patch P-1) through the public configuration setter {@code setBpmnParseFactory}. */
public class SangamBpmnParseFactory implements BpmnParseFactory {

    @Override
    public BpmnParse createBpmnParse(BpmnParser bpmnParser) {
        return new SangamBpmnParse(bpmnParser);
    }
}
