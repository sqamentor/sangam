<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Auth;

/** Base class: Authorization = "Bearer <token()>". */
abstract class BearerTokenProvider implements TokenProvider
{
    public function authorization(): ?string
    {
        $t = $this->token();
        return ($t === null || $t === '') ? null : 'Bearer ' . $t;
    }

    public function invalidate(): void
    {
    }
}
