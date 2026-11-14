// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.dmn.feel.impl.FeelEngine;

/**
 * Thin delegate kept for one release (integrator ruling, option A): the FEEL policies moved unchanged to
 * {@link org.sangam.feel.SangamFeel} in the module sangam-feel. Use that class; this one only forwards.
 *
 * @deprecated since 0.1.0, use {@link org.sangam.feel.SangamFeel}
 */
@Deprecated(since = "0.1.0")
public final class SangamFeel {

    private SangamFeel() {
    }

    /** @see org.sangam.feel.SangamFeel#dmnEngineConfiguration() */
    public static DefaultDmnEngineConfiguration dmnEngineConfiguration() {
        return org.sangam.feel.SangamFeel.dmnEngineConfiguration();
    }

    /** @see org.sangam.feel.SangamFeel#feelEngine() */
    public static FeelEngine feelEngine() {
        return org.sangam.feel.SangamFeel.feelEngine();
    }

    /** @see org.sangam.feel.SangamFeel#install(DefaultDmnEngineConfiguration) */
    public static DefaultDmnEngineConfiguration install(DefaultDmnEngineConfiguration dmn) {
        return org.sangam.feel.SangamFeel.install(dmn);
    }
}