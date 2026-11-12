<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * How a durable step recovers when an earlier attempt left it INTENT (the crash window of R37), following the
 * capability class of the remote call (R36):
 *
 * - (a) idempotent, with or without lookup: idempotentCall() / ->idempotent() runs the call again with the same
 *   Step::idempotencyKey();
 * - (b) outcome lookup only: withLookup(fn) / ->lookup(fn) asks the remote system before any controlled retry;
 * - (c) neither (the default): the step throws OutcomeUnknownException, the worker reports outcome-unknown and
 *   reconciliation decides; nothing is repeated automatically.
 *
 * A lookup, when given, is always asked first. It receives the Step and returns:
 * - a value (not null): the call took effect; it is recorded SUCCEEDED and returned without calling again;
 * - null: the call certainly did not take effect; the step runs again;
 * - or throws: the outcome is still unknown; the step stays INTENT and the exception propagates.
 */
final class StepOptions
{
    private static ?self $defaults = null;

    private function __construct(
        private readonly bool $idempotent,
        private readonly ?\Closure $lookup,
    ) {
    }

    /** Capability class (c): no lookup, not idempotent. */
    public static function defaults(): self
    {
        return self::$defaults ??= new self(false, null);
    }

    /** Capability class (a): the remote call is idempotent on Step::idempotencyKey(). */
    public static function idempotentCall(): self
    {
        return new self(true, null);
    }

    /**
     * Capability class (b): look the outcome up before running the call again.
     *
     * @param callable(Step): mixed $lookup
     */
    public static function withLookup(callable $lookup): self
    {
        return new self(false, \Closure::fromCallable($lookup));
    }

    /** These options, also idempotent (class (a) with a lookup). */
    public function idempotent(): self
    {
        return new self(true, $this->lookup);
    }

    /**
     * These options with an outcome lookup.
     *
     * @param callable(Step): mixed $lookup
     */
    public function lookup(callable $lookup): self
    {
        return new self($this->idempotent, \Closure::fromCallable($lookup));
    }

    public function isIdempotent(): bool
    {
        return $this->idempotent;
    }

    /** @return (\Closure(Step): mixed)|null */
    public function lookupOrNull(): ?\Closure
    {
        return $this->lookup;
    }
}
