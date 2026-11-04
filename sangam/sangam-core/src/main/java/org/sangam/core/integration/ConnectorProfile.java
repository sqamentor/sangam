// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.integration;

/**
 * Where a connector sends its calls. {@link #SIMULATOR} points it at the in-process simulator of the same system, for
 * air-gapped development and tests (ARCHITECTURE.md §1.9: no outbound calls unless configured).
 */
public enum ConnectorProfile {
    LIVE,
    SIMULATOR
}
