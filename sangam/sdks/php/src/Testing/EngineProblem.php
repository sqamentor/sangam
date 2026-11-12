<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Testing;

/**
 * An RFC 9457 answer of the test engine, shaped like the server's ApiProblem / Problems.body(). The machine code is
 * $problemCode (Exception::$code is the integer code of PHP exceptions).
 */
final class EngineProblem extends \RuntimeException
{
    private const TYPE_BASE = 'https://sangam.dev/problems/';
    private const TITLES = [400 => 'Bad request', 404 => 'Not found', 409 => 'Conflict', 410 => 'Gone',
        422 => 'Unprocessable content'];

    /** @param array<string, mixed> $extensions */
    public function __construct(
        public readonly int $status,
        public readonly string $problemCode,
        string $detail,
        public readonly ?string $title = null,
        public readonly array $extensions = [],
    ) {
        parent::__construct($detail);
    }

    /** VI.3 rule 2 (ApiProblem.staleLease). */
    public static function stale(string $workId, string $reason, string $detail): self
    {
        return new self(409, 'STALE_LEASE', $detail, 'Stale lease', ['workId' => $workId, 'reason' => $reason]);
    }

    public static function notFound(string $what, string $id): self
    {
        return new self(404, 'NOT_FOUND', $what . ' ' . $id . ' was not found or is not visible to you', null,
            ['resource' => $what]);
    }

    /** @return array<string, mixed> */
    public function body(string $instance): array
    {
        $b = [
            'type' => self::TYPE_BASE . str_replace('_', '-', strtolower($this->problemCode)),
            'title' => $this->title ?? (self::TITLES[$this->status] ?? 'Error'),
            'status' => $this->status,
            'detail' => $this->getMessage(),
            'instance' => $instance,
            'code' => $this->problemCode,
        ];
        foreach ($this->extensions as $k => $v) {
            if ($v !== null && !array_key_exists($k, $b)) {
                $b[$k] = $v;
            }
        }
        return $b;
    }
}
