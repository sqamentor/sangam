// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import org.sangam.sdk.http.HttpTransport;

/** Admin basics {@code /api/v1/admin/**}: org units, seats, assignments, delegations, policies, tenants (IX.3–IX.5, VI.4). */
public final class AdminApi {

    private final HttpTransport http;

    public AdminApi(HttpTransport http) {
        this.http = http;
    }

    public ResourceApi orgUnits() { return new ResourceApi(http, "/admin/org-units"); }
    public ResourceApi seats() { return new ResourceApi(http, "/admin/seats"); }
    public ResourceApi assignments() { return new ResourceApi(http, "/admin/assignments"); }
    public ResourceApi delegations() { return new ResourceApi(http, "/admin/delegations"); }
    public ResourceApi policies() { return new ResourceApi(http, "/admin/policies"); }
    public ResourceApi tenants() { return new ResourceApi(http, "/admin/tenants"); }

    /** Any other admin collection, for example {@code resource("users")}. */
    public ResourceApi resource(String name) {
        return new ResourceApi(http, "/admin/" + HttpTransport.seg(name));
    }
}
