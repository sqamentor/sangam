<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Auth;

use Sangam\Sdk\Error\SangamException;

/**
 * OIDC / OAuth 2.0 client-credentials grant (RFC 6749 section 4.4) against the IdP token endpoint, for example
 * Keycloak "/realms/<realm>/protocol/openid-connect/token". The token is cached in the process and refreshed
 * $refreshSkewSeconds before expires_in ends.
 */
final class ClientCredentialsTokenProvider extends BearerTokenProvider
{
    public const AUTH_BASIC = 'basic';
    public const AUTH_POST_BODY = 'post';

    private ?string $cached = null;
    private float $expiresAt = 0.0;

    public function __construct(
        private readonly string $tokenEndpoint,
        private readonly string $clientId,
        private readonly string $clientSecret,
        private readonly ?string $scope = null,
        private readonly ?string $audience = null,
        private readonly string $clientAuth = self::AUTH_BASIC,
        private readonly int $refreshSkewSeconds = 30,
        private readonly int $timeoutMs = 10000,
    ) {
    }

    public function token(): ?string
    {
        if ($this->cached !== null && microtime(true) < $this->expiresAt - $this->refreshSkewSeconds) {
            return $this->cached;
        }
        return $this->fetch();
    }

    public function invalidate(): void
    {
        $this->cached = null;
        $this->expiresAt = 0.0;
    }

    private function fetch(): string
    {
        $form = ['grant_type' => 'client_credentials'];
        if ($this->scope !== null) {
            $form['scope'] = $this->scope;
        }
        if ($this->audience !== null) {
            $form['audience'] = $this->audience;
        }
        $headers = ['Content-Type: application/x-www-form-urlencoded', 'Accept: application/json'];
        if ($this->clientAuth === self::AUTH_BASIC) {
            $headers[] = 'Authorization: Basic '
                . base64_encode(rawurlencode($this->clientId) . ':' . rawurlencode($this->clientSecret));
        } else {
            $form['client_id'] = $this->clientId;
            $form['client_secret'] = $this->clientSecret;
        }
        $ch = curl_init($this->tokenEndpoint);
        curl_setopt_array($ch, [
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => http_build_query($form, '', '&', PHP_QUERY_RFC3986),
            CURLOPT_HTTPHEADER => $headers,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT_MS => $this->timeoutMs,
            CURLOPT_CONNECTTIMEOUT_MS => $this->timeoutMs,
        ]);
        $raw = curl_exec($ch);
        if ($raw === false) {
            $err = curl_error($ch);
            curl_close($ch);
            throw new SangamException('token request failed: ' . $err);
        }
        $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        if (intdiv($status, 100) !== 2) {
            throw new SangamException('token endpoint returned HTTP ' . $status . ': ' . (string) $raw);
        }
        $data = json_decode((string) $raw, true);
        if (!is_array($data) || !isset($data['access_token']) || !is_string($data['access_token']) || $data['access_token'] === '') {
            throw new SangamException('token endpoint response has no access_token');
        }
        $this->cached = $data['access_token'];
        $this->expiresAt = microtime(true) + (float) ($data['expires_in'] ?? 300);
        return $this->cached;
    }
}
