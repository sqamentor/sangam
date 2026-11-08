// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.kernel;

/**
 * The registry kinds built on the one kernel (M4). Each configured type has exactly one kind; the kind decides which
 * kind-specific service and tables extend the kernel row.
 */
public enum ObjectKind {
    /** Any configured register record without extra behaviour. */
    RECORD,
    /** An issued instrument: licence, permit, certificate, registration (N06). */
    INSTRUMENT,
    /** A physical or logical asset with maintenance (N03). */
    ASSET,
    /** A tracked physical item under chain of custody (N18). */
    CUSTODY_ITEM
}
