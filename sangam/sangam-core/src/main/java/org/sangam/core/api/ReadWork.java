// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.api;

import java.sql.Connection;

/**
 * A unit of <b>read</b> work on a transaction's connection: the callback of {@link org.sangam.core.layer.Transactions#read(ReadWork)} and
 * {@link EngineAdapter#inEngineReadTransaction(ReadWork)}.
 *
 * <p>Its own type (not {@code java.util.function.Function}) is deliberate (round 9, CodeQL
 * java/csrf-unprotected-request-type): a call of a shared {@code Function<Connection, T>} callback resolves, in a
 * whole-program call graph, to every write lambda of the product, so every GET handler that read in a transaction
 * appeared to write. A {@code ReadWork} call resolves only to read lambdas. The implementations of {@code read} never
 * call a write callback; the server's {@code GetHandlersNeverWriteTest} keeps it so.
 */
@FunctionalInterface
public interface ReadWork<T> {

    T read(Connection c);
}
