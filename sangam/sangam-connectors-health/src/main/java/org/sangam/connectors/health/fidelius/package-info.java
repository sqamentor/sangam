// SPDX-License-Identifier: Apache-2.0
/**
 * ABDM Fidelius (ECDH on curve25519, HKDF-SHA256, AES-256-GCM) on Bouncy Castle (decision 243): bcprov-jdk18on, the
 * same jar as eSign, an optional dependency of the optional sangam-connectors-health module only, never on the default
 * server (a deployment that uses it never runs the NATS add-on, decision 235). Checked against a byte-exact copy of the
 * MIT test vectors of abdm_fidelius (test resources); no ABDM conformance or government approval is claimed.
 */
package org.sangam.connectors.health.fidelius;
