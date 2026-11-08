<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Api;

use Sangam\Sdk\Http\ApiRequest;
use Sangam\Sdk\Http\Json;
use Sangam\Sdk\Model\ExternalWork;
use Sangam\Sdk\Model\LockState;

/**
 * Worker API /api/v1/work (ARCHITECTURE section 4, VI.3). Every call after fetch carries
 * (workerId, lockVersion, attemptId); a stale lockVersion raises StaleLeaseException (409 stale-lease) and the
 * server changed nothing. Work endpoints are fenced (sangam-api.yaml: x-sangam-fenced) rather than keyed; the
 * $idempotencyKey derived from the operationId is still sent as Idempotency-Key, which the server accepts.
 * Most applications use Worker\SangamWorker, which drives these calls.
 */
final class WorkApi extends ApiSupport
{
    /**
     * Long-poll fetch-and-lock. $topics = list of TopicRequest arrays: name (required), lockDurationMs, variables
     * (list|null), localVariables (bool), processDefinitionKey, businessKey.
     *
     * @param list<array<string, mixed>> $topics
     * @return list<ExternalWork>
     */
    public function fetchAndLock(string $workerId, array $topics, int $maxTasks, int $asyncResponseTimeoutMs,
                                 ?\Closure $abort = null, ?bool $usePriority = null): array
    {
        $req = ApiRequest::post('/work/fetch-and-lock')
            ->body([
                'workerId' => $workerId,
                'topics' => $topics,
                'maxTasks' => $maxTasks,
                'asyncResponseTimeoutMs' => $asyncResponseTimeoutMs,
                'usePriority' => $usePriority,
            ])
            ->timeoutMs($asyncResponseTimeoutMs + 15000)
            ->noRetry()
            ->abortWhen($abort);
        $body = $this->call($req);
        $out = [];
        foreach (is_array($body) ? $body : [] as $w) {
            if (is_array($w)) {
                $out[] = ExternalWork::fromArray($w);
            }
        }
        return $out;
    }

    public function extendLock(string $workId, string $workerId, int $lockVersion, ?string $attemptId,
                               int $newDurationMs, ?string $idempotencyKey): LockState
    {
        return LockState::fromArray($this->call(ApiRequest::post('/work/' . self::seg($workId) . '/extend-lock')
            ->body(['workerId' => $workerId, 'lockVersion' => $lockVersion, 'attemptId' => $attemptId,
                'newDurationMs' => $newDurationMs])
            ->idempotencyKey($idempotencyKey)));
    }

    /**
     * @param array<string, mixed>|null $variables
     * @param array<string, mixed>|null $localVariables
     */
    public function complete(string $workId, string $workerId, int $lockVersion, ?string $attemptId,
                             ?array $variables, ?string $idempotencyKey, ?array $localVariables = null,
                             ?string $outcomeReference = null): void
    {
        $body = ['workerId' => $workerId, 'lockVersion' => $lockVersion, 'attemptId' => $attemptId,
            'variables' => Json::obj($variables), 'localVariables' => Json::obj($localVariables)];
        if ($outcomeReference !== null) {
            // SL-16: the downstream reference is recorded as the ledger outcome
            $body['outcome'] = ['reference' => $outcomeReference];
        }
        $this->call(ApiRequest::post('/work/' . self::seg($workId) . '/complete')
            ->body($body)
            ->idempotencyKey($idempotencyKey));
    }

    /** $retries = retries left after this failure; 0 raises an incident. */
    public function fail(string $workId, string $workerId, int $lockVersion, ?string $attemptId, string $errorMessage,
                         ?string $errorDetails, int $retries, int $retryTimeoutMs, ?string $idempotencyKey): void
    {
        $this->call(ApiRequest::post('/work/' . self::seg($workId) . '/fail')
            ->body(['workerId' => $workerId, 'lockVersion' => $lockVersion, 'attemptId' => $attemptId,
                'errorMessage' => $errorMessage, 'errorDetails' => $errorDetails, 'retries' => $retries,
                'retryTimeoutMs' => $retryTimeoutMs])
            ->idempotencyKey($idempotencyKey));
    }

    /** @param array<string, mixed>|null $variables */
    public function bpmnError(string $workId, string $workerId, int $lockVersion, ?string $attemptId, string $errorCode,
                              ?string $errorMessage, ?array $variables, ?string $idempotencyKey): void
    {
        $this->call(ApiRequest::post('/work/' . self::seg($workId) . '/bpmn-error')
            ->body(['workerId' => $workerId, 'lockVersion' => $lockVersion, 'attemptId' => $attemptId,
                'errorCode' => $errorCode, 'errorMessage' => $errorMessage, 'variables' => Json::obj($variables)])
            ->idempotencyKey($idempotencyKey));
    }

    /** Explicit release (VI.3.1 LOCKED --release--> AVAILABLE); owner and version are checked server-side (D-18). */
    public function unlock(string $workId, string $workerId, int $lockVersion, ?string $attemptId,
                           ?string $idempotencyKey): void
    {
        $this->call(ApiRequest::post('/work/' . self::seg($workId) . '/unlock')
            ->body(['workerId' => $workerId, 'lockVersion' => $lockVersion, 'attemptId' => $attemptId])
            ->idempotencyKey($idempotencyKey));
    }

    /**
     * VI.3.2 rule 4: the downstream effect may or may not have happened. Answered 202 with OutcomeUnknownResult
     * {workId, operationId, reconciliationItemId, state}; an UNKNOWN_OUTCOME reconciliation item is opened.
     *
     * @param array<string, mixed>|null $evidence VariableMap
     * @return array<string, mixed>
     */
    public function outcomeUnknown(string $workId, string $workerId, int $lockVersion, ?string $attemptId,
                                   ?string $detail, ?array $evidence, ?string $idempotencyKey): array
    {
        return (array) $this->call(ApiRequest::post('/work/' . self::seg($workId) . '/outcome-unknown')
            ->body(['workerId' => $workerId, 'lockVersion' => $lockVersion, 'attemptId' => $attemptId,
                'detail' => $detail, 'evidence' => Json::obj($evidence)])
            ->idempotencyKey($idempotencyKey));
    }

    /** VI.3.1: CANCEL_REQUESTED --worker ack--> CANCELLED. */
    public function cancelAck(string $workId, string $workerId, int $lockVersion, ?string $attemptId,
                              ?string $idempotencyKey): void
    {
        $this->call(ApiRequest::post('/work/' . self::seg($workId) . '/cancel-ack')
            ->body(['workerId' => $workerId, 'lockVersion' => $lockVersion, 'attemptId' => $attemptId])
            ->idempotencyKey($idempotencyKey));
    }
}
