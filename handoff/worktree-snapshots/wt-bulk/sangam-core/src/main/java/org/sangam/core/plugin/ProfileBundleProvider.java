// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.plugin;

import java.util.List;

/**
 * Plugin SPI ({@link java.util.ServiceLoader}): configuration profile documents a plugin ships (for example a country's
 * jurisdiction profile, a regime preset, or a sector reference profile). Each entry is a class-path resource of the
 * plugin (YAML or JSON in the sangam-config profile format). Without a provider the loader still reads the index
 * {@value #DEFAULT_INDEX} if the plugin jar has one. Imported as ACTIVE release profiles when the version is not yet
 * stored; later changes go through the maker-checker admin service.
 */
public interface ProfileBundleProvider {

    String DEFAULT_INDEX = "META-INF/sangam/profiles.idx";

    /** Resource paths of the profile documents, lowest layer first. */
    List<String> profileResources();
}
