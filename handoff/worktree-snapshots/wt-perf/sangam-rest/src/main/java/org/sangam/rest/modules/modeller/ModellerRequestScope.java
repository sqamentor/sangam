// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import java.util.function.Supplier;

import org.sangam.rest.port.RequestContext;

/** Carries the caller's context through {@code ActionModels}, whose permission callback receives only ids. */
public final class ModellerRequestScope {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private ModellerRequestScope() {
    }

    public static RequestContext current() {
        return CURRENT.get();
    }

    public static <T> T with(RequestContext ctx, Supplier<T> work) {
        CURRENT.set(ctx);
        try {
            return work.get();
        } finally {
            CURRENT.remove();
        }
    }
}
