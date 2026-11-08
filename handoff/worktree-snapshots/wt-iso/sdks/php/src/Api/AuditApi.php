<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Api;

use Sangam\Sdk\Http\ApiRequest;
use Sangam\Sdk\Model\Page;

/** Audit timeline and hash-chain verification (R42, AC-01). */
final class AuditApi extends ApiSupport
{
    /**
     * GET /audit/{businessKey}/timeline: a page of AuditEntry {eventId, eventClass, chainId, seq, type, occurredAt,
     * actor, seatId, actedForSeatId, processInstanceId, activityId, taskId, payload, erased, hash, evidence}.
     */
    public function timeline(string $businessKey, ?bool $includeHistory = null, ?int $page = null,
                             ?int $size = null): Page
    {
        return $this->readPage(ApiRequest::get('/audit/' . self::seg($businessKey) . '/timeline')
            ->query('includeHistory', $includeHistory)->query('page', $page)->query('size', $size));
    }

    /** @return array<string, mixed> {tenantId, ok, verifiedAt, chains} */
    public function verify(?string $chainId = null, ?string $businessKey = null): array
    {
        return (array) $this->call(ApiRequest::get('/audit/verify')->query('chainId', $chainId)
            ->query('businessKey', $businessKey));
    }
}
