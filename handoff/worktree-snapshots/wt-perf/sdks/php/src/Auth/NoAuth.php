<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Auth;

/** No Authorization header (local dev profile without a token). */
final class NoAuth extends BearerTokenProvider
{
    public function token(): ?string
    {
        return null;
    }
}
