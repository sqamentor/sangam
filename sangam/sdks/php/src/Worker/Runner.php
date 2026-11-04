<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

use Sangam\Sdk\Error\GoneException;
use Sangam\Sdk\Error\SangamApiException;
use Sangam\Sdk\Error\SangamException;
use Sangam\Sdk\Error\StaleLeaseException;
use Sangam\Sdk\Error\TransportException;
use Sangam\Sdk\Log\Log;
use Sangam\Sdk\SangamClient;

/**
 * @internal Runs a handler and reports its outcome with fencing (workerId, lockVersion, attemptId) and an
 * Idempotency-Key "<operationId>:<action>:<lockVersion>" (R41). Shared by fork and inline mode.
 */
final class Runner
{
    public function __construct(
        private readonly SangamClient $client,
        private readonly string $workerId,
        private readonly Backoff $reportBackoff,
        private readonly Log $log,
    ) {
    }

    /** Maps the handler result / exception to an outcome (same table as every Sangam SDK). */
    public function invoke(TaskContext $ctx, TopicSubscription $sub): Outcome
    {
        try {
            $vars = ($sub->handler)($ctx);
            if ($vars !== null && !is_array($vars)) {
                throw new \UnexpectedValueException('handler must return an array of variables or null');
            }
            return new Outcome('complete', $vars);
        } catch (BpmnError $e) {
            return new Outcome('bpmn-error', $e->variables, $e);
        } catch (OutcomeUnknownException $e) {
            return new Outcome('outcome-unknown', $e->variables, $e);
        } catch (TaskFailedException $e) {
            return new Outcome('fail', null, $e);
        } catch (TaskCancelledException $e) {
            return new Outcome($ctx->isCancelRequested() ? 'cancel-ack' : 'unlock', null, $e);
        } catch (\Throwable $e) {
            // sangam-api.yaml 0.1.0 carries no per-item effect flag: irreversibility comes from the subscription
            return new Outcome($sub->irreversible ? 'outcome-unknown' : 'fail', null, $e);
        }
    }

    /** Sends the outcome; transient failures are retried with back-off until $deadline (Unix seconds). */
    public function report(TaskContext $ctx, TopicSubscription $sub, Outcome $o, float $deadline): void
    {
        $action = $o->kind;
        $work = $this->client->work();
        $id = $ctx->workId();
        $call = match ($action) {
            'complete' => fn() => $work->complete($id, $this->workerId, $ctx->lockVersion(), $ctx->attemptId(),
                $o->variables, self::key($ctx, $action)),
            'bpmn-error' => fn() => $work->bpmnError($id, $this->workerId, $ctx->lockVersion(), $ctx->attemptId(),
                $o->error instanceof BpmnError ? $o->error->errorCode : 'ERROR', $o->error?->getMessage(),
                $o->variables, self::key($ctx, $action)),
            'outcome-unknown' => fn() => $work->outcomeUnknown($id, $this->workerId, $ctx->lockVersion(),
                $ctx->attemptId(), self::unknownDetail($o->error), $o->variables, self::key($ctx, $action)),
            'fail' => $this->failCall($ctx, $sub, $o->error),
            'cancel-ack' => fn() => $work->cancelAck($id, $this->workerId, $ctx->lockVersion(), $ctx->attemptId(),
                self::key($ctx, $action)),
            default => fn() => $work->unlock($id, $this->workerId, $ctx->lockVersion(), $ctx->attemptId(),
                self::key($ctx, 'unlock')),
        };
        $attempt = 0;
        while (true) {
            $attempt++;
            try {
                $call();
                $fields = ['attempts' => $attempt];
                if ($o->error !== null) {
                    $fields['error'] = $o->error->getMessage();
                }
                $ctx->log()->info('work.' . $action, 'reported ' . $action, $fields);
                return;
            } catch (StaleLeaseException|GoneException $e) {
                $ctx->markLeaseLost();
                $ctx->log()->warning('work.abandoned', $action . ' rejected: lease no longer ours', [], $e);
                return;
            } catch (SangamApiException $e) {
                if (!$e->isRetryable() || microtime(true) >= $deadline) {
                    $ctx->log()->error('work.report.failed', $action . ' rejected by server', ['status' => $e->status], $e);
                    return;
                }
            } catch (TransportException $e) {
                if (microtime(true) >= $deadline) {
                    $ctx->log()->error('work.report.gave-up', $action . ' not delivered before lease expiry; '
                        . 'the lease will expire and the same operationId is reused', ['attempts' => $attempt], $e);
                    return;
                }
            } catch (SangamException $e) {
                $ctx->log()->error('work.report.failed', $action . ' failed', [], $e);
                return;
            }
            usleep($this->reportBackoff->delayMs($attempt) * 1000);
        }
    }

    private function failCall(TaskContext $ctx, TopicSubscription $sub, ?\Throwable $error): \Closure
    {
        $base = $ctx->work()->retries ?? $sub->retries;
        $remaining = max(0, $base - 1);
        $timeout = $sub->retryBackoff->delayMs(max(1, $sub->retries - $remaining));
        $details = self::trace($error);
        if ($error instanceof TaskFailedException) {
            if ($error->retries !== null) {
                $remaining = max(0, $error->retries);
            }
            if ($error->retryTimeoutMs !== null) {
                $timeout = $error->retryTimeoutMs;
            }
            if ($error->details !== null) {
                $details = $error->details;
            }
        }
        $msg = $error === null ? 'failed' : self::describe($error);
        if (strlen($msg) > 1000) {
            $msg = substr($msg, 0, 1000);
        }
        $work = $this->client->work();
        return fn() => $work->fail($ctx->workId(), $this->workerId, $ctx->lockVersion(), $ctx->attemptId(), $msg,
            $details, $remaining, $timeout, self::key($ctx, 'fail'));
    }

    /** OutcomeUnknownRequest.detail = the handler's reason plus its details (or the stack trace). */
    private static function unknownDetail(?\Throwable $e): string
    {
        if ($e instanceof OutcomeUnknownException) {
            $d = $e->getMessage();
            if ($e->details !== null && $e->details !== '') {
                $d .= "\n" . $e->details;
            }
            return $d;
        }
        $d = 'unexpected error on irreversible topic: ' . self::describe($e);
        $t = self::trace($e);
        return $t === null ? $d : $d . "\n" . $t;
    }

    public static function key(TaskContext $ctx, string $action): string
    {
        return $ctx->operationId() . ':' . $action . ':' . $ctx->lockVersion();
    }

    private static function describe(?\Throwable $e): string
    {
        if ($e === null) {
            return 'failed';
        }
        return $e->getMessage() !== '' ? $e->getMessage() : get_class($e);
    }

    private static function trace(?\Throwable $e): ?string
    {
        if ($e === null) {
            return null;
        }
        $s = get_class($e) . ': ' . $e->getMessage() . "\n" . $e->getTraceAsString();
        return strlen($s) > 4000 ? substr($s, 0, 4000) : $s;
    }
}
