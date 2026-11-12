<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests;

use PHPUnit\Framework\TestCase;
use Sangam\Sdk\Error\StaleLeaseException;
use Sangam\Sdk\Testing\SangamTestEngine;

/** M-021: the R18 test engine's state machine (VI.3), through handle() and once through the real client. */
final class TestEngineTest extends TestCase
{
    /**
     * @param array<string, mixed> $extra
     * @return list<array<string, mixed>>
     */
    private static function fetch(SangamTestEngine $e, string $worker = 'w1', string $topic = 'pay', array $extra = []): array
    {
        [$status, , $body] = $e->handle('POST', '/work/fetch-and-lock', null,
            ['workerId' => $worker, 'topics' => [['name' => $topic] + $extra], 'maxTasks' => 10]);
        self::assertSame(200, $status);
        return $body;
    }

    /**
     * @param array<string, mixed> $body
     * @return array{0: int, 1: array<string, mixed>}
     */
    private static function post(SangamTestEngine $e, string $wid, string $action, array $body): array
    {
        [$status, , $out] = $e->handle('POST', '/work/' . $wid . '/' . $action, null, $body);
        return [$status, is_array($out) ? $out : []];
    }

    /**
     * @param array<string, mixed> $w
     * @param array<string, mixed> $extra
     * @return array<string, mixed>
     */
    private static function fence(array $w, array $extra = []): array
    {
        return $extra + ['workerId' => 'w1', 'lockVersion' => $w['lockVersion'], 'attemptId' => $w['attemptId']];
    }

    public function testGrantGivesLockVersionAttemptAndIntentOperation(): void
    {
        $e = new SangamTestEngine();
        $wid = $e->addWork('pay', ['a' => 1]);
        [$w] = self::fetch($e);
        self::assertSame($wid, $w['id']);
        self::assertSame(1, $w['lockVersion']);
        self::assertSame(['state' => 'INTENT', 'attempts' => 1, 'mayDispatch' => true], $w['operation']);
        self::assertSame('LOCKED', $e->state($wid));
        self::assertSame([], self::fetch($e, 'w2'));
    }

    public function testFetchValidation(): void
    {
        $e = new SangamTestEngine();
        [$s, , $b] = $e->handle('POST', '/work/fetch-and-lock', null, ['topics' => [['name' => 'pay']]]);
        self::assertSame([400, 'VALIDATION_FAILED'], [$s, $b['code']]);
        [$s] = $e->handle('POST', '/work/fetch-and-lock', null, ['workerId' => 'w', 'topics' => []]);
        self::assertSame(400, $s);
    }

    public function testFencingOrderAndProblemShape(): void
    {
        $e = new SangamTestEngine();
        $wid = $e->addWork('pay');
        [$w] = self::fetch($e);
        [$s, $p] = self::post($e, $wid, 'complete', self::fence($w, ['lockVersion' => $w['lockVersion'] + 1]));
        self::assertSame(409, $s);
        self::assertSame('STALE_LEASE', $p['code']);
        self::assertSame('STALE_VERSION', $p['reason']);
        self::assertSame('https://sangam.dev/problems/stale-lease', $p['type']);
        self::assertSame('Stale lease', $p['title']);
        self::assertSame($wid, $p['workId']);
        [, $p] = self::post($e, $wid, 'complete', self::fence($w, ['workerId' => 'intruder']));
        self::assertSame('NOT_OWNER', $p['reason']);
        [, $p] = self::post($e, $wid, 'complete', self::fence($w, ['attemptId' => 'old']));
        self::assertSame('ATTEMPT_MISMATCH', $p['reason']);
        self::assertSame('LOCKED', $e->state($wid));
        [$s, $p] = self::post($e, 'nope', 'complete', self::fence($w));
        self::assertSame([404, 'NOT_FOUND'], [$s, $p['code']]);
    }

    public function testExpiryReturnsWorkWithANewVersionAndTheSameOperationId(): void
    {
        $e = new SangamTestEngine();
        $wid = $e->addWork('pay');
        [$w] = self::fetch($e, 'w1', 'pay', ['lockDurationMs' => 10_000]);
        $e->advance(10_000);
        [$w2] = self::fetch($e, 'w2');
        self::assertSame($wid, $w2['id']);
        self::assertGreaterThan($w['lockVersion'], $w2['lockVersion']);
        self::assertNotSame($w['attemptId'], $w2['attemptId']);
        self::assertSame($w['operationId'], $w2['operationId']);
        self::assertSame(409, self::post($e, $wid, 'complete', self::fence($w))[0]);
    }

    public function testIrreversibleExpiryAndUnackedCancelEndInUnknownOutcome(): void
    {
        $e = new SangamTestEngine(['pay']);
        $a = $e->addWork('pay');
        self::fetch($e, 'w1', 'pay', ['lockDurationMs' => 5_000]);
        $e->advance(5_000);
        self::assertSame([], self::fetch($e, 'w2'));
        self::assertSame('UNKNOWN_OUTCOME', $e->state($a));
        $b = $e->addWork('ship');
        self::fetch($e, 'w1', 'ship', ['lockDurationMs' => 5_000]);
        $e->requestCancel($b);
        self::assertSame('CANCEL_REQUESTED', $e->state($b));
        $e->advance(5_000);
        self::fetch($e, 'w1', 'ship');
        self::assertSame('UNKNOWN_OUTCOME', $e->state($b));
    }

    public function testExtendLockKeepsTheVersionAndReportsCancel(): void
    {
        $e = new SangamTestEngine();
        $wid = $e->addWork('pay');
        [$w] = self::fetch($e, 'w1', 'pay', ['lockDurationMs' => 9_000]);
        $e->advance(6_000);
        [$s, $ls] = self::post($e, $wid, 'extend-lock', self::fence($w, ['newDurationMs' => 9_000]));
        self::assertSame(200, $s);
        self::assertSame($w['lockVersion'], $ls['lockVersion']);
        self::assertFalse($ls['cancelRequested']);
        $e->advance(6_000);
        self::assertSame([], self::fetch($e, 'w2'));
        $e->requestCancel($wid);
        [, $ls] = self::post($e, $wid, 'extend-lock', self::fence($w, ['newDurationMs' => 9_000]));
        self::assertTrue($ls['cancelRequested']);
        self::assertSame(204, self::post($e, $wid, 'cancel-ack', self::fence($w))[0]);
        self::assertSame('CANCELLED', $e->state($wid));
    }

    public function testCompletesOnceAndAnswersAnIdenticalRepeat(): void
    {
        $e = new SangamTestEngine();
        $wid = $e->addWork('pay');
        [$w] = self::fetch($e);
        $body = self::fence($w, ['variables' => ['ok' => true]]);
        self::assertSame(204, self::post($e, $wid, 'complete', $body)[0]);
        self::assertSame(204, self::post($e, $wid, 'complete', $body)[0]);
        self::assertSame(1, $e->completions($wid));
        [$s, $p] = self::post($e, $wid, 'complete', self::fence($w, ['variables' => ['ok' => false]]));
        self::assertSame([404, 'NOT_FOUND'], [$s, $p['code']]);
    }

    public function testFailWithRetriesThenIncident(): void
    {
        $e = new SangamTestEngine();
        $wid = $e->addWork('pay', [], 2);
        [$w] = self::fetch($e);
        self::assertSame(204, self::post($e, $wid, 'fail', self::fence($w, ['errorMessage' => 'x', 'retries' => 1,
            'retryTimeoutMs' => 1_000]))[0]);
        self::assertSame('AVAILABLE', $e->state($wid));
        self::assertSame([], self::fetch($e));
        $e->advance(1_000);
        [$w2] = self::fetch($e);
        self::assertSame(1, $w2['retries']);
        self::assertSame('x', $w2['errorMessage']);
        self::assertSame(204, self::post($e, $wid, 'fail', self::fence($w2, ['retries' => 0]))[0]);
        self::assertSame('INCIDENT', $e->state($wid));
    }

    public function testFailureOnAnIrreversibleTopicIsOutcomeUnknown(): void
    {
        $e = new SangamTestEngine(['pay']);
        $wid = $e->addWork('pay');
        [$w] = self::fetch($e);
        self::assertSame(204, self::post($e, $wid, 'fail', self::fence($w, ['errorMessage' => 'timeout']))[0]);
        self::assertSame('UNKNOWN_OUTCOME', $e->state($wid));
    }

    public function testBpmnErrorUnlockOutcomeUnknownAndRetryResolution(): void
    {
        $e = new SangamTestEngine();
        $a = $e->addWork('pay');
        $b = $e->addWork('pay');
        $c = $e->addWork('pay');
        [$wa, $wb, $wc] = self::fetch($e);
        self::assertSame(400, self::post($e, $a, 'bpmn-error', self::fence($wa))[0]);
        self::assertSame(204, self::post($e, $a, 'bpmn-error', self::fence($wa, ['errorCode' => 'E1']))[0]);
        self::assertSame('COMPLETED_WITH_ERROR', $e->state($a));
        self::assertSame(204, self::post($e, $b, 'unlock', self::fence($wb))[0]);
        self::assertSame($wb['lockVersion'] + 1, $e->lockVersion($b));
        [$s, $r] = self::post($e, $c, 'outcome-unknown', self::fence($wc, ['detail' => 'timeout']));
        self::assertSame(202, $s);
        self::assertSame('UNKNOWN_OUTCOME', $r['state']);
        self::assertSame($wc['operationId'], $r['operationId']);
        $e->advance(SangamTestEngine::DEFAULT_LOCK_MS * 3);
        self::assertNotContains($c, array_column(self::fetch($e, 'w9'), 'id'));
        $e->resolveReconciliation($r['reconciliationItemId'], 'RETRY');
        $again = array_values(array_filter(self::fetch($e, 'w9'), static fn(array $x): bool => $x['id'] === $c));
        self::assertSame($wc['operationId'], $again[0]['operationId']);
    }

    public function testAResultForADeletedInstanceAnswersGone(): void
    {
        $e = new SangamTestEngine();
        $wid = $e->addWork('pay');
        [$w] = self::fetch($e);
        $e->deleteInstance($wid);
        [$s, $p] = self::post($e, $wid, 'complete', self::fence($w));
        self::assertSame([410, 'GONE_INSTANCE_DELETED'], [$s, $p['code']]);
        self::assertNotEmpty($p['reconciliationItemId']);
    }

    public function testProcessStartIsIdempotentPerKey(): void
    {
        $e = new SangamTestEngine();
        $a1 = $e->handle('POST', '/process-instances', 'k1', ['key' => 'p1', 'businessKey' => 'BK']);
        $a2 = $e->handle('POST', '/process-instances', 'k1', ['key' => 'p1', 'businessKey' => 'BK']);
        self::assertSame(201, $a1[0]);
        self::assertSame($a1[2], $a2[2]);
        self::assertSame('true', $a2[1]['Idempotent-Replayed']);
        self::assertCount(1, $e->instances);
        $a3 = $e->handle('POST', '/process-instances', 'k1', ['key' => 'p1', 'businessKey' => 'OTHER']);
        self::assertSame([422, 'IDEMPOTENCY_KEY_REUSED'], [$a3[0], $a3[2]['code']]);
    }

    public function testServesTheRealClientThroughTheSenderSeam(): void
    {
        $e = new SangamTestEngine();
        $client = $e->client();
        $wid = $e->addWork('pay');
        [$w] = $client->work()->fetchAndLock('w1', [['name' => 'pay']], null, null);
        self::assertSame($wid, $w->id);
        try {
            $client->work()->complete($wid, 'w1', 99, $w->attemptId, null, null);
            self::fail('expected STALE_LEASE');
        } catch (StaleLeaseException $ex) {
            self::assertSame('STALE_LEASE', $ex->problem->code);
        }
        $client->work()->complete($wid, 'w1', (int) $w->lockVersion, $w->attemptId, ['x' => 1], null);
        self::assertSame('COMPLETED', $e->state($wid));
    }
}
