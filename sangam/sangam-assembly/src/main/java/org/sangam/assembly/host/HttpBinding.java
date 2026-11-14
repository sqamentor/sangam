// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

/**
 * HTTP seam (design note section 10.1, a convenience). A host that offers HTTP binds the routes of the published
 * contract ({@code sangam-api.yaml}), each a one-line call into a port, and renders problems unchanged. Without it the
 * host offers no HTTP surface and says so; the Java API still authorizes.
 */
public interface HttpBinding {

    /** The {@code info.version} of the published contract the routes are bound from. */
    String contractVersion();

    /** The base path the routes are served under (for example {@code /api/v1}). */
    String basePath();
}
