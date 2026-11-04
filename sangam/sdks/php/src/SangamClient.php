<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk;

use Sangam\Sdk\Api\AdminApi;
use Sangam\Sdk\Api\AuditApi;
use Sangam\Sdk\Api\DecisionsApi;
use Sangam\Sdk\Api\OpsApi;
use Sangam\Sdk\Api\ProcessesApi;
use Sangam\Sdk\Api\RepositoryApi;
use Sangam\Sdk\Api\TasksApi;
use Sangam\Sdk\Api\WorkApi;
use Sangam\Sdk\Auth\StaticTokenProvider;
use Sangam\Sdk\Auth\TokenProvider;
use Sangam\Sdk\Http\HttpTransport;
use Sangam\Sdk\Http\RetryPolicy;
use Sangam\Sdk\Log\Log;
use Sangam\Sdk\Log\SangamLogger;
use Sangam\Sdk\Log\StderrJsonLogger;

/**
 * Entry point of the Sangam PHP SDK: a typed client for /api/v1.
 *
 *     $client = new SangamClient('https://sangam.example.gov.in',
 *         new ClientCredentialsTokenProvider($tokenUrl, 'bill-worker', $secret));
 *     $pi = $client->processes()->start('p1_bill', 'BILL-2026-0001', ['amount' => TypedValue::decimal('125000.00')]);
 */
final class SangamClient
{
    private readonly HttpTransport $transport;
    private readonly Log $log;

    public function __construct(
        string $baseUrl,
        TokenProvider|string|null $auth = null,
        ?SangamLogger $logger = null,
        ?RetryPolicy $retryPolicy = null,
        int $requestTimeoutMs = 30000,
        int $connectTimeoutMs = 10000,
        ?string $userAgent = null,
        ?string $seat = null,
        ?string $actingForSeat = null,
    ) {
        if (!extension_loaded('curl')) {
            throw new \RuntimeException('the Sangam SDK needs ext-curl');
        }
        $tokens = is_string($auth) ? new StaticTokenProvider($auth) : $auth;
        $this->log = new Log($logger ?? new StderrJsonLogger());
        $this->transport = new HttpTransport($baseUrl, $tokens, $retryPolicy, $requestTimeoutMs, $connectTimeoutMs,
            $this->log, $userAgent, $seat, $actingForSeat);
    }

    /**
     * Default seat context for every call: X-Sangam-Seat (IX.5 rule 1) and X-Sangam-Acting-For-Seat (IX.5 rule 4,
     * part of the R41 fingerprint). null clears.
     */
    public function setSeat(?string $seat, ?string $actingForSeat = null): self
    {
        $this->transport->setSeat($seat, $actingForSeat);
        return $this;
    }

    /**
     * GET /me: identity, tenant, seat context and permissions of the caller (VI.4 rule 6).
     *
     * @return array<string, mixed>
     */
    public function me(): array
    {
        return (array) $this->transport->execute(\Sangam\Sdk\Http\ApiRequest::get('/me'))->body;
    }

    /** Last successful response (status, headers; ->replayed() reads Idempotent-Replayed). */
    public function lastResponse(): ?\Sangam\Sdk\Http\ApiResponse
    {
        return $this->transport->lastResponse();
    }

    /** Worker API /work/** (used by Worker\SangamWorker). */
    public function work(): WorkApi
    {
        return new WorkApi($this->transport);
    }

    public function tasks(): TasksApi
    {
        return new TasksApi($this->transport);
    }

    /** Process/case instances, messages, signals. */
    public function processes(): ProcessesApi
    {
        return new ProcessesApi($this->transport);
    }

    public function decisions(): DecisionsApi
    {
        return new DecisionsApi($this->transport);
    }

    public function audit(): AuditApi
    {
        return new AuditApi($this->transport);
    }

    public function admin(): AdminApi
    {
        return new AdminApi($this->transport);
    }

    /** Incidents, reconciliation, outbox, jobs. */
    public function ops(): OpsApi
    {
        return new OpsApi($this->transport);
    }

    /** Deployments, events, forms. */
    public function repository(): RepositoryApi
    {
        return new RepositoryApi($this->transport);
    }

    /** Low-level transport for endpoints not yet wrapped. */
    public function transport(): HttpTransport
    {
        return $this->transport;
    }

    public function log(): Log
    {
        return $this->log;
    }
}
