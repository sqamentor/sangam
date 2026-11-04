// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.resources;

/**
 * Endpoint permissions of the resources, queue and roster APIs. Object-level checks are the module's VI.4 visibility and
 * guards (RES_* resource types, sangam-security ModuleDefaults role templates).
 */
public final class ResourcesPermissions {

    /** Catalogue, availability, own and visible bookings, queue boards, rosters. */
    public static final String READ = "resources.read";
    /** Booking, waitlist, token and duty actions of a user (hold, confirm, cancel, swap ...). */
    public static final String WRITE = "resources.write";
    /** Catalogue, calendars, allotment, counter operation, roster planning, bump, draws, jobs. */
    public static final String ADMIN = "resources.admin";

    private ResourcesPermissions() {
    }
}
