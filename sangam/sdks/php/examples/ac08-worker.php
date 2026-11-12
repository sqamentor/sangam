<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

/*
 * AC-08 conformance worker (SANGAM.md IX.8 AC-08, M-023) for the PHP SDK.
 *
 * The AC-08 orchestrator runs the same scenario with the worker of every Tier-1 SDK against one server and compares
 * the results. CLI contract (identical in every SDK):
 *   env SANGAM_BASE_URL, SANGAM_TOKEN (optional), AC08_TOPIC, AC08_WORKER_ID,
 *       AC08_SCENARIO = complete | bpmn-error | fail-retry | outcome-unknown | stale-lease,
 *       AC08_TASKS (default 1), AC08_TIMEOUT_S (default 60), AC08_LOCK_MS (lock duration, default 30000)
 *   stdout: one JSON line per task {"lang","workId","operationId","lockVersion","attemptId","scenario","result","detail"}
 *   exit 0 when AC08_TASKS results were printed, 1 on timeout.
 *
 * Per task the variables amount (Decimal), count (Long), when (Date), day (LocalDate) and note (String or null) are
 * read and, in the complete scenarios, written back unchanged as out_amount, out_count, out_when, out_day, out_note.
 * The worker runs in inline mode so one process sees every result. stale-lease uses the work API directly: the
 * runtime's heartbeat would keep the lease alive, so the worker fetches, lets the lease expire (AC08_LOCK_MS + 2 s)
 * and then completes, expecting 409 STALE_LEASE.
 *
 * Run: composer install && php examples/ac08-worker.php
 */

use Sangam\Sdk\Auth\NoAuth;
use Sangam\Sdk\Auth\StaticTokenProvider;
use Sangam\Sdk\Error\StaleLeaseException;
use Sangam\Sdk\Http\CurlSender;
use Sangam\Sdk\Http\HttpSender;
use Sangam\Sdk\Http\RetryPolicy;
use Sangam\Sdk\Log\CallbackLogger;
use Sangam\Sdk\Log\Level;
use Sangam\Sdk\Model\TypedValue;
use Sangam\Sdk\SangamClient;
use Sangam\Sdk\Worker\BpmnError;
use Sangam\Sdk\Worker\OutcomeUnknownException;
use Sangam\Sdk\Worker\SangamWorker;
use Sangam\Sdk\Worker\TaskContext;
use Sangam\Sdk\Worker\TaskFailedException;
use Sangam\Sdk\Worker\TopicSubscription;

$autoload = __DIR__ . '/../vendor/autoload.php';
if (is_file($autoload)) {
    require $autoload;
} else {
    // no composer install: minimal PSR-4 loader for this package
    spl_autoload_register(static function (string $class): void {
        $prefix = 'Sangam\\Sdk\\';
        if (str_starts_with($class, $prefix)) {
            $file = __DIR__ . '/../src/' . str_replace('\\', '/', substr($class, strlen($prefix))) . '.php';
            if (is_file($file)) {
                require $file;
            }
        }
    });
}

const AC08_LANG = 'php';
const AC08_NAMES = ['amount', 'count', 'when', 'day', 'note'];

function ac08Env(string $name, ?string $default = null): string
{
    $v = getenv($name);
    if ($v === false || trim($v) === '') {
        if ($default === null) {
            throw new \RuntimeException($name . ' is required');
        }
        return $default;
    }
    return $v;
}

/** @param array<string, mixed> $raw */
function ac08CheckInputs(array $raw): ?string
{
    $amount = TypedValue::unwrap($raw['amount'] ?? null);
    $count = TypedValue::unwrap($raw['count'] ?? null);
    $note = TypedValue::unwrap($raw['note'] ?? null);
    if (!is_string($amount) || !is_numeric($amount)) {
        return 'amount is not a Decimal';
    }
    if (!is_int($count) && !(is_string($count) && preg_match('/^-?\d+$/D', $count) === 1)) {
        return 'count is not a Long';
    }
    if (TypedValue::toDateTime($raw['when'] ?? null) === null) {
        return 'when is not a Date';
    }
    $day = TypedValue::unwrap($raw['day'] ?? null);
    if (!is_string($day) || preg_match('/^\d{4}-\d{2}-\d{2}$/D', $day) !== 1) {
        return 'day is not a LocalDate';
    }
    if ($note !== null && !is_string($note)) {
        return 'note is not a String';
    }
    return null;
}

/**
 * @param array<string, mixed> $raw
 * @return array<string, mixed>
 */
function ac08Outputs(array $raw): array
{
    $out = [];
    foreach (AC08_NAMES as $n) {
        $out['out_' . $n] = $raw[$n] ?? null;
    }
    return $out;
}

$baseUrl = ac08Env('SANGAM_BASE_URL');
$token = getenv('SANGAM_TOKEN');
$auth = ($token !== false && $token !== '') ? new StaticTokenProvider($token) : new NoAuth();
$topic = ac08Env('AC08_TOPIC');
$workerId = ac08Env('AC08_WORKER_ID', 'ac08-' . AC08_LANG);
$scenario = ac08Env('AC08_SCENARIO', 'complete');
$wanted = (int) ac08Env('AC08_TASKS', '1');
$deadline = microtime(true) + (float) ac08Env('AC08_TIMEOUT_S', '60');
$lockMs = (int) ac08Env('AC08_LOCK_MS', '30000');
$count = 0;
$worker = null;

$emit = static function (array $ref, string $result, ?string $detail = null) use (&$count, &$worker, $wanted, $scenario): void {
    if ($count >= $wanted) {
        return;
    }
    echo json_encode(['lang' => AC08_LANG, 'workId' => $ref['workId'], 'operationId' => $ref['operationId'],
        'lockVersion' => $ref['lockVersion'], 'attemptId' => $ref['attemptId'], 'scenario' => $scenario,
        'result' => $result, 'detail' => $detail], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), "\n";
    if (++$count >= $wanted) {
        $worker?->stop();
    }
};

if ($scenario === 'stale-lease') {
    $client = new SangamClient($baseUrl, $auth, retryPolicy: RetryPolicy::none());
    while ($count < $wanted && microtime(true) < $deadline) {
        foreach ($client->work()->fetchAndLock($workerId, [['name' => $topic, 'lockDurationMs' => $lockMs]], 1, 5000) as $w) {
            $ref = ['workId' => $w->id, 'operationId' => $w->operationId, 'lockVersion' => $w->lockVersion,
                'attemptId' => $w->attemptId];
            usleep(($lockMs + 2000) * 1000);
            try {
                $client->work()->complete((string) $w->id, $workerId, (int) $w->lockVersion, $w->attemptId,
                    ac08Outputs($w->variables ?? []), null);
                $emit($ref, 'error', 'complete after lease expiry was accepted');
            } catch (StaleLeaseException $e) {
                $emit($ref, 'stale-lease', (string) ($e->problem->extensions['reason'] ?? ''));
            }
        }
    }
    exit($count >= $wanted ? 0 : 1);
}

$failedOnce = [];
$details = [];
/** @var array<string, TaskContext> $pending */
$pending = [];

$sink = new CallbackLogger(static function (Level $level, string $event, string $message, array $fields = [],
                                             ?\Throwable $error = null) use (&$pending, &$details, $emit, $scenario): void {
    $wid = $fields['workId'] ?? null;
    $ctx = is_string($wid) ? ($pending[$wid] ?? null) : null;
    if ($ctx === null) {
        return;
    }
    $ref = ['workId' => $ctx->workId(), 'operationId' => $ctx->operationId(), 'lockVersion' => $ctx->lockVersion(),
        'attemptId' => $ctx->attemptId()];
    if ($event === 'work.complete') {
        if (isset($details[$ctx->workId()])) {
            $emit($ref, 'error', $details[$ctx->workId()]);
        } else {
            $emit($ref, $scenario === 'fail-retry' ? 'failed-then-completed' : 'completed');
        }
    } elseif ($event === 'work.bpmn-error') {
        $emit($ref, 'bpmn-error');
    } elseif ($event === 'work.outcome-unknown') {
        $emit($ref, 'outcome-unknown');
    } elseif (in_array($event, ['work.report.failed', 'work.report.gave-up', 'work.abandoned'], true)) {
        $emit($ref, 'error', $event . ': ' . ($error?->getMessage() ?? $message));
    }
});

// the sender stops the worker once the deadline passes (the inline loop returns to fetch-and-lock regularly)
$curl = new CurlSender();
$sender = new class ($curl, $deadline, static function () use (&$worker): ?SangamWorker {
    return $worker;
}) implements HttpSender {
    public function __construct(private readonly HttpSender $inner, private readonly float $deadline,
                                private readonly \Closure $worker)
    {
    }

    public function send(string $method, string $url, array $headers, ?string $payload, int $timeoutMs,
                         int $connectTimeoutMs, ?\Closure $abort): array
    {
        if (microtime(true) > $this->deadline) {
            ($this->worker)()?->stop();
        }
        return $this->inner->send($method, $url, $headers, $payload, $timeoutMs, $connectTimeoutMs, $abort);
    }
};

$client = new SangamClient($baseUrl, $auth, $sink, sender: $sender);
$worker = new SangamWorker($client, $workerId, 1, 1, 5000, mode: SangamWorker::MODE_INLINE);
$worker->subscribe(new TopicSubscription($topic, static function (TaskContext $ctx) use (&$pending, &$details, &$failedOnce, $scenario): array {
    $pending[$ctx->workId()] = $ctx;
    $raw = $ctx->work()->variables ?? [];
    $problem = ac08CheckInputs($raw);
    if ($problem !== null) {
        $details[$ctx->workId()] = $problem;
    }
    if ($scenario === 'bpmn-error') {
        throw new BpmnError('AC08_ERROR', 'AC-08 business error');
    }
    if ($scenario === 'outcome-unknown') {
        throw new OutcomeUnknownException('AC-08 outcome unknown');
    }
    if ($scenario === 'fail-retry' && !isset($failedOnce[$ctx->workId()])) {
        $failedOnce[$ctx->workId()] = true;
        throw new TaskFailedException('AC-08 transient failure', null, 1, 0);
    }
    return ac08Outputs($raw);
}, $lockMs, null, 1));
$worker->run();
exit($count >= $wanted ? 0 : 1);
