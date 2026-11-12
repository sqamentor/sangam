<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Model;

/**
 * W3C Trace Context of the request that created a work item (sangam-api.yaml TraceContext; ExternalWork.traceContext,
 * M-062, R13): the engine's stored JOB_TRACE_CONTEXT row. traceparent is required in the schema, tracestate optional.
 */
final class TraceContext
{
    public function __construct(
        public readonly string $traceparent,
        public readonly ?string $tracestate = null,
    ) {
    }

    /**
     * The trace context of a decoded work item, or null when the server sent none (or no traceparent).
     *
     * @param array<string, mixed> $d
     */
    public static function fromArray(array $d): ?self
    {
        if (!isset($d['traceparent']) || !is_string($d['traceparent'])) {
            return null;
        }
        return new self($d['traceparent'], isset($d['tracestate']) && is_string($d['tracestate']) ? $d['tracestate'] : null);
    }
}
