<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

use Sangam\Sdk\Error\GoneException;
use Sangam\Sdk\Error\SangamApiException;
use Sangam\Sdk\Error\SangamException;
use Sangam\Sdk\Error\StaleLeaseException;
use Sangam\Sdk\Error\TransportException;
use Sangam\Sdk\Http\HttpTransport;
use Sangam\Sdk\Log\Log;
use Sangam\Sdk\Log\SangamLogger;
use Sangam\Sdk\Model\ExternalWork;
use Sangam\Sdk\Model\Time;
use Sangam\Sdk\SangamClient;

/**
 * External-worker runtime for the Sangam worker API (VI.3, A-1, A-2, R9, R20, R36).
 *
 * PHP runs one thread, so the runtime has two modes:
 *
 * - "fork" (ext-pcntl + ext-posix, Linux/macOS): up to $maxConcurrency child processes. The parent long-polls
 *   fetch-and-lock for as many items as there are free slots, and heartbeats (extend-lock) every lease/3 for each
 *   child's item. cancelRequested -> SIGUSR1 to the child; 409 stale-lease / 410 gone / expiry -> SIGTERM to the
 *   child (lease lost, result discarded). After each heartbeat the parent writes the LockState (lockVersion,
 *   lockExpiresAt, cancelRequested) to a per-child socket pair; the child applies it before reporting. The child
 *   runs the handler and reports its outcome itself, fenced with (workerId, lockVersion, attemptId).
 * - "inline" (Windows or no pcntl): one item at a time. The handler calls $ctx->heartbeat() during long work; the
 *   runtime heartbeats again before reporting when lease/3 has passed.
 *
 * Both modes: A-1 (start only after the LOCKED confirmation), Idempotency-Key "<operationId>:<action>:<lockVersion>"
 * per call (heartbeats add ":<seq>"), result reports retried until lease expiry, fetch errors backed off with
 * jitter, overdelivered or unsubscribed items unlocked, graceful stop on SIGTERM/SIGINT with a shutdown timeout.
 */
final class SangamWorker
{
    public const MODE_FORK = 'fork';
    public const MODE_INLINE = 'inline';

    /** @var array<string, TopicSubscription> */
    private array $subscriptions = [];
    private bool $stopping = false;
    private bool $running = false;
    /** @var array<int, array{work: ExternalWork, sub: TopicSubscription, lockVersion: int, expiresAt: float, nextHb: float, failures: int, cancelSent: bool, lost: bool, pipe: resource|null}> */
    private array $children = [];
    private int $hbSeq = 0;
    private ?TaskContext $current = null;
    private readonly string $workerId;
    private readonly string $mode;
    private readonly Log $log;
    private readonly Backoff $fetchBackoff;
    private readonly Backoff $reportBackoff;
    private readonly Runner $runner;

    public function __construct(
        private readonly SangamClient $client,
        ?string $workerId = null,
        private readonly int $maxConcurrency = 4,
        private readonly int $maxTasksPerFetch = 4,
        private readonly int $asyncResponseTimeoutMs = 30000,
        private readonly float $heartbeatFraction = 1 / 3,
        ?Backoff $fetchBackoff = null,
        ?Backoff $reportBackoff = null,
        private readonly int $shutdownTimeoutMs = 30000,
        ?string $mode = null,
        ?SangamLogger $logger = null,
        private readonly ?bool $usePriority = null,
    ) {
        if ($maxConcurrency < 1 || $maxTasksPerFetch < 1) {
            throw new \InvalidArgumentException('maxConcurrency and maxTasksPerFetch must be >= 1');
        }
        if ($heartbeatFraction <= 0 || $heartbeatFraction >= 1) {
            throw new \InvalidArgumentException('heartbeatFraction must be in (0,1)');
        }
        $this->workerId = $workerId ?? self::defaultWorkerId();
        $forkable = self::forkSupported();
        if ($mode === self::MODE_FORK && !$forkable) {
            throw new \RuntimeException('fork mode needs ext-pcntl and ext-posix');
        }
        $this->mode = $mode ?? ($forkable ? self::MODE_FORK : self::MODE_INLINE);
        $this->log = $logger !== null ? new Log($logger) : $client->log();
        $this->fetchBackoff = $fetchBackoff ?? Backoff::fetchDefaults();
        $this->reportBackoff = $reportBackoff ?? Backoff::reportDefaults();
        $this->runner = new Runner($client, $this->workerId, $this->reportBackoff, $this->log);
    }

    public static function forkSupported(): bool
    {
        return function_exists('pcntl_fork') && function_exists('posix_kill') && PHP_OS_FAMILY !== 'Windows';
    }

    public function workerId(): string
    {
        return $this->workerId;
    }

    public function mode(): string
    {
        return $this->mode;
    }

    /** Registers (or replaces) a handler; takes effect on the next fetch (Flowable-client parity: runtime subscribe). */
    public function subscribe(TopicSubscription|string $topic, TaskHandler|callable|null $handler = null): self
    {
        if (is_string($topic)) {
            if ($handler === null) {
                throw new \InvalidArgumentException('handler is required');
            }
            $topic = new TopicSubscription($topic, $handler);
        }
        $this->subscriptions[$topic->topic] = $topic;
        $this->log->info('worker.subscribed', 'subscribed', ['workerId' => $this->workerId, 'topic' => $topic->topic]);
        return $this;
    }

    /** Stops fetching a topic; items already running finish normally. */
    public function unsubscribe(string $topic): self
    {
        unset($this->subscriptions[$topic]);
        $this->log->info('worker.unsubscribed', 'unsubscribed', ['workerId' => $this->workerId, 'topic' => $topic]);
        return $this;
    }

    /** Asks the run loop to stop (safe from a signal handler). */
    public function stop(): void
    {
        if (!$this->stopping) {
            $this->stopping = true;
            $this->current?->markShuttingDown();
        }
    }

    /** Runs until stop() / SIGTERM / SIGINT, then shuts down gracefully. */
    public function run(): void
    {
        if ($this->running) {
            return;
        }
        $this->running = true;
        $this->stopping = false;
        $this->installSignalHandlers();
        $this->log->info('worker.started', 'worker started', ['workerId' => $this->workerId, 'mode' => $this->mode,
            'maxConcurrency' => $this->mode === self::MODE_FORK ? $this->maxConcurrency : 1,
            'topics' => array_keys($this->subscriptions)]);
        try {
            if ($this->mode === self::MODE_FORK) {
                $this->runFork();
            } else {
                $this->runInline();
            }
        } finally {
            $this->running = false;
            $this->log->info('worker.stopped', 'worker stopped', ['workerId' => $this->workerId]);
        }
    }

    // ================================================================ fetch

    /** @return list<ExternalWork> */
    private function fetch(int $maxTasks, int $waitMs, int &$consecutiveErrors): array
    {
        $topics = [];
        foreach ($this->subscriptions as $s) {
            $topics[] = $s->toTopicRequest();
        }
        try {
            $items = $this->client->work()->fetchAndLock($this->workerId, $topics, $maxTasks, $waitMs,
                fn(): bool => $this->stopping, $this->usePriority);
            $consecutiveErrors = 0;
            return $items;
        } catch (SangamException $e) {
            if ($this->stopping || ($e instanceof TransportException && $e->isAborted())) {
                return [];
            }
            $consecutiveErrors++;
            $d = $this->fetchBackoff->delayMs($consecutiveErrors);
            $this->log->warning('work.fetch.failed', 'fetch-and-lock failed; backing off',
                ['workerId' => $this->workerId, 'attempt' => $consecutiveErrors, 'delayMs' => $d], $e);
            $this->idle($d);
            return [];
        }
    }

    /** A-1 + subscription check; unlocks items that must not run. */
    private function accept(ExternalWork $w): ?TopicSubscription
    {
        $sub = $w->topic !== null ? ($this->subscriptions[$w->topic] ?? null) : null;
        if ($sub === null) {
            $this->log->warning('work.unsubscribed', 'no handler for topic; unlocking',
                ['workerId' => $this->workerId, 'workId' => $w->id, 'topic' => $w->topic]);
            $this->unlockQuietly($w);
            return null;
        }
        if ($w->id === null || $w->attemptId === null) {
            // A-1: a handler starts only after the LOCKED confirmation with lockVersion + attemptId
            $this->log->error('work.protocol.violation', 'item without LOCKED confirmation (attemptId); not started',
                ['workerId' => $this->workerId, 'workId' => $w->id]);
            return null;
        }
        return $sub;
    }

    private function unlockQuietly(ExternalWork $w, int $lockVersion = -1): void
    {
        if ($w->id === null) {
            return;
        }
        $lv = $lockVersion >= 0 ? $lockVersion : $w->lockVersion;
        try {
            $this->client->work()->unlock($w->id, $this->workerId, $lv, $w->attemptId,
                $w->operationIdOrId() . ':unlock:' . $lv);
        } catch (SangamException $e) {
            $this->log->warning('work.unlock.failed', 'unlock failed; lease will expire',
                ['workerId' => $this->workerId, 'workId' => $w->id], $e);
        }
    }

    // ================================================================ fork mode

    private function runFork(): void
    {
        $errors = 0;
        while (!$this->stopping) {
            $this->reapChildren();
            $this->heartbeatChildren();
            $free = $this->maxConcurrency - count($this->children);
            if ($free <= 0 || $this->subscriptions === []) {
                $this->idle($this->subscriptions === [] ? 1000 : 200);
                continue;
            }
            $wait = $this->asyncResponseTimeoutMs;
            if ($this->children !== []) {
                // the parent must be back in time for the next heartbeat
                $wait = (int) max(1000, min($wait, $this->msUntilNextHeartbeat()));
            }
            $want = min($free, $this->maxTasksPerFetch);
            $items = $this->fetch($want, $wait, $errors);
            $used = 0;
            foreach ($items as $w) {
                if ($used >= $want) {
                    $this->log->warning('work.overdelivered', 'server returned more items than requested; unlocking',
                        ['workerId' => $this->workerId, 'workId' => $w->id]);
                    $this->unlockQuietly($w);
                    continue;
                }
                $sub = $this->accept($w);
                if ($sub === null) {
                    continue;
                }
                if ($this->stopping) {
                    $this->unlockQuietly($w);
                    continue;
                }
                $used++;
                $this->spawn($w, $sub);
            }
        }
        $this->drainChildren();
    }

    private function spawn(ExternalWork $w, TopicSubscription $sub): void
    {
        // parent -> child channel for LockState updates (lockVersion, lockExpiresAt, cancelRequested)
        $pair = function_exists('stream_socket_pair')
            ? @stream_socket_pair(STREAM_PF_UNIX, STREAM_SOCK_STREAM, STREAM_IPPROTO_IP)
            : false;
        $pid = pcntl_fork();
        if ($pid === -1) {
            $this->log->error('work.fork.failed', 'pcntl_fork failed; unlocking', ['workerId' => $this->workerId,
                'workId' => $w->id]);
            if (is_array($pair)) {
                fclose($pair[0]);
                fclose($pair[1]);
            }
            $this->unlockQuietly($w);
            return;
        }
        if ($pid === 0) {
            $childEnd = null;
            if (is_array($pair)) {
                fclose($pair[0]);
                $childEnd = $pair[1];
            }
            $code = 0;
            try {
                $this->runChild($w, $sub, $childEnd);
            } catch (\Throwable $e) {
                $code = 1;
            }
            exit($code);
        }
        $parentEnd = null;
        if (is_array($pair)) {
            fclose($pair[1]);
            $parentEnd = $pair[0];
            stream_set_blocking($parentEnd, false);
        }
        $now = microtime(true);
        $interval = $this->heartbeatIntervalMs($sub) / 1000;
        $this->children[$pid] = [
            'work' => $w,
            'sub' => $sub,
            'lockVersion' => $w->lockVersion,
            'expiresAt' => $w->lockExpiresAtTs() ?? ($now + $sub->lockDurationMs / 1000),
            'nextHb' => $now + $interval,
            'failures' => 0,
            'cancelSent' => false,
            'lost' => false,
            'pipe' => $parentEnd,
        ];
        $this->log->debug('work.forked', 'child started', ['workerId' => $this->workerId, 'workId' => $w->id,
            'pid' => $pid]);
    }

    /**
     * Runs in the child process: handler + fenced report. Never returns to the parent loop.
     *
     * @param resource|null $pipe child end of the LockState channel (null if no socket pair could be made)
     */
    private function runChild(ExternalWork $w, TopicSubscription $sub, $pipe): void
    {
        $this->children = []; // drops (and closes) the inherited parent ends of sibling channels
        $sync = null;
        if (is_resource($pipe)) {
            stream_set_blocking($pipe, false);
            $buf = '';
            // applies the LockState lines the parent wrote after each successful heartbeat
            $sync = static function (TaskContext $ctx, bool $force) use ($pipe, &$buf): void {
                while (true) {
                    $chunk = fread($pipe, 8192);
                    if ($chunk === false || $chunk === '') {
                        break;
                    }
                    $buf .= $chunk;
                }
                while (($nl = strpos($buf, "\n")) !== false) {
                    $line = substr($buf, 0, $nl);
                    $buf = (string) substr($buf, $nl + 1);
                    $d = json_decode($line, true);
                    if (is_array($d)) {
                        $ctx->markExtended(
                            Time::parse(isset($d['lockExpiresAt']) && is_string($d['lockExpiresAt']) ? $d['lockExpiresAt'] : null),
                            isset($d['lockVersion']) && is_int($d['lockVersion']) ? $d['lockVersion'] : null,
                            ($d['cancelRequested'] ?? false) === true,
                        );
                    }
                }
            };
        }
        $ctx = new TaskContext($w, $this->workerId, $this->client, $this->log, $sync);
        $this->current = $ctx;
        pcntl_async_signals(true);
        pcntl_signal(SIGTERM, SIG_DFL);  // parent: lease lost -> terminate at once, nothing is reported
        pcntl_signal(SIGINT, SIG_IGN);   // Ctrl-C hits the process group; the parent coordinates shutdown
        pcntl_signal(SIGUSR1, static function () use ($ctx): void {
            $ctx->markCancelRequested();
        });
        pcntl_signal(SIGUSR2, static function () use ($ctx): void {
            $ctx->markShuttingDown();
        });
        $ctx->log()->info('work.started', 'handler started', ['mode' => self::MODE_FORK]);
        $outcome = $this->runner->invoke($ctx, $sub);
        $ctx->heartbeat(true); // pick up the latest lockVersion / expiry from the parent before reporting
        if ($ctx->isLeaseLost()) {
            $ctx->log()->warning('work.abandoned', 'lease lost; result discarded', ['outcome' => $outcome->kind]);
            return;
        }
        // while this child lives, the parent keeps heartbeating, so the lease stays valid for one more lease
        $deadline = max($ctx->lockExpiresAt() ?? 0.0, microtime(true) + $sub->lockDurationMs / 1000);
        $this->runner->report($ctx, $sub, $outcome, $deadline);
    }

    private function reapChildren(): void
    {
        while (($pid = pcntl_waitpid(-1, $status, WNOHANG)) > 0) {
            $c = $this->children[$pid] ?? null;
            unset($this->children[$pid]);
            if ($c !== null) {
                self::closePipe($c['pipe']);
                $exited = pcntl_wifexited($status);
                $this->log->debug('work.child.exit', 'child finished', ['workerId' => $this->workerId,
                    'workId' => $c['work']->id, 'pid' => $pid,
                    'exitCode' => $exited ? pcntl_wexitstatus($status) : null,
                    'signal' => pcntl_wifsignaled($status) ? pcntl_wtermsig($status) : null]);
            }
        }
    }

    private function heartbeatChildren(): void
    {
        $now = microtime(true);
        foreach ($this->children as $pid => $c) {
            if ($c['lost'] || $now < $c['nextHb']) {
                continue;
            }
            $w = $c['work'];
            $sub = $c['sub'];
            $interval = $this->heartbeatIntervalMs($sub) / 1000;
            try {
                $r = $this->client->work()->extendLock((string) $w->id, $this->workerId, $c['lockVersion'],
                    $w->attemptId, $sub->lockDurationMs,
                    $w->operationIdOrId() . ':extend-lock:' . $c['lockVersion'] . ':' . (++$this->hbSeq));
                $c['failures'] = 0;
                $c['nextHb'] = microtime(true) + $interval;
                $exp = Time::parse($r->lockExpiresAt);
                if ($exp !== null) {
                    $c['expiresAt'] = $exp;
                }
                if ($r->lockVersion !== null) {
                    $c['lockVersion'] = $r->lockVersion;
                }
                // hand the LockState to the child so its report carries the current lockVersion
                self::sendLockState($c['pipe'], $c['lockVersion'], $r->lockExpiresAt, $r->cancelRequested);
                if ($r->cancelRequested && !$c['cancelSent']) {
                    $c['cancelSent'] = true;
                    posix_kill($pid, SIGUSR1);
                    $this->log->warning('work.cancel.requested', 'server requested cancel',
                        ['workerId' => $this->workerId, 'workId' => $w->id]);
                }
                $this->log->debug('work.heartbeat', 'lease extended', ['workerId' => $this->workerId,
                    'workId' => $w->id, 'lockExpiresAt' => $r->lockExpiresAt]);
            } catch (StaleLeaseException|GoneException $e) {
                $c['lost'] = true;
                $this->loseChild($pid, $w, $e);
            } catch (SangamApiException $e) {
                if ($e->status === 404) {
                    // the item is no longer there (completed by the child, or removed): stop heartbeating
                    $c['lost'] = true;
                } else {
                    $c = $this->heartbeatFailed($pid, $c, $e);
                }
            } catch (SangamException $e) {
                $c = $this->heartbeatFailed($pid, $c, $e);
            }
            $this->children[$pid] = $c;
        }
    }

    /**
     * @param array{work: ExternalWork, sub: TopicSubscription, lockVersion: int, expiresAt: float, nextHb: float, failures: int, cancelSent: bool, lost: bool, pipe: resource|null} $c
     * @return array{work: ExternalWork, sub: TopicSubscription, lockVersion: int, expiresAt: float, nextHb: float, failures: int, cancelSent: bool, lost: bool, pipe: resource|null}
     */
    private function heartbeatFailed(int $pid, array $c, \Throwable $e): array
    {
        $c['failures']++;
        if (microtime(true) > $c['expiresAt']) {
            $c['lost'] = true;
            $this->loseChild($pid, $c['work'], $e);
            return $c;
        }
        $interval = $this->heartbeatIntervalMs($c['sub']);
        $c['nextHb'] = microtime(true) + min($interval, $this->reportBackoff->delayMs($c['failures'])) / 1000;
        $this->log->warning('work.heartbeat.failed', 'heartbeat failed; will retry before lease expiry',
            ['workerId' => $this->workerId, 'workId' => $c['work']->id, 'failures' => $c['failures']], $e);
        return $c;
    }

    private function loseChild(int $pid, ExternalWork $w, \Throwable $cause): void
    {
        $this->log->warning('work.lease.lost', 'lease lost; terminating handler process, result discarded',
            ['workerId' => $this->workerId, 'workId' => $w->id, 'pid' => $pid], $cause);
        posix_kill($pid, SIGTERM);
    }

    private function msUntilNextHeartbeat(): float
    {
        $next = INF;
        foreach ($this->children as $c) {
            if (!$c['lost']) {
                $next = min($next, $c['nextHb']);
            }
        }
        return $next === INF ? (float) $this->asyncResponseTimeoutMs : max(0.0, ($next - microtime(true)) * 1000);
    }

    /** Graceful shutdown: wait (heartbeating), then SIGUSR2 (cooperative), then SIGKILL + unlock. */
    private function drainChildren(): void
    {
        $this->log->info('worker.stopping', 'stopping: no new fetches', ['workerId' => $this->workerId,
            'inFlight' => count($this->children)]);
        $this->waitChildren(microtime(true) + $this->shutdownTimeoutMs / 1000);
        if ($this->children === []) {
            return;
        }
        $this->log->warning('worker.shutdown.timeout', 'asking handlers still running to stop',
            ['workerId' => $this->workerId, 'inFlight' => count($this->children)]);
        foreach (array_keys($this->children) as $pid) {
            posix_kill($pid, SIGUSR2);
        }
        $this->waitChildren(microtime(true) + 10);
        foreach ($this->children as $pid => $c) {
            posix_kill($pid, SIGKILL);
            pcntl_waitpid($pid, $status);
            self::closePipe($c['pipe']);
            if (!$c['lost']) {
                $this->unlockQuietly($c['work'], $c['lockVersion']);
            }
            unset($this->children[$pid]);
        }
    }

    private function waitChildren(float $until): void
    {
        while ($this->children !== [] && microtime(true) < $until) {
            $this->reapChildren();
            $this->heartbeatChildren();
            usleep(100_000);
        }
    }

    // ================================================================ inline mode

    private function runInline(): void
    {
        $errors = 0;
        while (!$this->stopping) {
            if ($this->subscriptions === []) {
                $this->idle(1000);
                continue;
            }
            $items = $this->fetch(1, $this->asyncResponseTimeoutMs, $errors);
            $ran = false;
            foreach ($items as $w) {
                if ($ran || $this->stopping) {
                    if ($ran) {
                        $this->log->warning('work.overdelivered', 'server returned more items than requested; unlocking',
                            ['workerId' => $this->workerId, 'workId' => $w->id]);
                    }
                    $this->unlockQuietly($w);
                    continue;
                }
                $sub = $this->accept($w);
                if ($sub === null) {
                    continue;
                }
                $ran = true;
                $this->runInlineItem($w, $sub);
            }
        }
    }

    private function runInlineItem(ExternalWork $w, TopicSubscription $sub): void
    {
        $intervalMs = $this->heartbeatIntervalMs($sub);
        $heartbeat = function (TaskContext $ctx, bool $force) use ($sub, $intervalMs): void {
            if ($ctx->isLeaseLost() ||(!$force && (microtime(true) - $ctx->lastExtendedAt()) * 1000 < $intervalMs)) {
                return;
            }
            try {
                $r = $this->client->work()->extendLock($ctx->workId(), $this->workerId, $ctx->lockVersion(),
                    $ctx->attemptId(), $sub->lockDurationMs,
                    $ctx->operationId() . ':extend-lock:' . $ctx->lockVersion() . ':' . (++$this->hbSeq));
                $ctx->markExtended(Time::parse($r->lockExpiresAt), $r->lockVersion, $r->cancelRequested);
                if ($r->cancelRequested) {
                    $ctx->log()->warning('work.cancel.requested', 'server requested cancel');
                }
            } catch (StaleLeaseException|GoneException $e) {
                $ctx->markLeaseLost();
                $ctx->log()->warning('work.lease.lost', 'lease lost; result will be discarded', [], $e);
            } catch (SangamException $e) {
                $exp = $ctx->lockExpiresAt();
                if ($exp !== null && microtime(true) > $exp) {
                    $ctx->markLeaseLost();
                    $ctx->log()->warning('work.lease.lost', 'lease expired while unreachable', [], $e);
                } else {
                    $ctx->log()->warning('work.heartbeat.failed', 'heartbeat failed; will retry', [], $e);
                }
            }
        };
        $ctx = new TaskContext($w, $this->workerId, $this->client, $this->log, \Closure::fromCallable($heartbeat));
        $this->current = $ctx;
        try {
            if ($this->stopping) {
                $ctx->markShuttingDown();
            }
            $ctx->log()->info('work.started', 'handler started', ['mode' => self::MODE_INLINE]);
            $outcome = $this->runner->invoke($ctx, $sub);
            // re-check the lease before reporting when lease/3 has passed (VI.3.2 rule 3)
            $ctx->heartbeat();
            if ($ctx->isLeaseLost()) {
                $ctx->log()->warning('work.abandoned', 'lease lost; result discarded', ['outcome' => $outcome->kind]);
                return;
            }
            $deadline = $ctx->lockExpiresAt() ?? (microtime(true) + $sub->lockDurationMs / 1000);
            $this->runner->report($ctx, $sub, $outcome, $deadline);
        } finally {
            $this->current = null;
        }
    }

    // ================================================================ helpers

    /** @param resource|null $pipe */
    private static function sendLockState($pipe, int $lockVersion, ?string $lockExpiresAt, bool $cancelRequested): void
    {
        if (!is_resource($pipe)) {
            return;
        }
        $line = json_encode(['lockVersion' => $lockVersion, 'lockExpiresAt' => $lockExpiresAt,
            'cancelRequested' => $cancelRequested], JSON_UNESCAPED_SLASHES);
        if ($line !== false) {
            @fwrite($pipe, $line . "\n"); // a child that already exited is reaped later; errors are ignored
        }
    }

    /** @param resource|null $pipe */
    private static function closePipe($pipe): void
    {
        if (is_resource($pipe)) {
            @fclose($pipe);
        }
    }

    private function heartbeatIntervalMs(TopicSubscription $sub): int
    {
        return (int) max(1000, $sub->lockDurationMs * $this->heartbeatFraction);
    }

    /** Sleeps up to $ms in short slices; in fork mode it keeps reaping and heartbeating. */
    private function idle(int $ms): void
    {
        $until = microtime(true) + $ms / 1000;
        while (!$this->stopping && microtime(true) < $until) {
            if ($this->mode === self::MODE_FORK && $this->children !== []) {
                $this->reapChildren();
                $this->heartbeatChildren();
            }
            usleep((int) min(200_000, max(1, ($until - microtime(true)) * 1_000_000)));
        }
    }

    private function installSignalHandlers(): void
    {
        if (function_exists('pcntl_async_signals') && function_exists('pcntl_signal')) {
            pcntl_async_signals(true);
            $stop = function (): void {
                $this->stop();
            };
            pcntl_signal(SIGTERM, $stop);
            pcntl_signal(SIGINT, $stop);
            if (defined('SIGPIPE')) {
                pcntl_signal(SIGPIPE, SIG_IGN); // writing LockState to a child that just exited must not kill us
            }
        } elseif (function_exists('sapi_windows_set_ctrl_handler')) {
            sapi_windows_set_ctrl_handler(function (int $event): void {
                $this->stop();
            });
        }
    }

    private static function defaultWorkerId(): string
    {
        $host = gethostname();
        return ($host === false ? 'worker' : $host) . '-' . getmypid() . '-' . substr(HttpTransport::uuid4(), 0, 8);
    }
}
