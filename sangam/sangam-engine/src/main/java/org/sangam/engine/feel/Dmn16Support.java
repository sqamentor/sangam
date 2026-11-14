// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.model.dmn.DmnModelInstance;

/**
 * Thin delegate kept for one release (integrator ruling, option A): DMN 1.6 support (decision 158) moved unchanged to
 * {@link org.sangam.feel.Dmn16Support} in the module sangam-feel. Use that class; this one only forwards. Refusals are
 * {@link org.sangam.feel.Dmn16Support.Dmn16Exception}.
 *
 * @deprecated since 0.1.0, use {@link org.sangam.feel.Dmn16Support}
 */
@Deprecated(since = "0.1.0")
public final class Dmn16Support {

    public static final String DMN15_NS = org.sangam.feel.Dmn16Support.DMN15_NS;
    public static final String DMN16_NS = org.sangam.feel.Dmn16Support.DMN16_NS;
    public static final String FEEL15_NS = org.sangam.feel.Dmn16Support.FEEL15_NS;
    public static final String FEEL16_NS = org.sangam.feel.Dmn16Support.FEEL16_NS;
    public static final String BFEEL15_NS = org.sangam.feel.Dmn16Support.BFEEL15_NS;
    public static final String BFEEL16_NS = org.sangam.feel.Dmn16Support.BFEEL16_NS;
    public static final String CODE_ONNX = org.sangam.feel.Dmn16Support.CODE_ONNX;
    public static final String CODE_BFEEL = org.sangam.feel.Dmn16Support.CODE_BFEEL;
    public static final String CODE_UNREADABLE = org.sangam.feel.Dmn16Support.CODE_UNREADABLE;

    private Dmn16Support() {
    }

    public static DefaultDmnEngineConfiguration install(DefaultDmnEngineConfiguration dmn) {
        return org.sangam.feel.Dmn16Support.install(dmn);
    }

    public static boolean isDmn16(byte[] xml) {
        return org.sangam.feel.Dmn16Support.isDmn16(xml);
    }

    public static byte[] toParserInput(byte[] xml) {
        return org.sangam.feel.Dmn16Support.toParserInput(xml);
    }

    public static DmnModelInstance readModel(byte[] xml) {
        return org.sangam.feel.Dmn16Support.readModel(xml);
    }
}