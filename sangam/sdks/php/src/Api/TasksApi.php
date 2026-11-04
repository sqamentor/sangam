<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Api;

use Sangam\Sdk\Http\ApiRequest;
use Sangam\Sdk\Http\Json;
use Sangam\Sdk\Model\Page;

/**
 * User tasks /api/v1/tasks, filtered by the caller's VI.4 policy on the server. Task objects carry id, name,
 * description, taskDefinitionKey, processInstanceId, processDefinitionKey, caseInstanceId, businessKey, assignee,
 * owner, seatId, tenantId, created, due, followUp, lastUpdated, claimTime, state, priority, formKey,
 * delegationState. Mutations take an optional Idempotency-Key (null = generated per call).
 */
final class TasksApi extends ApiSupport
{
    /**
     * Filters: assignee, mine (bool), unassigned (bool), candidateGroup, seatId, processInstanceId, caseInstanceId,
     * businessKey, processDefinitionKey, taskDefinitionKey, state, sortBy, sortOrder, page, size.
     *
     * @param array<string, mixed> $query
     */
    public function list(array $query = []): Page
    {
        return $this->readPage(ApiRequest::get('/tasks')->queryAll($query));
    }

    /** @return array<string, mixed> Task */
    public function get(string $taskId): array
    {
        return (array) $this->call(ApiRequest::get('/tasks/' . self::seg($taskId)));
    }

    /** @return array<string, mixed> VariableMap (unwrap entries with TypedValue::unwrap()) */
    public function variables(string $taskId): array
    {
        return (array) $this->call(ApiRequest::get('/tasks/' . self::seg($taskId) . '/variables'));
    }

    /** Claims for the caller (in the X-Sangam-Seat context). No body; 204. */
    public function claim(string $taskId, ?string $idempotencyKey = null): void
    {
        $this->call(ApiRequest::post('/tasks/' . self::seg($taskId) . '/claim')->idempotencyKey($idempotencyKey));
    }

    /** No body; 204. */
    public function unclaim(string $taskId, ?string $idempotencyKey = null): void
    {
        $this->call(ApiRequest::post('/tasks/' . self::seg($taskId) . '/unclaim')->idempotencyKey($idempotencyKey));
    }

    /**
     * Decision on the task, bound to the payload hash (hex SHA-256 of the material payload) and case version the
     * user saw (VI.4 rule 5); a mismatch gives 409 APPROVAL_INVALIDATED.
     *
     * @param array<string, mixed>|null $variables
     * @return array<string, mixed> CompleteTaskResult {taskId, processInstanceId, decision, payloadHash, instanceEnded, nextTaskIds}
     */
    public function complete(string $taskId, string $payloadHash, ?array $variables = null, ?string $decision = null,
                             ?string $remarks = null, ?int $caseVersion = null, ?string $formVersion = null,
                             ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/tasks/' . self::seg($taskId) . '/complete')
            ->body(['payloadHash' => $payloadHash, 'caseVersion' => $caseVersion, 'decision' => $decision,
                'remarks' => $remarks, 'formVersion' => $formVersion, 'variables' => Json::obj($variables)])
            ->idempotencyKey($idempotencyKey));
    }

    /**
     * Sends the task back (for example to the initiator) with a mandatory reason.
     *
     * @param array<string, mixed>|null $variables
     * @return array<string, mixed> CompleteTaskResult
     */
    public function returnTask(string $taskId, string $reason, ?string $reasonCode = null,
                               ?string $targetActivityId = null, ?array $variables = null,
                               ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/tasks/' . self::seg($taskId) . '/return')
            ->body(['reason' => $reason, 'reasonCode' => $reasonCode, 'targetActivityId' => $targetActivityId,
                'variables' => Json::obj($variables)])
            ->idempotencyKey($idempotencyKey));
    }

    /** Delegates to a user or a seat with a mandatory reason. @return array<string, mixed> */
    public function delegate(string $taskId, ?string $toUserId, ?string $toSeatId, string $reason,
                             ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/tasks/' . self::seg($taskId) . '/delegate')
            ->body(['toUserId' => $toUserId, 'toSeatId' => $toSeatId, 'reason' => $reason])
            ->idempotencyKey($idempotencyKey));
    }
}
