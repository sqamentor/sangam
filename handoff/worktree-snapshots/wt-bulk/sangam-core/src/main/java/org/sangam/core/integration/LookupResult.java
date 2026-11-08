// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.integration;

import java.util.Map;

/** Result of an outcome lookup by operationId (capability classes a and b). */
public sealed interface LookupResult
        permits LookupResult.Found, LookupResult.NotFound, LookupResult.Unavailable, LookupResult.NotSupported {

    /** The downstream system knows the operation; {@code succeeded} tells whether the effect happened. */
    record Found(boolean succeeded, Map<String, Object> output, String providerRef, String failureCode)
            implements LookupResult {
        public Found {
            output = output == null ? Map.of() : Map.copyOf(output);
        }

        public static Found success(Map<String, Object> output, String providerRef) {
            return new Found(true, output, providerRef, null);
        }

        public static Found failed(String failureCode, Map<String, Object> output) {
            return new Found(false, output, null, failureCode);
        }
    }

    /** The downstream system has no record of the operation: the effect did not happen (yet). */
    record NotFound() implements LookupResult {
    }

    /** The lookup itself failed (network, 5xx); try again later. */
    record Unavailable(String reason) implements LookupResult {
    }

    /** The connector has no lookup (class c). */
    record NotSupported() implements LookupResult {
    }
}
