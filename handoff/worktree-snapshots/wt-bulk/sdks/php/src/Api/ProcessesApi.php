<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Api;

use Sangam\Sdk\Http\ApiRequest;
use Sangam\Sdk\Http\Json;
use Sangam\Sdk\Model\Page;

/**
 * Process and case instances, messages and signals. The tenant comes from the token, so no call takes a tenantId.
 * Variables are VariableMaps: plain JSON values or Model\TypedValue (R33).
 */
final class ProcessesApi extends ApiSupport
{
    /**
     * POST /process-instances (201 ProcessInstance). The government profile requires an Idempotency-Key; pass a
     * business-derived one ("start:<businessKey>") to make "start once" safe across retries (generated otherwise).
     *
     * @param array<string, mixed>|null $variables
     * @param list<string>|null $startBeforeActivityIds
     * @return array<string, mixed> ProcessInstance {id, processDefinitionId, processDefinitionKey,
     *     processDefinitionVersion, businessKey, tenantId, state, ended, startTime, endTime, activeActivityIds,
     *     caseVersion, startedBy, businessStatus, superProcessInstanceId, rootProcessInstanceId}
     */
    public function start(string $key, ?string $businessKey = null, ?array $variables = null, ?int $version = null,
                          ?string $packageHash = null, ?string $formVersion = null,
                          ?array $startBeforeActivityIds = null, ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/process-instances')
            ->body(['key' => $key, 'businessKey' => $businessKey, 'variables' => Json::obj($variables),
                'version' => $version, 'packageHash' => $packageHash, 'formVersion' => $formVersion,
                'startBeforeActivityIds' => $startBeforeActivityIds])
            ->idempotencyKey($idempotencyKey));
    }

    /**
     * GET /process-instances. Filters: key, businessKey, state, startedAfter, startedBefore (ISO-8601),
     * withIncidents (bool), superProcessInstanceId.
     *
     * @param array<string, mixed> $filters
     */
    public function list(array $filters = [], ?int $page = null, ?int $size = null): Page
    {
        return $this->readPage(ApiRequest::get('/process-instances')->queryAll($filters)
            ->query('page', $page)->query('size', $size));
    }

    /** @return array<string, mixed> ProcessInstance */
    public function get(string $processInstanceId): array
    {
        return (array) $this->call(ApiRequest::get('/process-instances/' . self::seg($processInstanceId)));
    }

    /** @return array<string, mixed> VariableMap (unwrap entries with TypedValue::unwrap()) */
    public function variables(string $processInstanceId): array
    {
        return (array) $this->call(ApiRequest::get('/process-instances/' . self::seg($processInstanceId) . '/variables'));
    }

    /**
     * Business cancel (VI.5): requires CANCEL_INSTANCE and a reason. Idempotency-Key required by the government
     * profile (generated when null).
     *
     * @return array<string, mixed> TerminationResult {processInstanceId, state, openOperationIds, cancelRequestedWork, tombstones}
     */
    public function cancel(string $processInstanceId, string $reason, ?string $reasonCode = null,
                           ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/process-instances/' . self::seg($processInstanceId) . '/cancel')
            ->body(['reason' => $reason, 'reasonCode' => $reasonCode])->idempotencyKey($idempotencyKey));
    }

    /**
     * POST /messages: correlate by business key, instance id and/or correlation keys.
     *
     * @param array<string, mixed>|null $correlationKeys
     * @param array<string, mixed>|null $variables
     * @return array<string, mixed> MessageResult {messageName, processInstanceIds, executionIds}
     */
    public function correlateMessage(string $messageName, ?string $businessKey = null, ?string $processInstanceId = null,
                                     ?array $correlationKeys = null, ?array $variables = null, ?bool $all = null,
                                     ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/messages')
            ->body(['messageName' => $messageName, 'businessKey' => $businessKey,
                'processInstanceId' => $processInstanceId, 'correlationKeys' => Json::obj($correlationKeys),
                'variables' => Json::obj($variables), 'all' => $all])
            ->idempotencyKey($idempotencyKey));
    }

    /**
     * POST /signals (204).
     *
     * @param array<string, mixed>|null $variables
     */
    public function signal(string $name, ?string $executionId = null, ?array $variables = null,
                           ?string $idempotencyKey = null): void
    {
        $this->call(ApiRequest::post('/signals')
            ->body(['name' => $name, 'executionId' => $executionId, 'variables' => Json::obj($variables)])
            ->idempotencyKey($idempotencyKey));
    }

    /**
     * POST /case-instances (CMMN 1.1).
     *
     * @param array<string, mixed>|null $variables
     * @return array<string, mixed> CaseInstance {id, caseDefinitionId, caseDefinitionKey, businessKey, tenantId,
     *     state, created, executions, reactivatedFrom}
     */
    public function startCase(string $key, ?string $businessKey = null, ?array $variables = null,
                              ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/case-instances')
            ->body(['key' => $key, 'businessKey' => $businessKey, 'variables' => Json::obj($variables)])
            ->idempotencyKey($idempotencyKey));
    }

    /** @return array<string, mixed> CaseInstance */
    public function getCase(string $caseInstanceId): array
    {
        return (array) $this->call(ApiRequest::get('/case-instances/' . self::seg($caseInstanceId)));
    }
}
