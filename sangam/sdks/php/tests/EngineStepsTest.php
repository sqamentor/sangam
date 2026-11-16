<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests;

use PHPUnit\Framework\TestCase;
use Sangam\Sdk\Testing\SangamTestEngine;

/** M-025: the test engine's step journal (R37) has the server's transitions, fencing and error codes. */
final class EngineStepsTest extends TestCase
{
    private SangamTestEngine $e;
    private string $wid;
    /** @var array<string, mixed> */
    private array $w;

    protected function setUp(): void
    {
        $this->e = new SangamTestEngine();
        $this->wid = $this->e->addWork('pay');
        $this->w = $this->fetch();
    }

    /** @return array<string, mixed> */
    private function fetch(): array
    {
        [$s, , $b] = $this->e->handle('POST', '/work/fetch-and-lock', null,
            ['workerId' => 'w1', 'topics' => [['name' => 'pay']]]);
        self::assertSame(200, $s);
        return $b[0];
    }

    /**
     * @param array<string, mixed> $extra
     * @return array{0: int, 1: array<string, mixed>}
     */
    private function put(string $name, string $state, array $extra = []): array
    {
        $body = $extra + ['workerId' => 'w1', 'lockVersion' => $this->w['lockVersion'],
            'attemptId' => $this->w['attemptId'], 'state' => $state];
        [$s, , $out] = $this->e->handle('PUT', '/work/' . $this->wid . '/step', null, $body,
            ['stepName' => $name]);
        return [$s, is_array($out) ? $out : []];
    }

    /**
     * @param array<string, mixed> $query
     * @return array{0: int, 1: mixed}
     */
    private function get(string $name, array $query = []): array
    {
        $query += ['workerId' => 'w1', 'lockVersion' => (string) $this->w['lockVersion'],
            'attemptId' => $this->w['attemptId'], 'stepName' => $name];
        [$s, , $out] = $this->e->handle('GET', '/work/' . $this->wid . '/step', null, null, $query);
        return [$s, $out];
    }

    public function testOutcomeNeedsAnIntent(): void
    {
        [$s, $p] = $this->put('a', 'SUCCEEDED', ['result' => 1]);
        self::assertSame([409, 'STEP_INTENT_REQUIRED'], [$s, $p['code']]);
        [$s, $p] = $this->put('a', 'FAILED', ['error' => 'x']);
        self::assertSame([409, 'STEP_INTENT_REQUIRED'], [$s, $p['code']]);
        [$s, $p] = $this->get('a');
        self::assertSame([404, 'NOT_FOUND'], [$s, $p['code']]);
    }

    public function testIntentThenSucceededIsFinalUnlessIdentical(): void
    {
        [$s, $row] = $this->put('a', 'INTENT');
        self::assertSame(200, $s);
        self::assertSame('INTENT', $row['state']);
        self::assertSame($this->w['operationId'], $row['operationId']);
        self::assertSame($this->w['attemptId'], $row['attemptId']);
        self::assertNotEmpty($row['recordedAt']);
        [$s, $row] = $this->put('a', 'SUCCEEDED', ['result' => ['id' => 'P1', 'n' => 2], 'providerRef' => 'P1']);
        self::assertSame([200, 'SUCCEEDED'], [$s, $row['state']]);
        // identical repeat (same result, key order irrelevant, same providerRef) is a no-op
        [$s] = $this->put('a', 'SUCCEEDED', ['result' => ['n' => 2, 'id' => 'P1'], 'providerRef' => 'P1']);
        self::assertSame(200, $s);
        [$s, $p] = $this->put('a', 'SUCCEEDED', ['result' => ['id' => 'P2'], 'providerRef' => 'P2']);
        self::assertSame([409, 'STEP_ALREADY_RECORDED'], [$s, $p['code']]);
        [$s, $p] = $this->put('a', 'INTENT');
        self::assertSame([409, 'STEP_ALREADY_RECORDED'], [$s, $p['code']]);
        [$s, $got] = $this->get('a');
        self::assertSame(200, $s);
        self::assertSame(['SUCCEEDED', ['id' => 'P1', 'n' => 2], 'P1'], [$got['state'], $got['result'], $got['providerRef']]);
    }

    public function testFailedThenIntentAgainAndIdenticalRepeats(): void
    {
        self::assertSame(200, $this->put('a', 'INTENT')[0]);
        self::assertSame(200, $this->put('a', 'INTENT')[0]);
        [$s, $row] = $this->put('a', 'FAILED', ['error' => 'rejected']);
        self::assertSame([200, 'FAILED', 'rejected'], [$s, $row['state'], $row['error']]);
        self::assertSame(200, $this->put('a', 'FAILED', ['error' => 'rejected'])[0]);
        [$s, $p] = $this->put('a', 'FAILED', ['error' => 'other']);
        self::assertSame([409, 'STEP_INTENT_REQUIRED'], [$s, $p['code']]);
        [$s, $p] = $this->put('a', 'SUCCEEDED', ['result' => 1]);
        self::assertSame([409, 'STEP_INTENT_REQUIRED'], [$s, $p['code']]);
        self::assertSame('INTENT', $this->put('a', 'INTENT')[1]['state']);
        self::assertSame('SUCCEEDED', $this->put('a', 'SUCCEEDED', ['result' => 1])[1]['state']);
    }

    public function testValidation(): void
    {
        [$s, $p] = $this->put('a', 'INTENT', ['attemptId' => null]);
        self::assertSame([400, 'VALIDATION_FAILED'], [$s, $p['code']]);
        [$s, $p] = $this->put(str_repeat('x', 129), 'INTENT');
        self::assertSame([400, 'VALIDATION_FAILED'], [$s, $p['code']]);
        [$s, $p] = $this->put("a\x7f", 'INTENT');
        self::assertSame([400, 'VALIDATION_FAILED'], [$s, $p['code']]);
        [$s, $p] = $this->put('a', 'DONE');
        self::assertSame([400, 'VALIDATION_FAILED'], [$s, $p['code']]);
        [$s, $p] = $this->get('a', ['lockVersion' => '']);
        self::assertSame([400, 'VALIDATION_FAILED'], [$s, $p['code']]);
        [$s, $p] = $this->get('a', ['attemptId' => '']);
        self::assertSame([400, 'VALIDATION_FAILED'], [$s, $p['code']]);
        [$s, , $p] = $this->e->handle('POST', '/work/' . $this->wid . '/step', null, [], ['stepName' => 'a']);
        self::assertSame([405, 'METHOD_NOT_ALLOWED'], [$s, $p['code']]);
    }

    public function testFencingOrder(): void
    {
        [$s, $p] = $this->put('a', 'INTENT', ['lockVersion' => $this->w['lockVersion'] + 1]);
        self::assertSame([409, 'STALE_LEASE', 'STALE_VERSION'], [$s, $p['code'], $p['reason']]);
        [, $p] = $this->put('a', 'INTENT', ['workerId' => 'intruder']);
        self::assertSame('NOT_OWNER', $p['reason']);
        [, $p] = $this->put('a', 'INTENT', ['attemptId' => 'old-attempt']);
        self::assertSame('ATTEMPT_MISMATCH', $p['reason']);
        [$s, $p] = $this->get('a', ['lockVersion' => (string) ($this->w['lockVersion'] + 1)]);
        self::assertSame([409, 'STALE_VERSION'], [$s, $p['reason']]);
        $this->e->advance(SangamTestEngine::DEFAULT_LOCK_MS);
        // the sweep ends the expired lease first (AVAILABLE, lockVersion + 1): the old version is stale
        [$s, $p] = $this->put('a', 'INTENT');
        self::assertSame([409, 'STALE_LEASE'], [$s, $p['code']]);
        [$s, , $p] = $this->e->handle('PUT', '/work/nope/step', null, ['workerId' => 'w1', 'lockVersion' => 1,
            'attemptId' => 'x', 'state' => 'INTENT'], ['stepName' => 'a']);
        self::assertSame([404, 'NOT_FOUND'], [$s, $p['code']]);
    }

    public function testDeletedInstanceIsGone(): void
    {
        $this->e->deleteInstance($this->wid);
        [$s, $p] = $this->put('a', 'INTENT');
        self::assertSame([410, 'GONE_INSTANCE_DELETED'], [$s, $p['code']]);
        [$s, $p] = $this->get('a');
        self::assertSame([410, 'GONE_INSTANCE_DELETED'], [$s, $p['code']]);
    }

    public function testListIsKeyedByTheOperationAcrossLeases(): void
    {
        $this->put('second', 'INTENT');
        $this->put('first', 'INTENT');
        $this->put('second', 'SUCCEEDED', ['result' => 'ok']);
        [$s] = $this->e->handle('POST', '/work/' . $this->wid . '/unlock', null, ['workerId' => 'w1',
            'lockVersion' => $this->w['lockVersion'], 'attemptId' => $this->w['attemptId']]);
        self::assertSame(204, $s);
        $this->w = $this->fetch();
        [$s, $got] = $this->get('second');
        self::assertSame([200, 'SUCCEEDED', 'ok'], [$s, $got['state'], $got['result']]);
        // a new attempt may take over the earlier attempt's INTENT
        self::assertSame(200, $this->put('first', 'INTENT')[0]);
        [$s, , $rows] = $this->e->handle('GET', '/work/' . $this->wid . '/steps', null, null);
        self::assertSame(200, $s);
        self::assertSame(['second', 'first'], array_column($rows, 'name'));
        self::assertSame($this->w['attemptId'], $rows[1]['attemptId']);
        [$s, , $p] = $this->e->handle('GET', '/work/unknown/steps', null, null);
        self::assertSame([404, 'NOT_FOUND'], [$s, $p['code']]);
        [$s, , $rows] = $this->e->handle('GET', '/work/' . $this->e->addWork('pay') . '/steps', null, null);
        self::assertSame([200, []], [$s, $rows]);
    }
}
