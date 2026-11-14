// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.regex.Pattern;

/**
 * The one reading of a module switch {@code sangam.modules.<module>.enabled} (design note section 2.3: the host-neutral
 * form of {@code @ConditionalOnProperty}). The decision and its default live here once; a host registers a module's
 * adapters only when this says the module is on.
 */
public final class ModuleSwitch {

    private static final Pattern MODULE = Pattern.compile("[a-z][a-z0-9-]*");

    private ModuleSwitch() {
    }

    /** The configuration key of {@code module}'s switch. */
    public static String key(String module) {
        if (module == null || !MODULE.matcher(module).matches()) {
            throw new IllegalArgumentException("module name must be lower-case letters, digits and '-': " + module);
        }
        return "sangam.modules." + module + ".enabled";
    }

    /** Whether {@code module} is on; {@code defaultOn} applies when the key is absent or blank. */
    public static boolean enabled(SangamProperties properties, String module, boolean defaultOn) {
        return properties.getBoolean(key(module), defaultOn);
    }
}
