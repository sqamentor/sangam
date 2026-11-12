// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

/**
 * How a durable step recovers when an earlier attempt left it {@code INTENT} (the crash window of R37), following
 * the capability class of the remote call (R36):
 *
 * <ul>
 *   <li>(a) idempotent, with or without lookup: {@link #idempotent()} runs the call again with the same
 *       {@link Step#idempotencyKey()};</li>
 *   <li>(b) outcome lookup only: {@link #lookup(StepLookup)} asks the remote system before any controlled retry;</li>
 *   <li>(c) neither (the default): the step throws {@link OutcomeUnknownException}, the worker reports
 *       outcome-unknown and reconciliation decides; nothing is repeated automatically.</li>
 * </ul>
 * A lookup, when given, is always asked first.
 */
public final class StepOptions<T> {

    private static final StepOptions<Object> DEFAULTS = new StepOptions<>(false, null);

    private final boolean idempotent;
    private final StepLookup<T> lookup;

    private StepOptions(boolean idempotent, StepLookup<T> lookup) {
        this.idempotent = idempotent;
        this.lookup = lookup;
    }

    /** Capability class (c): no lookup, not idempotent. */
    @SuppressWarnings("unchecked")
    public static <T> StepOptions<T> defaults() {
        return (StepOptions<T>) DEFAULTS;
    }

    /** Capability class (a): the remote call is idempotent on {@link Step#idempotencyKey()}. */
    public static <T> StepOptions<T> idempotentCall() {
        return new StepOptions<>(true, null);
    }

    /** Capability class (b): look the outcome up before running the call again. */
    public static <T> StepOptions<T> withLookup(StepLookup<T> lookup) {
        return new StepOptions<>(false, java.util.Objects.requireNonNull(lookup, "lookup"));
    }

    /** These options, also idempotent (class (a) with a lookup). */
    public StepOptions<T> idempotent() {
        return new StepOptions<>(true, lookup);
    }

    /** These options with an outcome lookup. */
    public StepOptions<T> lookup(StepLookup<T> l) {
        return new StepOptions<>(idempotent, java.util.Objects.requireNonNull(l, "lookup"));
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public StepLookup<T> lookupOrNull() {
        return lookup;
    }
}
