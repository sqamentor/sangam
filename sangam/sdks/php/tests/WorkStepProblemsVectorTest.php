<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Sangam\Sdk\Testing\SangamTestEngine;

/**
 * SL-174: step routes and lease calls on work that is not open answer exactly what the server answers. The expected
 * problems come from sdks/testing/work-step-problems.json, which the server's WorkStepJournalH2Test and
 * WorkCallProblemsVectorH2Test check against the server work adapters too, so the engine and the server are compared
 * through one table.
 */
final class WorkStepProblemsVectorTest extends TestCase
{
    private const VECTORS = __DIR__ . '/../../testing/work-step-problems.json';
    private const FIXED = ['id', 'scenario', 'call', 'status', 'code'];
    private const CALL_BODY = [
        'complete' => ['variables' => []],
        'fail' => ['errorMessage' => 'again', 'retries' => 1],
        'bpmn-error' => ['errorCode' => 'E1'],
        'unlock' => [],
        'extend-lock' => ['newDurationMs' => 60000],
        'outcome-unknown' => ['detail' => 'unsure'],
    ];

    /** @return array<string, mixed> */
    private static function vectors(): array
    {
        $file = json_decode((string) file_get_contents(self::VECTORS), true, 512, JSON_THROW_ON_ERROR);
        self::assertIsArray($file);
        return $file;
    }

    /** @return array<string, array{0: array<string, mixed>}> */
    public static function cases(): array
    {
        $out = [];
        foreach (self::vectors()['cases'] as $c) {
            $out[(string) $c['id']] = [$c];
        }
        return $out;
    }

    /** @param array<string, mixed> $case */
    #[DataProvider('cases')]
    public function testStepRoutesOnWorkThatIsNotOpenAnswerTheServersProblem(array $case): void
    {
        $file = self::vectors();
        $e = new SangamTestEngine();
        $wid = $e->addWork('pay');
        [$s, , $fetched] = $e->handle('POST', '/work/fetch-and-lock', null,
            ['workerId' => 'w1', 'topics' => [['name' => 'pay']]]);
        self::assertSame(200, $s);
        $w = $fetched[0];
        $lease = ['workerId' => 'w1', 'lockVersion' => $w['lockVersion'], 'attemptId' => $w['attemptId']];
        $target = $wid;
        switch ($case['scenario']) {
            case 'UNKNOWN':
                $target = (string) $file['unknownWorkId'];
                break;
            case 'COMPLETED':
                self::assertSame(204, $e->handle('POST', '/work/' . $wid . '/complete', null,
                    $lease + ['variables' => []])[0]);
                break;
            case 'DELETED':
                $e->deleteInstance($wid);
                break;
            case 'RELEASED':
                self::assertSame(204, $e->handle('POST', '/work/' . $wid . '/unlock', null, $lease)[0]);
                break;
            case 'UNKNOWN_OUTCOME':
                self::awaitReconciliation($e, $wid, $lease);
                break;
            default:
                self::fail('unknown scenario ' . $case['scenario']);
        }
        $path = '/work/' . $target . '/steps/' . rawurlencode((string) $file['stepName']);
        [$s, , $body] = $e->handle('GET', $path, null, null,
            ['workerId' => 'w1', 'lockVersion' => (string) $w['lockVersion'], 'attemptId' => $w['attemptId']]);
        self::expectProblem($case, 'GET', $s, $body);
        [$s, , $body] = $e->handle('PUT', $path, null, $lease + ['state' => 'INTENT']);
        self::expectProblem($case, 'PUT', $s, $body);
    }

    /** @return array<string, array{0: array<string, mixed>}> */
    public static function workCalls(): array
    {
        $out = [];
        foreach (self::vectors()['workCalls'] as $c) {
            $out[(string) $c['id']] = [$c];
        }
        return $out;
    }

    /** @param array<string, mixed> $case */
    #[DataProvider('workCalls')]
    public function testLeaseCallsOnWorkThatIsNotOpenAnswerTheServersProblem(array $case): void
    {
        $e = new SangamTestEngine();
        $wid = $e->addWork('pay');
        [$s, , $fetched] = $e->handle('POST', '/work/fetch-and-lock', null,
            ['workerId' => 'w1', 'topics' => [['name' => 'pay']]]);
        self::assertSame(200, $s);
        $w = $fetched[0];
        $lease = ['workerId' => 'w1', 'lockVersion' => $w['lockVersion'], 'attemptId' => $w['attemptId']];
        switch ($case['scenario']) {
            case 'INCIDENT':
                self::assertSame(204, $e->handle('POST', '/work/' . $wid . '/fail', null,
                    $lease + ['errorMessage' => 'no retries left', 'retries' => 0])[0]);
                self::assertSame('INCIDENT', $e->state($wid));
                break;
            case 'UNKNOWN_OUTCOME':
                self::awaitReconciliation($e, $wid, $lease);
                break;
            default:
                self::fail('unknown scenario ' . $case['scenario']);
        }
        $call = (string) $case['call'];
        self::assertArrayHasKey($call, self::CALL_BODY);
        [$s, , $body] = $e->handle('POST', '/work/' . $wid . '/' . $call, null, $lease + self::CALL_BODY[$call]);
        self::expectProblem($case, $call, $s, $body);
    }

    public function testEveryVectorIsRead(): void
    {
        self::assertGreaterThanOrEqual(5, count(self::vectors()['cases']));
        self::assertGreaterThanOrEqual(12, count(self::vectors()['workCalls']));
    }

    /**
     * The UNKNOWN_OUTCOME scenario: the worker reports an unknown outcome; the work awaits reconciliation.
     *
     * @param array<string, mixed> $lease
     */
    private static function awaitReconciliation(SangamTestEngine $e, string $wid, array $lease): void
    {
        self::assertSame(202, $e->handle('POST', '/work/' . $wid . '/outcome-unknown', null,
            $lease + ['detail' => 'unsure'])[0]);
        self::assertSame('UNKNOWN_OUTCOME', $e->state($wid));
    }

    /** @param array<string, mixed> $case */
    private static function expectProblem(array $case, string $route, int $status, mixed $body): void
    {
        $what = $case['id'] . ' ' . $route;
        self::assertIsArray($body, $what);
        self::assertSame($case['status'], $status, $what);
        self::assertSame($case['code'], $body['code'] ?? null, $what);
        foreach ($case as $k => $v) {
            if (!in_array($k, self::FIXED, true)) {
                self::assertSame($v, $body[$k] ?? null, $what . ' ' . $k);
            }
        }
    }
}
