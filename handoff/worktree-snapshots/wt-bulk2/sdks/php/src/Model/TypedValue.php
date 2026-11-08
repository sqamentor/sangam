<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Model;

/**
 * R33 typed JSON value of a VariableMap: {"type": "...", "value": ...}. Money is never a binary float: Decimal,
 * BigInteger, Long, Date, LocalDate and Bytes (base64) travel as strings. Plain JSON values pass through too.
 * Serialises itself (JsonSerializable), so it can be put straight into any variables array.
 */
final class TypedValue implements \JsonSerializable
{
    public const DECIMAL = 'Decimal';
    public const BIG_INTEGER = 'BigInteger';
    public const LONG = 'Long';
    public const INTEGER = 'Integer';
    public const BOOLEAN = 'Boolean';
    public const STRING = 'String';
    public const DATE = 'Date';
    public const LOCAL_DATE = 'LocalDate';
    public const JSON = 'Json';
    public const BYTES = 'Bytes';

    public const TYPES = [self::DECIMAL, self::BIG_INTEGER, self::LONG, self::INTEGER, self::BOOLEAN, self::STRING,
        self::DATE, self::LOCAL_DATE, self::JSON, self::BYTES];

    public function __construct(public readonly string $type, public readonly mixed $value)
    {
        if (!in_array($type, self::TYPES, true)) {
            throw new \InvalidArgumentException('unknown TypedValue type ' . $type);
        }
    }

    /** Exact decimal from a numeric string or int, for example decimal('1234.50'). Floats are rejected (R33). */
    public static function decimal(string|int $value): self
    {
        $v = (string) $value;
        if (!preg_match('/^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$/', $v)) {
            throw new \InvalidArgumentException('not a decimal: ' . $v);
        }
        return new self(self::DECIMAL, $v);
    }

    public static function bigInteger(string|int $value): self
    {
        $v = (string) $value;
        if (!preg_match('/^[+-]?\d+$/', $v)) {
            throw new \InvalidArgumentException('not an integer: ' . $v);
        }
        return new self(self::BIG_INTEGER, $v);
    }

    public static function long(string|int $value): self
    {
        $v = (string) $value;
        if (!preg_match('/^[+-]?\d+$/', $v)) {
            throw new \InvalidArgumentException('not an integer: ' . $v);
        }
        return new self(self::LONG, $v);
    }

    public static function integer(int $value): self
    {
        return new self(self::INTEGER, $value);
    }

    public static function boolean(bool $value): self
    {
        return new self(self::BOOLEAN, $value);
    }

    public static function string(string $value): self
    {
        return new self(self::STRING, $value);
    }

    /** Zoned instant, sent as ISO-8601 UTC. */
    public static function date(\DateTimeInterface|string $value): self
    {
        if ($value instanceof \DateTimeInterface) {
            $value = \DateTimeImmutable::createFromInterface($value)->setTimezone(new \DateTimeZone('UTC'))
                ->format('Y-m-d\TH:i:s.v\Z');
        }
        return new self(self::DATE, $value);
    }

    /** Unzoned calendar date "YYYY-MM-DD". */
    public static function localDate(\DateTimeInterface|string $value): self
    {
        if ($value instanceof \DateTimeInterface) {
            $value = $value->format('Y-m-d');
        }
        return new self(self::LOCAL_DATE, $value);
    }

    /** Arbitrary JSON (object, array, scalar). */
    public static function json(mixed $value): self
    {
        return new self(self::JSON, $value);
    }

    /** Binary content, sent base64-encoded. */
    public static function bytes(string $binary): self
    {
        return new self(self::BYTES, base64_encode($binary));
    }

    /** True when $v has the TypedValue wire shape {"type": <known type>, "value": ...}. */
    public static function isTyped(mixed $v): bool
    {
        return is_array($v) && count($v) === 2 && array_key_exists('type', $v) && array_key_exists('value', $v)
            && is_string($v['type']) && in_array($v['type'], self::TYPES, true);
    }

    public static function fromArray(array $v): self
    {
        return new self((string) $v['type'], $v['value']);
    }

    /**
     * PHP value of a VariableMap entry. Decimal and BigInteger stay numeric strings (exact); Long becomes int when it
     * fits, else stays a string; Integer -> int; Boolean -> bool; Date/LocalDate -> ISO string (see toDateTime());
     * Bytes -> decoded binary string; Json -> the value. Plain (untyped) values are returned unchanged.
     */
    public static function unwrap(mixed $v): mixed
    {
        if (!self::isTyped($v)) {
            return $v;
        }
        $t = $v['type'];
        $x = $v['value'];
        if ($x === null) {
            return null;
        }
        return match ($t) {
            self::DECIMAL, self::BIG_INTEGER, self::STRING, self::DATE, self::LOCAL_DATE => (string) $x,
            self::LONG => (is_int($x) || (is_string($x) && filter_var($x, FILTER_VALIDATE_INT) !== false))
                ? (int) $x : (string) $x,
            self::INTEGER => (int) $x,
            self::BOOLEAN => is_bool($x) ? $x : in_array(strtolower((string) $x), ['true', '1'], true),
            self::BYTES => (string) base64_decode((string) $x, true),
            default => $x,
        };
    }

    /** Date / LocalDate (typed or plain ISO string) as DateTimeImmutable, or null. */
    public static function toDateTime(mixed $v): ?\DateTimeImmutable
    {
        $s = self::unwrap($v);
        if (!is_string($s) || $s === '') {
            return null;
        }
        try {
            return new \DateTimeImmutable($s);
        } catch (\Exception) {
            return null;
        }
    }

    /** @return array{type: string, value: mixed} */
    public function jsonSerialize(): array
    {
        return ['type' => $this->type, 'value' => $this->value];
    }
}
