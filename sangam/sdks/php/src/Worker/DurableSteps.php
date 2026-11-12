<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

use Sangam\Sdk\Error\GoneException;
use Sangam\Sdk\Error\SangamApiException;
use Sangam\Sdk\Error\StaleLeaseException;
use Sangam\Sdk\Generated\Api\WorkApi;
use Sangam\Sdk\Generated\Model\WorkStep;
use Sangam\Sdk\Generated\Model\WorkStepRequest;
use Sangam\Sdk\Model\TypedValue;

/**
 * The algorithm of TaskContext::step() (M-025, R37 / E02) on the step journal routes GET/PUT
 * /work/{id}/steps/{stepName}, fenced by the context's lease:
 *
 * 1. read the step: SUCCEEDED replays the recorded result without calling again;
 * 2. INTENT left by an earlier attempt (the crash window): ask the lookup if any (a value is recorded SUCCEEDED and
 *    returned; null means no effect), else run again only for an idempotent call, else throw OutcomeUnknownException;
 * 3. record INTENT, run the call, record SUCCEEDED with the result, or FAILED on StepNoEffectException; any other
 *    exception leaves INTENT (uncertain) and propagates.
 *
 * A 409 STALE_LEASE or 410 from the journal marks the lease lost and throws TaskCancelledException.
 * NOT exactly-once for remote APIs: a crash between the remote call and the SUCCEEDED record leaves INTENT, which
 * step 2 resolves without a blind re-issue.
 *
 * @internal use TaskContext::step()
 */
final class DurableSteps
{
    public const INTENT = 'INTENT';
    public const SUCCEEDED = 'SUCCEEDED';
    public const FAILED = 'FAILED';

    private function __construct()
    {
    }

    /**
     * @param callable(Step): mixed $fn
     * @throws OutcomeUnknownException INTENT from an earlier attempt and the call is neither looked up nor idempotent
     * @throws StepNoEffectException   rethrown from $fn after the step was recorded FAILED
     * @throws TaskCancelledException  the lease was lost (409 STALE_LEASE or 410 from the journal)
     */
    public static function run(TaskContext $ctx, string $name, callable $fn, ?StepOptions $options = null): mixed
    {
        self::requireName($name);
        $opts = $options ?? StepOptions::defaults();
        $api = $ctx->client()->api()->work;
        $recorded = self::read($ctx, $api, $name);
        $previous = $recorded?->state;
        $step = new Step($name, $ctx->operationId(), $previous);
        if ($recorded !== null && $previous === self::SUCCEEDED) {
            $ctx->log()->info('step.replayed', 'step already succeeded; replaying its recorded result', ['step' => $name]);
            return TypedValue::unwrap($recorded->result);
        }
        if ($previous === self::INTENT) {
            $lookup = $opts->lookupOrNull();
            if ($lookup !== null) {
                $found = $lookup($step);
                if ($found !== null) {
                    self::write($ctx, $api, $name, self::SUCCEEDED, $found, $step->providerRef(), null);
                    $ctx->log()->info('step.looked-up', 'earlier attempt took effect; recorded from lookup', ['step' => $name]);
                    return $found;
                }
            } elseif (!$opts->isIdempotent()) {
                throw new OutcomeUnknownException('step ' . $name . ' of operation ' . $ctx->operationId()
                    . ' was started by an earlier attempt and its outcome is unknown (R37)',
                    'step=' . $name, ['step' => $name, 'idempotencyKey' => $step->idempotencyKey()]);
            }
        }
        self::write($ctx, $api, $name, self::INTENT, null, null, null);
        try {
            $value = $fn($step);
        } catch (StepNoEffectException $e) {
            self::write($ctx, $api, $name, self::FAILED, null, $step->providerRef(), $e->getMessage());
            throw $e;
        }
        self::write($ctx, $api, $name, self::SUCCEEDED, $value, $step->providerRef(), null);
        return $value;
    }

    /** 1-128 printable ASCII characters (0x20-0x7E), as the journal requires. */
    public static function requireName(string $name): void
    {
        if ($name === '' || strlen($name) > 128) {
            throw new \InvalidArgumentException('step name must be 1-128 characters');
        }
        if (preg_match('/^[\x20-\x7e]+$/D', $name) !== 1) {
            throw new \InvalidArgumentException('step name must be printable ASCII');
        }
    }

    private static function read(TaskContext $ctx, WorkApi $api, string $name): ?WorkStep
    {
        try {
            return $api->getWorkStep($ctx->workId(), $name, $ctx->workerId(), $ctx->lockVersion(),
                (string) $ctx->attemptId());
        } catch (StaleLeaseException|GoneException $e) {
            throw self::leaseLost($ctx, $name, $e);
        } catch (SangamApiException $e) {
            if ($e->status === 404) {
                return null; // never recorded: run it
            }
            throw $e;
        }
    }

    private static function write(TaskContext $ctx, WorkApi $api, string $name, string $state, mixed $result,
                                  ?string $providerRef, ?string $error): void
    {
        try {
            $api->recordWorkStep($ctx->workId(), $name, new WorkStepRequest($ctx->workerId(), $ctx->lockVersion(),
                $ctx->attemptId(), $state, $result, $providerRef, $error));
        } catch (StaleLeaseException|GoneException $e) {
            throw self::leaseLost($ctx, $name, $e);
        }
    }

    private static function leaseLost(TaskContext $ctx, string $name, \Throwable $cause): TaskCancelledException
    {
        $ctx->markLeaseLost();
        return new TaskCancelledException('lease lost while recording step ' . $name, 0, $cause);
    }
}
