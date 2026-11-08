<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Http;

use Sangam\Sdk\Error\SangamException;

/** JSON helpers shared by the SDK. */
final class Json
{
    public static function encode(mixed $value): string
    {
        try {
            return json_encode(self::prepare($value), JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES
                | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION);
        } catch (\JsonException $e) {
            throw new SangamException('cannot encode JSON: ' . $e->getMessage(), 0, $e);
        }
    }

    public static function decode(string $raw): mixed
    {
        if ($raw === '') {
            return null;
        }
        try {
            return json_decode($raw, true, 512, JSON_THROW_ON_ERROR | JSON_BIGINT_AS_STRING);
        } catch (\JsonException $e) {
            throw new SangamException('cannot decode JSON: ' . $e->getMessage(), 0, $e);
        }
    }

    /**
     * A JSON object from a PHP map: null stays null, [] becomes {} (PHP would encode it as []).
     *
     * @param array<string, mixed>|null $map
     */
    public static function obj(?array $map): ?object
    {
        return $map === null ? null : (object) $map;
    }

    /** Drops null members of request bodies (the server treats absent and null alike). */
    private static function prepare(mixed $value): mixed
    {
        if (is_array($value) && !array_is_list($value)) {
            $out = [];
            foreach ($value as $k => $v) {
                if ($v !== null) {
                    $out[$k] = self::prepare($v);
                }
            }
            return $out === [] ? new \stdClass() : $out;
        }
        if (is_array($value)) {
            return array_map(static fn(mixed $v): mixed => self::prepare($v), $value);
        }
        return $value;
    }
}
