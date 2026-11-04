<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Auth;

/**
 * Supplies the credential for the Authorization header (ARCHITECTURE section 4: "Bearer <OIDC JWT>").
 */
interface TokenProvider
{
    /** A currently valid access token, fetched or refreshed when needed; null = anonymous. */
    public function token(): ?string;

    /** Full Authorization header value (bearer for token providers). */
    public function authorization(): ?string;

    /** Called after a 401; the next token() must not return the cached token. */
    public function invalidate(): void;
}
