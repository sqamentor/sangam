// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import org.sangam.core.layer.AuthorizationPolicy;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Permission bits stored in {@code AUTH_EFFECTIVE.perms} (VI.4 rule 2). The first eleven constants keep the ordinals of
 * the spike's {@link AuthorizationPolicy.Action}, so rows written by the core class and by this module share one bit
 * layout. Never reorder; only append.
 */
public enum Permission {
    READ, READ_TASK, READ_HISTORY, READ_VARIABLE, CLAIM, COMPLETE, APPROVE, CANCEL, MODIFY, CHANGE_GRANT, READ_AUDIT,
    // ---- appended by Stage C (ordinal 11+)
    READ_SENSITIVE, START, DELETE_INSTANCE, ERASE_DATA, MIGRATE, BULK, EXPORT, DEPLOY, ADMIN,
    APPROVE_SOD_EXCEPTION, WORK, ASSIGN, REVIEW_PACKAGE;

    /** VI.4 rule 1: a policy on these actions must compile to SQL, otherwise it is rejected at deployment. */
    private static final Set<Permission> LIST_VISIBLE = EnumSet.of(READ, READ_TASK, READ_HISTORY, READ_VARIABLE, READ_AUDIT);

    /** VI.4 rule 5: re-checked inside the command transaction with no cache. */
    private static final Set<Permission> SENSITIVE = EnumSet.of(COMPLETE, APPROVE, CANCEL, MODIFY, CHANGE_GRANT,
            DELETE_INSTANCE, ERASE_DATA, MIGRATE, BULK, ADMIN, APPROVE_SOD_EXCEPTION, DEPLOY, ASSIGN, REVIEW_PACKAGE);

    public long bit() {
        return 1L << ordinal();
    }

    public boolean listVisible() {
        return LIST_VISIBLE.contains(this);
    }

    public boolean sensitive() {
        return SENSITIVE.contains(this);
    }

    public static long mask(Permission... permissions) {
        long m = 0;
        for (Permission p : permissions) {
            m |= p.bit();
        }
        return m;
    }

    public static long mask(Iterable<Permission> permissions) {
        long m = 0;
        for (Permission p : permissions) {
            m |= p.bit();
        }
        return m;
    }

    public static Set<Permission> fromMask(long mask) {
        Set<Permission> s = EnumSet.noneOf(Permission.class);
        for (Permission p : values()) {
            if ((mask & p.bit()) != 0) {
                s.add(p);
            }
        }
        return s;
    }

    public static Permission parse(String name) {
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
    }

    public static Permission of(AuthorizationPolicy.Action a) {
        return values()[a.ordinal()];
    }
}
