<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Testing;

use Sangam\Sdk\Error\TransportException;
use Sangam\Sdk\Http\HttpSender;
use Sangam\Sdk\Http\RetryPolicy;
use Sangam\Sdk\Log\SangamLogger;
use Sangam\Sdk\SangamClient;

/**
 * R18 test engine (M-021): an in-process fake of the Sangam worker API for unit-testing workers without a server.
 *
 * It implements the SDK's HttpSender seam (no sockets, no ext-curl, no dependency), so the real SangamClient and
 * SangamWorker (inline mode) run unchanged against it:
 *
 *     $engine = new SangamTestEngine();
 *     $workId = $engine->addWork('pay', ['amount' => ['type' => 'Decimal', 'value' => '12.50']]);
 *     $worker = new SangamWorker($engine->client(), 'w1', mode: SangamWorker::MODE_INLINE);
 *
 * Semantics (mirror sangam-rest OperatonWorkAdapter, sangam-core LeaseTable, ApiProblem, IdempotencyStore; identical
 * to the Python and TypeScript test engines):
 * - Lease row per item: a grant gives lockVersion + 1 and a new attemptId; a lease that ends without completing (fail,
 *   unlock, expiry, outcome-unknown) gives lockVersion + 1 and no owner; complete / bpmn-error remove it.
 * - Fencing on every worker call in the server's order: no lease, stale version, not owner, expired, then attempt
 *   mismatch -> 409 STALE_LEASE (reason NO_LEASE | STALE_VERSION | NOT_OWNER | EXPIRED | ATTEMPT_MISMATCH), nothing
 *   changes. Unknown work -> 404 NOT_FOUND. A result for a deleted instance -> 410 GONE_INSTANCE_DELETED.
 * - Lease per topic: default 5 min, capped at 24 h, on the engine's own clock (advance()). Every request first sweeps
 *   expired leases: LOCKED -> AVAILABLE (lockVersion + 1), or UNKNOWN_OUTCOME with a reconciliation item when the
 *   topic is irreversible or a cancel was requested and not acknowledged.
 * - fail with retries > 0 -> AVAILABLE after retryTimeoutMs; retries = 0 -> INCIDENT. On an irreversible topic a
 *   failure with retries absent or > 0 becomes outcome-unknown (VI.3 rule 5).
 * - An identical repeat of a completion (same lockVersion, same variables/outcome) answers 204 again; completed once.
 * - R41 for POST /process-instances: same Idempotency-Key + same body -> the stored response with
 *   Idempotent-Replayed: true; same key + another body -> 422 IDEMPOTENCY_KEY_REUSED.
 * - fetch-and-lock answers at once (no long-poll wait); an empty answer comes after a pause of at most 10 ms.
 * - Step journal (M-025, R37): GET/PUT /work/{id}/step?stepName= and GET /work/{id}/steps with the server's
 *   transitions and fencing (EngineSteps).
 */
final class SangamTestEngine implements HttpSender
{
    public const DEFAULT_LOCK_MS = 300_000;
    public const MAX_LOCK_MS = 86_400_000;
    private const API_PREFIX = '/api/v1';

    /** @var list<array{method: string, path: string, idempotencyKey: ?string, body: mixed}> every request */
    public array $calls = [];
    /** @var list<array<string, mixed>> started process instances (ProcessInstance wire arrays) */
    public array $instances = [];
    /** @var list<array<string, mixed>> reconciliation items {id, kind, workId, operationId, state, detail} */
    public array $reconciliationItems = [];
    /** @var (\Closure(array{method: string, path: string, idempotencyKey: ?string, body: mixed}): void)|null runs after each request is applied */
    public ?\Closure $onCall = null;

    /** @var array<string, bool> */
    private array $irreversible = [];
    private int $now;
    /** @var array<string, array<string, mixed>> work items by id, in insertion order */
    private array $work = [];
    /** @var array<string, int> */
    private array $lose = [];
    /** @var array<string, array{0: string, 1: int, 2: array<string, mixed>}> */
    private array $idem = [];
    private int $reconSeq = 0;
    /** M-025 step journal: GET/PUT /work/{id}/step?stepName=, GET /work/{id}/steps. */
    private readonly EngineSteps $journal;

    /** @param list<string> $irreversibleTopics */
    public function __construct(array $irreversibleTopics = [], private readonly string $tenantId = 'test',
                                ?int $startMs = null)
    {
        foreach ($irreversibleTopics as $t) {
            $this->irreversible[$t] = true;
        }
        $this->now = $startMs ?? (int) floor(microtime(true) * 1000);
        $this->journal = new EngineSteps(
            fn(string $id): ?array => $this->work[$id] ?? null,
            function (string $id, array $lease): void {
                $this->fence($id, $lease);
            },
            fn(): string => self::iso($this->now),
        );
    }

    /** A SangamClient on this engine (no transport retries unless given; the worker's own retries stay). */
    public function client(?SangamLogger $logger = null, ?RetryPolicy $retryPolicy = null): SangamClient
    {
        return new SangamClient('http://sangam-test-engine', logger: $logger, retryPolicy: $retryPolicy ?? RetryPolicy::none(),
            sender: $this);
    }

    // ------------------------------------------------------------------ clock and steering

    public function nowMs(): int
    {
        return $this->now;
    }

    /** Moves the engine clock forward; leases expire on the next request. */
    public function advance(int $ms): void
    {
        if ($ms < 0) {
            throw new \InvalidArgumentException('the clock only moves forward');
        }
        $this->now += $ms;
    }

    /**
     * Adds AVAILABLE external work and returns its id. Its operationId is fixed for the life of the item (R36).
     *
     * @param array<string, mixed> $variables VariableMap (plain JSON or R33 TypedValue arrays)
     */
    public function addWork(string $topic, array $variables = [], ?int $retries = 3, int $priority = 0,
                            ?string $businessKey = null, string $processDefinitionKey = 'test-process',
                            ?string $processInstanceId = null): string
    {
        $id = self::uuid();
        $this->work[$id] = [
            'id' => $id, 'topic' => $topic, 'variables' => $variables, 'retries' => $retries, 'priority' => $priority,
            'businessKey' => $businessKey, 'processDefinitionKey' => $processDefinitionKey,
            'processInstanceId' => $processInstanceId ?? self::uuid(),
            'activityInstanceId' => 'task:' . substr(self::uuid(), 0, 12), 'tenantId' => $this->tenantId,
            'operationId' => self::uuid(), 'state' => 'AVAILABLE', 'availableAt' => 0, 'errorMessage' => null,
            'row' => null, 'cancelRequested' => false, 'deleted' => false, 'ledgerState' => 'INTENT', 'attempts' => 0,
            'storedOutcome' => null, 'completion' => null, 'completions' => 0, 'output' => null, 'errorCode' => null,
        ];
        return $id;
    }

    /** CANCEL_REQUESTED: the next heartbeat answers cancelRequested: true. */
    public function requestCancel(string $workId): void
    {
        $this->get($workId);
        $this->work[$workId]['cancelRequested'] = true;
    }

    /** Deletes the work's instance (tombstone): late results get 410 GONE_INSTANCE_DELETED. */
    public function deleteInstance(string $workId): void
    {
        $this->get($workId);
        $this->work[$workId]['deleted'] = true;
    }

    /** CONFIRM_SUCCESS -> COMPLETED, RETRY -> AVAILABLE with the same operationId, MARK_FAILED -> FAILED. */
    public function resolveReconciliation(string $itemId, string $action): void
    {
        foreach ($this->reconciliationItems as $i => $item) {
            if ($item['id'] !== $itemId || $item['state'] !== 'OPEN') {
                continue;
            }
            $wid = $item['workId'];
            match ($action) {
                'CONFIRM_SUCCESS' => $this->setWork($wid, ['state' => 'COMPLETED', 'ledgerState' => 'SUCCEEDED',
                    'completions' => $this->work[$wid]['completions'] + 1]),
                'RETRY' => $this->setWork($wid, ['state' => 'AVAILABLE', 'ledgerState' => 'INTENT',
                    'availableAt' => $this->now]),
                'MARK_FAILED' => $this->setWork($wid, ['state' => 'FAILED', 'ledgerState' => 'FAILED']),
                default => throw new \InvalidArgumentException('unknown resolution ' . $action),
            };
            $this->reconciliationItems[$i]['state'] = 'RESOLVED';
            $this->reconciliationItems[$i]['resolution'] = $action;
            return;
        }
        throw new \InvalidArgumentException('no open reconciliation item ' . $itemId);
    }

    /** The next call of $action is applied, then the client sees a transport error (no answer). */
    public function loseNextResponse(string $action): void
    {
        $this->lose[$action] = ($this->lose[$action] ?? 0) + 1;
    }

    // ------------------------------------------------------------------ inspection

    /** AVAILABLE | LOCKED | CANCEL_REQUESTED | COMPLETED | COMPLETED_WITH_ERROR | INCIDENT | UNKNOWN_OUTCOME | CANCELLED | FAILED */
    public function state(string $workId): string
    {
        $w = $this->get($workId);
        return $w['state'] === 'LOCKED' && $w['cancelRequested'] ? 'CANCEL_REQUESTED' : $w['state'];
    }

    public function lockVersion(string $workId): int
    {
        $w = $this->get($workId);
        return $w['row'] === null ? 0 : (int) $w['row']['lockVersion'];
    }

    public function completions(string $workId): int
    {
        return $this->get($workId)['completions'];
    }

    /** @return array<string, mixed> */
    public function snapshot(string $workId): array
    {
        $w = $this->get($workId);
        return [
            'id' => $w['id'], 'topic' => $w['topic'], 'state' => $this->state($workId),
            'lockVersion' => $this->lockVersion($workId), 'attemptId' => $w['row']['attemptId'] ?? null,
            'workerId' => $w['row']['workerId'] ?? null, 'operationId' => $w['operationId'], 'attempts' => $w['attempts'],
            'ledgerState' => $w['ledgerState'], 'retries' => $w['retries'], 'completions' => $w['completions'],
            'output' => $w['output'], 'errorCode' => $w['errorCode'], 'errorMessage' => $w['errorMessage'],
        ];
    }

    /** @return list<array{method: string, path: string, idempotencyKey: ?string, body: mixed}> */
    public function callsOf(string $action): array
    {
        return array_values(array_filter($this->calls,
            static fn(array $c): bool => substr($c['path'], (int) strrpos($c['path'], '/') + 1) === $action));
    }

    // ------------------------------------------------------------------ HttpSender seam

    public function send(string $method, string $url, array $headers, ?string $payload, int $timeoutMs,
                         int $connectTimeoutMs, ?\Closure $abort): array
    {
        $path = (string) parse_url($url, PHP_URL_PATH);
        if (str_starts_with($path, self::API_PREFIX)) {
            $path = substr($path, strlen(self::API_PREFIX));
        }
        $key = null;
        foreach ($headers as $k => $v) {
            if (strtolower((string) $k) === 'idempotency-key') {
                $key = (string) $v;
            }
        }
        $body = null;
        if ($payload !== null && $payload !== '') {
            $body = json_decode($payload, true, 512, JSON_BIGINT_AS_STRING);
        }
        $query = [];
        parse_str((string) parse_url($url, PHP_URL_QUERY), $query);
        [$status, $outHeaders, $out, $lose] = $this->handle($method, $path, $key, $body, $query);
        if ($this->onCall !== null) {
            ($this->onCall)(['method' => $method, 'path' => $path, 'idempotencyKey' => $key, 'body' => $body]);
        }
        if ($lose) {
            throw new TransportException('connection closed by the test engine (lost response)');
        }
        if ($path === '/work/fetch-and-lock' && $out === []) {
            $wait = is_array($body) && is_int($body['asyncResponseTimeoutMs'] ?? null) ? $body['asyncResponseTimeoutMs'] : 10;
            usleep(min(10, max(0, $wait)) * 1000);
        }
        $lower = [];
        foreach ($outHeaders as $k => $v) {
            $lower[strtolower($k)] = $v;
        }
        $raw = '';
        if ($out !== null) {
            $raw = (string) json_encode($out, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION);
            $lower['content-type'] ??= 'application/json';
        }
        return [$status, $lower, $raw];
    }

    /**
     * One request without HTTP.
     *
     * @param array<string, mixed> $query decoded query parameters (the step journal's GET reads its fence from them)
     * @return array{0: int, 1: array<string, string>, 2: mixed, 3: bool} status, headers, JSON body (null = none),
     *                                                                    lose the answer
     */
    public function handle(string $method, string $path, ?string $key, mixed $body, array $query = []): array
    {
        $this->calls[] = ['method' => $method, 'path' => $path, 'idempotencyKey' => $key, 'body' => $body];
        $action = substr($path, (int) strrpos($path, '/') + 1);
        $lose = ($this->lose[$action] ?? 0) > 0;
        if ($lose) {
            $this->lose[$action]--;
        }
        $b = is_array($body) && !array_is_list($body) ? $body : [];
        try {
            $this->sweep();
            [$status, $headers, $out] = $this->route($method, $path, $key, $b, $query);
            return [$status, $headers, $out, $lose];
        } catch (EngineProblem $p) {
            return [$p->status, ['Content-Type' => 'application/problem+json'], $p->body(self::API_PREFIX . $path), $lose];
        }
    }

    /**
     * @param array<string, mixed> $b
     * @param array<string, mixed> $query
     * @return array{0: int, 1: array<string, string>, 2: mixed}
     */
    private function route(string $method, string $path, ?string $key, array $b, array $query = []): array
    {
        $parts = array_values(array_filter(explode('/', $path), static fn(string $p): bool => $p !== ''));
        if (count($parts) === 3 && $parts[0] === 'work' && ($parts[2] === 'steps' || $parts[2] === 'step')) {
            // UI-DEMO-1: as on the server, one step is /step with the name in the stepName query ('/' allowed)
            $name = null;
            if ($parts[2] === 'step') {
                $name = $query['stepName'] ?? null;
                if (!is_string($name)) {
                    throw new EngineProblem(400, 'VALIDATION_FAILED', 'stepName is required');
                }
            }
            return $this->journal->route($method, rawurldecode($parts[1]), $name, $query, $b);
        }
        if ($method === 'POST' && $parts === ['process-instances']) {
            return $this->start($key, $b);
        }
        if ($method === 'POST' && $parts === ['work', 'fetch-and-lock']) {
            return [200, [], $this->fetchAndLock($b)];
        }
        if ($method === 'POST' && count($parts) === 3 && $parts[0] === 'work') {
            $wid = rawurldecode($parts[1]);
            switch ($parts[2]) {
                case 'extend-lock':
                    return [200, [], $this->extend($wid, $b)];
                case 'complete':
                    $this->complete($wid, $b);
                    return [204, [], null];
                case 'fail':
                    $this->fail($wid, $b);
                    return [204, [], null];
                case 'bpmn-error':
                    $this->bpmnError($wid, $b);
                    return [204, [], null];
                case 'unlock':
                    $this->unlock($wid, $b);
                    return [204, [], null];
                case 'outcome-unknown':
                    return [202, [], $this->outcomeUnknown($wid, $b)];
                case 'cancel-ack':
                    $this->cancelAck($wid, $b);
                    return [204, [], null];
            }
        }
        throw new EngineProblem(404, 'NOT_FOUND', $method . ' ' . $path . ' is not served by the test engine');
    }

    // ------------------------------------------------------------------ lease table

    /** @return array<string, mixed> */
    private function get(string $workId): array
    {
        if (!isset($this->work[$workId])) {
            throw new \InvalidArgumentException('no work ' . $workId);
        }
        return $this->work[$workId];
    }

    /** @param array<string, mixed> $changes */
    private function setWork(string $workId, array $changes): void
    {
        $this->work[$workId] = array_merge($this->work[$workId], $changes);
    }

    private function sweep(): void
    {
        foreach ($this->work as $id => $w) {
            $row = $w['row'];
            if ($w['state'] === 'LOCKED' && $row !== null && $row['expiresAt'] !== null && $row['expiresAt'] <= $this->now) {
                if ($w['cancelRequested'] || isset($this->irreversible[$w['topic']])) {
                    $this->toUnknown($id, $w['cancelRequested'] ? 'cancel not acknowledged before lease expiry'
                        : 'lease expired on irreversible topic');
                } else {
                    $this->end($id);
                    $this->setWork($id, ['state' => 'AVAILABLE']);
                }
            }
        }
    }

    /** LeaseTable.end: lock_version + 1, no owner. */
    private function end(string $id): void
    {
        $lv = $this->work[$id]['row']['lockVersion'] ?? 0;
        $this->setWork($id, ['row' => ['lockVersion' => $lv + 1, 'attemptId' => null, 'workerId' => null, 'expiresAt' => null]]);
    }

    /** @param array<string, mixed> $b */
    private function fence(string $id, array $b): void
    {
        $worker = $b['workerId'] ?? null;
        if (!is_string($worker) || $worker === '') {
            throw new EngineProblem(400, 'WORKER_ID_REQUIRED', 'workerId is required');
        }
        $lv = $b['lockVersion'] ?? null;
        $r = $this->work[$id]['row'];
        if ($r === null) {
            throw EngineProblem::stale($id, 'NO_LEASE', 'no lease was granted for this work');
        }
        if ($lv !== $r['lockVersion']) {
            throw EngineProblem::stale($id, 'STALE_VERSION',
                'lockVersion ' . var_export($lv, true) . ' is not the current version ' . $r['lockVersion']);
        }
        if ($r['workerId'] === null || $r['workerId'] !== $worker) {
            throw EngineProblem::stale($id, 'NOT_OWNER',
                $worker . ' does not hold the lease (owner: ' . ($r['workerId'] ?? 'null') . ')');
        }
        if ($r['expiresAt'] === null || $r['expiresAt'] <= $this->now) {
            throw EngineProblem::stale($id, 'EXPIRED', 'the lease of ' . $worker . ' expired at ' . self::iso((int) ($r['expiresAt'] ?? 0)));
        }
        $att = $b['attemptId'] ?? null;
        if ($att !== null && $r['attemptId'] !== null && $att !== $r['attemptId']) {
            throw EngineProblem::stale($id, 'ATTEMPT_MISMATCH', 'attemptId ' . (string) $att . ' is not the current attempt');
        }
    }

    /** requireTask: the external task exists while it is not finished. */
    private function task(string $wid): string
    {
        $w = $this->work[$wid] ?? null;
        // an incident or an outcome awaiting reconciliation keeps the external task, so a call on such work reaches the
        // fence (409 STALE_LEASE: outcome-unknown ended the lease) as on both server work adapters (SL-174)
        if ($w === null || $w['deleted']
            || !in_array($w['state'], ['AVAILABLE', 'LOCKED', 'INCIDENT', 'UNKNOWN_OUTCOME'], true)) {
            throw EngineProblem::notFound('external work', $wid);
        }
        return $wid;
    }

    private function gone(string $wid, bool $effect, mixed $result): void
    {
        $w = $this->work[$wid] ?? null;
        if ($w === null || !$w['deleted']) {
            return;
        }
        $item = $effect ? $this->openRecon($wid, 'LATE_RESULT', 'late result for a deleted instance: ' . self::canonical($result)) : null;
        throw new EngineProblem(410, 'GONE_INSTANCE_DELETED', 'The instance of this work was deleted; the result was recorded',
            null, ['workId' => $wid, 'reconciliationItemId' => $item['id'] ?? null]);
    }

    /** @return array<string, mixed> */
    private function openRecon(string $wid, string $kind, ?string $detail): array
    {
        $item = ['id' => (string) (++$this->reconSeq), 'kind' => $kind, 'workId' => $wid,
            'operationId' => $this->work[$wid]['operationId'], 'state' => 'OPEN', 'detail' => $detail];
        $this->reconciliationItems[] = $item;
        return $item;
    }

    /** @return array<string, mixed> */
    private function toUnknown(string $wid, ?string $detail): array
    {
        if ($this->work[$wid]['row'] !== null) {
            $this->end($wid);
        }
        $this->setWork($wid, ['state' => 'UNKNOWN_OUTCOME', 'ledgerState' => 'UNKNOWN_OUTCOME']);
        return $this->openRecon($wid, 'UNKNOWN_OUTCOME', $detail);
    }

    // ------------------------------------------------------------------ operations

    /**
     * @param array<string, mixed> $b
     * @return array{0: int, 1: array<string, string>, 2: mixed}
     */
    private function start(?string $key, array $b): array
    {
        $fp = self::canonical($b);
        $idemKey = 'POST /process-instances ' . ($key ?? '');
        if ($key !== null && isset($this->idem[$idemKey])) {
            [$storedFp, $status, $pi] = $this->idem[$idemKey];
            if ($storedFp !== $fp) {
                throw new EngineProblem(422, 'IDEMPOTENCY_KEY_REUSED', 'the Idempotency-Key was used before with a different request');
            }
            return [$status, ['Idempotent-Replayed' => 'true', 'Location' => self::API_PREFIX . '/process-instances/' . $pi['id']], $pi];
        }
        if (!is_string($b['key'] ?? null) || $b['key'] === '') {
            throw new EngineProblem(400, 'VALIDATION_FAILED', 'key is required');
        }
        $id = self::uuid();
        $pi = ['id' => $id, 'processDefinitionId' => $b['key'] . ':1:test', 'processDefinitionKey' => $b['key'],
            'processDefinitionVersion' => 1, 'tenantId' => $this->tenantId, 'state' => 'ACTIVE', 'ended' => false,
            'startTime' => self::iso($this->now)];
        if (isset($b['businessKey'])) {
            $pi['businessKey'] = $b['businessKey'];
        }
        $pi['rootProcessInstanceId'] = $id;
        $this->instances[] = $pi;
        if ($key !== null) {
            $this->idem[$idemKey] = [$fp, 201, $pi];
        }
        return [201, ['Location' => self::API_PREFIX . '/process-instances/' . $id], $pi];
    }

    /**
     * @param array<string, mixed> $b
     * @return list<array<string, mixed>>
     */
    private function fetchAndLock(array $b): array
    {
        $worker = $b['workerId'] ?? null;
        if (!is_string($worker) || trim($worker) === '') {
            throw new EngineProblem(400, 'VALIDATION_FAILED', 'workerId is required');
        }
        $topics = $b['topics'] ?? null;
        if (!is_array($topics) || $topics === []) {
            throw new EngineProblem(400, 'VALIDATION_FAILED', 'at least one topic is required');
        }
        $byName = [];
        foreach ($topics as $t) {
            $name = is_array($t) ? ($t['name'] ?? null) : null;
            if (!is_string($name) || trim($name) === '') {
                throw new EngineProblem(400, 'TOPIC_NAME_REQUIRED', 'every topic needs a name');
            }
            $byName[$name] = $t;
        }
        $mt = $b['maxTasks'] ?? null;
        $n = is_int($mt) ? max(1, min(100, $mt)) : 1;
        $ids = array_keys($this->work);
        if (($b['usePriority'] ?? null) === true) {
            $order = array_flip($ids);
            usort($ids, fn(string $x, string $y): int => ($this->work[$y]['priority'] <=> $this->work[$x]['priority'])
                ?: ($order[$x] <=> $order[$y]));
        }
        $out = [];
        foreach ($ids as $id) {
            if (count($out) >= $n) {
                break;
            }
            $w = $this->work[$id];
            $t = $byName[$w['topic']] ?? null;
            if ($t === null || $w['state'] !== 'AVAILABLE' || $w['deleted'] || $w['availableAt'] > $this->now) {
                continue;
            }
            if (isset($t['processDefinitionKey']) && $t['processDefinitionKey'] !== $w['processDefinitionKey']) {
                continue;
            }
            if (isset($t['businessKey']) && $t['businessKey'] !== $w['businessKey']) {
                continue;
            }
            $ms = is_int($t['lockDurationMs'] ?? null) ? $t['lockDurationMs'] : 0;
            $d = $ms <= 0 ? self::DEFAULT_LOCK_MS : min($ms, self::MAX_LOCK_MS);
            $version = $w['row'] === null ? 1 : $w['row']['lockVersion'] + 1;
            $this->setWork($id, ['row' => ['lockVersion' => $version, 'attemptId' => self::uuid(), 'workerId' => $worker,
                'expiresAt' => $this->now + $d], 'state' => 'LOCKED', 'cancelRequested' => false,
                'attempts' => $w['attempts'] + 1]);
            $out[] = $this->external($id, $t);
        }
        return $out;
    }

    /**
     * @param array<string, mixed> $t
     * @return array<string, mixed>
     */
    private function external(string $id, array $t): array
    {
        $w = $this->work[$id];
        $r = $w['row'];
        $names = is_array($t['variables'] ?? null) ? $t['variables'] : null;
        $vars = [];
        foreach ($w['variables'] as $k => $v) {
            if ($names === null || in_array($k, $names, true)) {
                $vars[$k] = $v;
            }
        }
        $op = ['state' => $w['ledgerState'], 'attempts' => $w['attempts'],
            'mayDispatch' => $w['ledgerState'] !== 'SUCCEEDED' && $w['ledgerState'] !== 'UNKNOWN_OUTCOME'];
        if ($w['storedOutcome'] !== null) {
            $op['storedOutcome'] = $w['storedOutcome'];
        }
        $e = ['id' => $id, 'topic' => $w['topic'], 'tenantId' => $w['tenantId'],
            'processInstanceId' => $w['processInstanceId'], 'processDefinitionKey' => $w['processDefinitionKey'],
            'activityId' => 'task', 'activityInstanceId' => $w['activityInstanceId'],
            'variables' => $vars === [] ? new \stdClass() : $vars, 'lockVersion' => $r['lockVersion'],
            'attemptId' => $r['attemptId'], 'operationId' => $w['operationId'], 'lockExpiresAt' => self::iso((int) $r['expiresAt']),
            'priority' => $w['priority'], 'operation' => $op];
        if ($w['retries'] !== null) {
            $e['retries'] = $w['retries'];
        }
        if ($w['businessKey'] !== null) {
            $e['businessKey'] = $w['businessKey'];
        }
        if ($w['errorMessage'] !== null) {
            $e['errorMessage'] = $w['errorMessage'];
        }
        return $e;
    }

    /**
     * @param array<string, mixed> $b
     * @return array<string, mixed>
     */
    private function extend(string $wid, array $b): array
    {
        $this->fence($this->task($wid), $b);
        $ms = is_int($b['newDurationMs'] ?? null) ? $b['newDurationMs'] : 0;
        $d = $ms <= 0 ? self::DEFAULT_LOCK_MS : min($ms, self::MAX_LOCK_MS);
        $row = $this->work[$wid]['row'];
        $row['expiresAt'] = $this->now + $d;
        $this->setWork($wid, ['row' => $row]);
        return ['id' => $wid, 'lockVersion' => $b['lockVersion'] ?? null, 'lockExpiresAt' => self::iso($row['expiresAt']),
            'cancelRequested' => $this->work[$wid]['cancelRequested']];
    }

    /** @param array<string, mixed> $b */
    private function complete(string $wid, array $b): void
    {
        $this->gone($wid, true, $b['variables'] ?? null);
        $outcome = self::canonical(['output' => $b['variables'] ?? null, 'outcome' => $b['outcome'] ?? null]);
        $done = $this->work[$wid] ?? null;
        if ($done !== null && $done['state'] === 'COMPLETED' && $done['completion'] === [$b['lockVersion'] ?? null, $outcome]) {
            return; // the same completion again (a retried request): answered as the first one
        }
        $this->fence($this->task($wid), $b);
        $output = is_array($b['variables'] ?? null) ? $b['variables'] : [];
        $ref = is_array($b['outcome'] ?? null) ? ($b['outcome']['reference'] ?? null) : null;
        $this->setWork($wid, ['state' => 'COMPLETED', 'ledgerState' => 'SUCCEEDED', 'row' => null,
            'completion' => [$b['lockVersion'] ?? null, $outcome], 'completions' => $this->work[$wid]['completions'] + 1,
            'output' => $output, 'storedOutcome' => ['output' => $output, 'providerRef' => $ref, 'duplicate' => false]]);
    }

    /** @param array<string, mixed> $b */
    private function fail(string $wid, array $b): void
    {
        $probe = $this->work[$wid] ?? null;
        $retries = $b['retries'] ?? null;
        if ($probe !== null && isset($this->irreversible[$probe['topic']]) && ($retries === null || $retries > 0)) {
            $this->outcomeUnknown($wid, ['workerId' => $b['workerId'] ?? null, 'lockVersion' => $b['lockVersion'] ?? null,
                'attemptId' => $b['attemptId'] ?? null,
                'detail' => 'failure on irreversible topic: ' . (string) ($b['errorMessage'] ?? '')]);
            return;
        }
        $this->gone($wid, false, null);
        $this->fence($this->task($wid), $b);
        $left = is_int($retries) ? max(0, $retries) : max(0, ($this->work[$wid]['retries'] ?? 1) - 1);
        $this->end($wid);
        $changes = ['retries' => $left, 'errorMessage' => is_string($b['errorMessage'] ?? null) ? $b['errorMessage'] : null];
        if ($left > 0) {
            $wait = is_int($b['retryTimeoutMs'] ?? null) ? max(0, $b['retryTimeoutMs']) : 0;
            $changes += ['state' => 'AVAILABLE', 'availableAt' => $this->now + $wait];
        } else {
            $changes += ['state' => 'INCIDENT', 'ledgerState' => 'FAILED'];
        }
        $this->setWork($wid, $changes);
    }

    /** @param array<string, mixed> $b */
    private function bpmnError(string $wid, array $b): void
    {
        $code = $b['errorCode'] ?? null;
        if (!is_string($code) || trim($code) === '') {
            throw new EngineProblem(400, 'VALIDATION_FAILED', 'errorCode is required');
        }
        $this->gone($wid, false, $b['variables'] ?? null);
        $this->fence($this->task($wid), $b);
        $this->setWork($wid, ['state' => 'COMPLETED_WITH_ERROR', 'row' => null, 'errorCode' => $code,
            'errorMessage' => is_string($b['errorMessage'] ?? null) ? $b['errorMessage'] : null]);
    }

    /** @param array<string, mixed> $b */
    private function unlock(string $wid, array $b): void
    {
        $this->fence($this->task($wid), $b);
        $this->end($wid);
        $this->setWork($wid, ['state' => 'AVAILABLE']);
    }

    /**
     * @param array<string, mixed> $b
     * @return array<string, mixed>
     */
    private function outcomeUnknown(string $wid, array $b): array
    {
        $this->gone($wid, true, $b['evidence'] ?? null);
        $this->fence($this->task($wid), $b);
        $item = $this->toUnknown($wid, is_string($b['detail'] ?? null) ? $b['detail'] : null);
        return ['workId' => $wid, 'operationId' => $this->work[$wid]['operationId'],
            'reconciliationItemId' => $item['id'], 'state' => 'UNKNOWN_OUTCOME'];
    }

    /** @param array<string, mixed> $b */
    private function cancelAck(string $wid, array $b): void
    {
        $w = $this->work[$wid] ?? null;
        if ($w === null || !$w['cancelRequested'] || ($w['state'] !== 'LOCKED' && $w['state'] !== 'AVAILABLE')) {
            throw EngineProblem::notFound('cancel request for work', $wid);
        }
        $current = $w['row']['lockVersion'] ?? null;
        if (($b['lockVersion'] ?? null) !== $current) {
            throw EngineProblem::stale($wid, 'STALE_VERSION',
                'no CANCEL_REQUESTED tombstone with lockVersion ' . var_export($b['lockVersion'] ?? null, true));
        }
        $this->setWork($wid, ['state' => 'CANCELLED', 'row' => null]);
    }

    // ------------------------------------------------------------------ helpers

    private static function canonical(mixed $v): string
    {
        return (string) json_encode(self::sortKeys($v), JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION);
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

    private static function iso(int $ms): string
    {
        $d = \DateTimeImmutable::createFromFormat('U.v', sprintf('%d.%03d', intdiv($ms, 1000), $ms % 1000),
            new \DateTimeZone('UTC'));
        return $d === false ? gmdate('Y-m-d\TH:i:s\Z', intdiv($ms, 1000)) : $d->format('Y-m-d\TH:i:s.v\Z');
    }

    private static function uuid(): string
    {
        $b = random_bytes(16);
        $b[6] = chr((ord($b[6]) & 0x0f) | 0x40);
        $b[8] = chr((ord($b[8]) & 0x3f) | 0x80);
        $h = bin2hex($b);
        return substr($h, 0, 8) . '-' . substr($h, 8, 4) . '-' . substr($h, 12, 4) . '-' . substr($h, 16, 4) . '-'
            . substr($h, 20, 12);
    }
}
