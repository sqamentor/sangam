<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Auth;

/**
 * HTTP Basic credentials (Flowable external-client parity, FC-37). Only for dev/test gateways that accept Basic;
 * the Sangam server itself expects OIDC bearer tokens.
 */
final class BasicAuthProvider implements TokenProvider
{
    private readonly string $header;

    public function __construct(string $user, string $password)
    {
        $this->header = 'Basic ' . base64_encode($user . ':' . $password);
    }

    public function token(): ?string
    {
        return null;
    }

    public function authorization(): ?string
    {
        return $this->header;
    }

    public function invalidate(): void
    {
    }
}
