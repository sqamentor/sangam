<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Auth;

/** A fixed bearer token (dev profile static token, or a token managed by the platform). */
final class StaticTokenProvider extends BearerTokenProvider
{
    public function __construct(private readonly string $token)
    {
    }

    public function token(): ?string
    {
        return $this->token;
    }
}
