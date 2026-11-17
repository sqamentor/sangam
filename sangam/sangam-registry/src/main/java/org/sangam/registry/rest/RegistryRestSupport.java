// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.rest;

import org.sangam.core.layer.Transactions;
import org.sangam.registry.RegistryContext;

import java.sql.Connection;
import java.util.function.BiFunction;

/**
 * Shared plumbing of the registry REST controllers: the caller's {@link RegistryContext} (from the authenticated
 * identity, VI.4 rule 6) and one transaction per request. The integrator binds {@link CallerResolver} to the security
 * module's identity ({@code SecurityIdentity}: tenant claim, principal id, acting-for seat) and supplies the
 * request's {@code Idempotency-Key} as the operationId.
 */
public final class RegistryRestSupport {

    /** Resolves the current caller; implemented by the integrator over the security module. */
    @FunctionalInterface
    public interface CallerResolver {
        RegistryContext current();
    }

    /** The read twin of the {@code tx} callback (round 9): a read path never calls the shared write callback. */
    @FunctionalInterface
    public interface Read<T> {
        T read(Connection c, RegistryContext ctx);
    }

    private final Transactions transactions;
    private final CallerResolver caller;

    public RegistryRestSupport(Transactions transactions, CallerResolver caller) {
        this.transactions = transactions;
        this.caller = caller;
    }

    /** Runs {@code work} in one transaction with the caller's context. */
    public <T> T tx(BiFunction<Connection, RegistryContext, T> work) {
        RegistryContext ctx = caller.current();
        return transactions.inTransaction(c -> work.apply(c, ctx));
    }

    /** Same, with a declared purpose (privacy and consent checks), e.g. from the {@code X-Purpose} header. */
    public <T> T tx(String purpose, BiFunction<Connection, RegistryContext, T> work) {
        RegistryContext ctx = caller.current();
        RegistryContext withPurpose = purpose == null || purpose.isBlank() ? ctx : ctx.withPurpose(purpose);
        return transactions.inTransaction(c -> work.apply(c, withPurpose));
    }

    /** {@link #tx(BiFunction)} for work that only reads. */
    public <T> T rtx(Read<T> work) {
        RegistryContext ctx = caller.current();
        return transactions.read(c -> work.read(c, ctx));
    }

    /** {@link #tx(String, BiFunction)} for work that only reads. */
    public <T> T rtx(String purpose, Read<T> work) {
        RegistryContext ctx = caller.current();
        RegistryContext withPurpose = purpose == null || purpose.isBlank() ? ctx : ctx.withPurpose(purpose);
        return transactions.read(c -> work.read(c, withPurpose));
    }

    /** Same, with the request's {@code Idempotency-Key} as the operationId (R41). */
    public <T> T txOp(String idempotencyKey, BiFunction<Connection, RegistryContext, T> work) {
        RegistryContext ctx = caller.current();
        RegistryContext withOp = idempotencyKey == null || idempotencyKey.isBlank() ? ctx : ctx.withOperation(idempotencyKey);
        return transactions.inTransaction(c -> work.apply(c, withOp));
    }

    /** Public (unauthenticated) endpoints such as verification run with a fixed tenant and a system actor. */
    public <T> T publicTx(String tenant, BiFunction<Connection, RegistryContext, T> work) {
        RegistryContext ctx = RegistryContext.of(tenant, "system:public-verification").withPurpose("PUBLIC_VERIFICATION");
        return transactions.inTransaction(c -> work.apply(c, ctx));
    }

    /** {@link #publicTx} for work that only reads. */
    public <T> T publicRead(String tenant, Read<T> work) {
        RegistryContext ctx = RegistryContext.of(tenant, "system:public-verification").withPurpose("PUBLIC_VERIFICATION");
        return transactions.read(c -> work.read(c, ctx));
    }
}
