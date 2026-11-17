// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.api;

/**
 * A read without arguments: the callback of the read twins of the product's call wrappers (engine calls, joined
 * transactions, guarded controller calls). Like {@link ReadWork}, its own type keeps a read path from calling a shared
 * {@code java.util.function.Supplier}, which in a whole-program call graph resolves to every write lambda (round 9,
 * CodeQL java/csrf-unprotected-request-type).
 */
@FunctionalInterface
public interface ReadSupplier<T> {

    T read();
}
