<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Error;

/**
 * RFC 9457 application/problem+json body. $code is the Sangam machine code (for example STALE_LEASE,
 * GONE_INSTANCE_DELETED); unknown members are kept in $extensions.
 */
final class Problem
{
    /** @param array<string, mixed> $extensions */
    public function __construct(
        public readonly ?string $type = null,
        public readonly ?string $title = null,
        public readonly ?int $status = null,
        public readonly ?string $detail = null,
        public readonly ?string $instance = null,
        public readonly ?string $code = null,
        public readonly ?string $traceId = null,
        public readonly array $extensions = [],
    ) {
    }

    /** @param array<string, mixed> $data */
    public static function fromArray(array $data, int $status): self
    {
        $known = ['type', 'title', 'status', 'detail', 'instance', 'code', 'traceId'];
        $ext = array_diff_key($data, array_flip($known));
        $str = static fn(string $k): ?string => isset($data[$k]) && is_scalar($data[$k]) ? (string) $data[$k] : null;
        return new self(
            $str('type'),
            $str('title'),
            isset($data['status']) && is_numeric($data['status']) ? (int) $data['status'] : $status,
            $str('detail'),
            $str('instance'),
            $str('code'),
            $str('traceId'),
            $ext,
        );
    }

    /** True when $code or the last segment of $type equals $name (case and -/_ insensitive). */
    public function is(string $name): bool
    {
        $n = self::norm($name);
        if ($this->code !== null && self::norm($this->code) === $n) {
            return true;
        }
        if ($this->type !== null) {
            $t = $this->type;
            $p = strrpos($t, '/');
            if ($p !== false) {
                $t = substr($t, $p + 1);
            }
            $h = strrpos($t, '#');
            if ($h !== false) {
                $t = substr($t, $h + 1);
            }
            return self::norm($t) === $n;
        }
        return false;
    }

    private static function norm(string $s): string
    {
        return str_replace('_', '-', strtolower($s));
    }
}
