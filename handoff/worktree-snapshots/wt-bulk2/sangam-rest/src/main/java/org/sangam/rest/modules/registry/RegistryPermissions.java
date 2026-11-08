// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.registry;

/** Endpoint permissions of the registry API (object-level checks are the module's VI.4 visibility). */
public final class RegistryPermissions {

    public static final String READ = "registry.read";
    public static final String WRITE = "registry.write";
    public static final String ADMIN = "registry.admin";

    private RegistryPermissions() {
    }
}
