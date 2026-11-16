<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Testing;

/**
 * The step journal of SangamTestEngine (M-025, R37): GET/PUT /work/{id}/step?stepName= and GET /work/{id}/steps,
 * keyed by (operationId, step name) and fenced by the lease like the server (WorkStepJournal + WorkPort.inFencedWork).
 * Same transitions: INTENT on a new, INTENT or FAILED step; SUCCEEDED/FAILED need an INTENT (409
 * STEP_INTENT_REQUIRED); SUCCEEDED is final (409 STEP_ALREADY_RECORDED) unless an identical repeat (same result and
 * providerRef); an identical repeat of INTENT/FAILED by the same attempt is a no-op. Missing attemptId or a bad name
 * is 400 VALIDATION_FAILED, unknown work 404, a deleted instance 410 GONE_INSTANCE_DELETED; then the engine's fence
 * (NO_LEASE, STALE_VERSION, NOT_OWNER, EXPIRED, ATTEMPT_MISMATCH -> 409 STALE_LEASE).
 *
 * @internal used by SangamTestEngine
 */
final class EngineSteps
{
    private const STATES = ['INTENT', 'SUCCEEDED', 'FAILED'];

    /** States in which the server still has the work's external task (an incident and a reconciliation hold keep it). */
    private const TASK_EXISTS = ['AVAILABLE', 'LOCKED', 'INCIDENT', 'UNKNOWN_OUTCOME'];

    /** @var array<string, array<string, array<string, mixed>>> operationId -> step name -> row, in recording order */
    private array $steps = [];

    /**
     * @param \Closure(string): (array<string, mixed>|null) $work  the engine's work item by id, or null
     * @param \Closure(string, array<string, mixed>): void $fence  the engine's lease fence (throws EngineProblem)
     * @param \Closure(): string $now                              the engine clock as ISO-8601
     */
    public function __construct(
        private readonly \Closure $work,
        private readonly \Closure $fence,
        private readonly \Closure $now,
    ) {
    }

    /**
     * One step-journal request.
     *
     * @param array<string, mixed> $query decoded query parameters
     * @param array<string, mixed> $body  decoded JSON object body
     * @return array{0: int, 1: array<string, string>, 2: mixed}
     */
    public function route(string $method, string $workId, ?string $name, array $query, array $body): array
    {
        if ($name === null && $method === 'GET') {
            return [200, [], $this->list($workId)];
        }
        if ($name !== null && $method === 'GET') {
            $lv = $query['lockVersion'] ?? null;
            if (!is_string($lv) || preg_match('/^-?\d+$/D', $lv) !== 1) {
                throw new EngineProblem(400, 'VALIDATION_FAILED', 'lockVersion is required');
            }
            return [200, [], $this->get($workId, $name, $query['workerId'] ?? null, (int) $lv,
                $query['attemptId'] ?? null)];
        }
        if ($name !== null && $method === 'PUT') {
            return [200, [], $this->put($workId, $name, $body)];
        }
        throw new EngineProblem(405, 'METHOD_NOT_ALLOWED',
            $method . ' /work/' . $workId . '/steps is not served by the test engine');
    }

    /** @return array<string, mixed> */
    public function get(string $workId, string $name, mixed $workerId, int $lockVersion, mixed $attemptId): array
    {
        $w = $this->fenced($workId, $name, ['workerId' => $workerId, 'lockVersion' => $lockVersion,
            'attemptId' => $attemptId]);
        $row = $this->steps[$w['operationId']][$name] ?? null;
        if ($row === null) {
            throw EngineProblem::notFound('work step', $name);
        }
        return self::view($row);
    }

    /**
     * @param array<string, mixed> $body WorkStepRequest
     * @return array<string, mixed>
     */
    public function put(string $workId, string $name, array $body): array
    {
        $w = $this->fenced($workId, $name, ['workerId' => $body['workerId'] ?? null,
            'lockVersion' => $body['lockVersion'] ?? null, 'attemptId' => $body['attemptId'] ?? null]);
        $state = $body['state'] ?? null;
        if (!is_string($state) || !in_array($state, self::STATES, true)) {
            throw new EngineProblem(400, 'VALIDATION_FAILED', 'state must be INTENT, SUCCEEDED or FAILED');
        }
        $attemptId = (string) $body['attemptId'];
        $result = $body['result'] ?? null;
        $ref = is_string($body['providerRef'] ?? null) ? $body['providerRef'] : null;
        $error = is_string($body['error'] ?? null) ? $body['error'] : null;
        $op = (string) $w['operationId'];
        $row = $this->steps[$op][$name] ?? null;
        if ($row === null) {
            if ($state !== 'INTENT') {
                throw self::intentRequired($name, null);
            }
            $row = ['operationId' => $op, 'name' => $name];
        } else {
            if (self::identical($row, $state, $attemptId, $result, $ref, $error)) {
                return self::view($row);
            }
            if ($row['state'] === 'SUCCEEDED') {
                throw new EngineProblem(409, 'STEP_ALREADY_RECORDED', 'step ' . $name . ' already SUCCEEDED');
            }
            if ($state !== 'INTENT' && $row['state'] !== 'INTENT') {
                throw self::intentRequired($name, (string) $row['state']);
            }
        }
        $row['workId'] = $workId;
        $row['state'] = $state;
        $row['result'] = $result;
        $row['providerRef'] = $ref;
        $row['error'] = $error;
        $row['attemptId'] = $attemptId;
        $row['recordedAt'] = ($this->now)();
        $this->steps[$op][$name] = $row;
        return self::view($row);
    }

    /** @return list<array<string, mixed>> the steps of the work's operation in recording order */
    public function list(string $workId): array
    {
        $w = ($this->work)($workId);
        $rows = [];
        // as WorkStepController.list: the operation of open work, else the rows this work id recorded
        if ($w !== null && isset($w['operationId']) && self::taskExists($w)) {
            $rows = array_values($this->steps[(string) $w['operationId']] ?? []);
        } else {
            foreach ($this->steps as $byName) {
                foreach ($byName as $row) {
                    if (($row['workId'] ?? null) === $workId) {
                        $rows[] = $row;
                    }
                }
            }
            if ($rows === []) {
                throw EngineProblem::notFound('external work', $workId);
            }
        }
        return array_map(static fn(array $r): array => self::view($r), $rows);
    }

    /**
     * @param array<string, mixed> $lease workerId, lockVersion, attemptId
     * @return array<string, mixed> the work item
     */
    private function fenced(string $workId, string $name, array $lease): array
    {
        if ($name === '' || strlen($name) > 128 || preg_match('/^[\x20-\x7e]+$/D', $name) !== 1) {
            throw new EngineProblem(400, 'VALIDATION_FAILED', 'stepName must be 1-128 printable ASCII characters');
        }
        $attemptId = $lease['attemptId'];
        if (!is_string($attemptId) || trim($attemptId) === '') {
            throw new EngineProblem(400, 'VALIDATION_FAILED', 'attemptId is required');
        }
        $w = ($this->work)($workId);
        if ($w === null) {
            throw EngineProblem::notFound('external work', $workId);
        }
        // as OperatonWorkAdapter.inFencedWork: a deleted instance (tombstone) is 410, any other ended work 404 (SL-174)
        if ($w['deleted'] ?? false) {
            throw new EngineProblem(410, 'GONE_INSTANCE_DELETED', 'The instance of this work was deleted', null,
                ['workId' => $workId]);
        }
        if (!self::taskExists($w)) {
            throw EngineProblem::notFound('external work', $workId);
        }
        ($this->fence)($workId, $lease);
        return $w;
    }

    /** @param array<string, mixed> $w */
    private static function taskExists(array $w): bool
    {
        return !($w['deleted'] ?? false) && in_array($w['state'] ?? null, self::TASK_EXISTS, true);
    }

    /** @param array<string, mixed> $row */
    private static function identical(array $row, string $state, string $attemptId, mixed $result, ?string $ref,
                                      ?string $error): bool
    {
        if ($row['state'] !== $state) {
            return false;
        }
        $same = self::canonical($row['result']) === self::canonical($result) && $row['providerRef'] === $ref;
        if ($state === 'SUCCEEDED') {
            return $same;
        }
        return $same && $row['attemptId'] === $attemptId && $row['error'] === $error;
    }

    private static function intentRequired(string $name, ?string $current): EngineProblem
    {
        return new EngineProblem(409, 'STEP_INTENT_REQUIRED', 'step ' . $name . ' has no INTENT'
            . ($current === null ? '' : ' (it is ' . $current . ')'));
    }

    /**
     * @param array<string, mixed> $row
     * @return array<string, mixed> WorkStep
     */
    private static function view(array $row): array
    {
        return ['operationId' => $row['operationId'], 'name' => $row['name'], 'state' => $row['state'],
            'result' => $row['result'], 'providerRef' => $row['providerRef'], 'error' => $row['error'],
            'attemptId' => $row['attemptId'], 'recordedAt' => $row['recordedAt']];
    }

    private static function canonical(mixed $v): string
    {
        return (string) json_encode(self::sortKeys($v), JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE
            | JSON_PRESERVE_ZERO_FRACTION);
    }

    private static function sortKeys(mixed $v): mixed
    {
        if (!is_array($v)) {
            return $v;
        }
        if (!array_is_list($v)) {
            ksort($v, SORT_STRING);
        }
        return array_map(static fn(mixed $x): mixed => self::sortKeys($x), $v);
    }
}
