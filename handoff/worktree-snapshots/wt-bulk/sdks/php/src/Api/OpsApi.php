<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Api;

use Sangam\Sdk\Http\ApiRequest;
use Sangam\Sdk\Http\Json;
use Sangam\Sdk\Model\Page;

/**
 * Operations /api/v1/ops/**: incidents, reconciliation workbench (VI.3 UNKNOWN_OUTCOME), outbox, jobs. Other
 * actions (incident resolve, outbox skip, job execute/suspension) go through ResourceApi::action().
 */
final class OpsApi extends ApiSupport
{
    public const RESOLVE_CONFIRM_SUCCESS = 'CONFIRM_SUCCESS';
    public const RESOLVE_RETRY_SAME_OPERATION = 'RETRY_SAME_OPERATION';
    public const RESOLVE_MARK_FAILED = 'MARK_FAILED';

    public function incidents(): ResourceApi
    {
        return new ResourceApi($this->http, '/ops/incidents');
    }

    public function outbox(): ResourceApi
    {
        return new ResourceApi($this->http, '/ops/outbox');
    }

    public function jobs(): ResourceApi
    {
        return new ResourceApi($this->http, '/ops/jobs');
    }

    /** POST /ops/incidents/{id}/retry {reason, retries, annotation}. @return array<string, mixed> Incident */
    public function retryIncident(string $incidentId, string $reason, ?int $retries = null, ?string $annotation = null,
                                  ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/ops/incidents/' . self::seg($incidentId) . '/retry')
            ->body(['reason' => $reason, 'retries' => $retries, 'annotation' => $annotation])
            ->idempotencyKey($idempotencyKey));
    }

    /** POST /ops/jobs/{id}/retries {retries, reason}. @return array<string, mixed> Job */
    public function setJobRetries(string $jobId, int $retries, string $reason, ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/ops/jobs/' . self::seg($jobId) . '/retries')
            ->body(['retries' => $retries, 'reason' => $reason])->idempotencyKey($idempotencyKey));
    }

    /** POST /ops/outbox/{outboxId}/requeue {reason, destination}. @return array<string, mixed> OutboxDelivery */
    public function requeueOutbox(string $outboxId, string $reason, ?string $destination = null,
                                  ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/ops/outbox/' . self::seg($outboxId) . '/requeue')
            ->body(['reason' => $reason, 'destination' => $destination])->idempotencyKey($idempotencyKey));
    }

    /** GET /ops/outbox/stats. @return array<string, mixed> */
    public function outboxStats(): array
    {
        return (array) $this->call(ApiRequest::get('/ops/outbox/stats'));
    }

    /**
     * Reconciliation items {id, kind (UNKNOWN_OUTCOME|LATE_RESULT_AFTER_DELETE), operationId, rootId, taskId,
     * state (OPEN|RESOLVED), details, action, resolvedBy, resolution, createdAt, resolvedAt}.
     */
    public function reconciliation(?string $state = null, ?string $kind = null, ?int $page = null,
                                   ?int $size = null): Page
    {
        return $this->readPage(ApiRequest::get('/ops/reconciliation')->query('state', $state)->query('kind', $kind)
            ->query('page', $page)->query('size', $size));
    }

    /** @return array<string, mixed> */
    public function reconciliationItem(string $id): array
    {
        return (array) $this->call(ApiRequest::get('/ops/reconciliation/' . self::seg($id)));
    }

    /**
     * Resolves an item: CONFIRM_SUCCESS (with $result variables), RETRY_SAME_OPERATION or MARK_FAILED, with
     * mandatory $evidence (VI.3.2 rule 4, AC-03).
     *
     * @param array<string, mixed>|null $result VariableMap
     * @return array<string, mixed>
     */
    public function resolve(string $id, string $action, string $evidence, ?array $result = null, ?string $reason = null,
                            ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/ops/reconciliation/' . self::seg($id) . '/resolve')
            ->body(['action' => $action, 'evidence' => $evidence, 'result' => Json::obj($result), 'reason' => $reason])
            ->idempotencyKey($idempotencyKey));
    }
}
