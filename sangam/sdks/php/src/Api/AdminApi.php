<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Api;

use Sangam\Sdk\Http\HttpTransport;

/** Admin basics /api/v1/admin/**: org units, seats, assignments, delegations, policies, tenants (IX.3-IX.5, VI.4). */
final class AdminApi
{
    public function __construct(private readonly HttpTransport $http)
    {
    }

    public function orgUnits(): ResourceApi
    {
        return new ResourceApi($this->http, '/admin/org-units');
    }

    public function seats(): ResourceApi
    {
        return new ResourceApi($this->http, '/admin/seats');
    }

    public function assignments(): ResourceApi
    {
        return new ResourceApi($this->http, '/admin/assignments');
    }

    public function delegations(): ResourceApi
    {
        return new ResourceApi($this->http, '/admin/delegations');
    }

    public function policies(): ResourceApi
    {
        return new ResourceApi($this->http, '/admin/policies');
    }

    public function tenants(): ResourceApi
    {
        return new ResourceApi($this->http, '/admin/tenants');
    }

    /** Any other admin collection, for example resource('users'). */
    public function resource(string $name): ResourceApi
    {
        return new ResourceApi($this->http, '/admin/' . HttpTransport::seg($name));
    }
}
