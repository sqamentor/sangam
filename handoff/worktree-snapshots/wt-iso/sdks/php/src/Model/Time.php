<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Model;

/** ISO-8601 UTC helpers (ARCHITECTURE section 4: times are ISO-8601 UTC). */
final class Time
{
    public static function parse(?string $iso): ?float
    {
        if ($iso === null || $iso === '') {
            return null;
        }
        try {
            $dt = new \DateTimeImmutable($iso);
        } catch (\Exception) {
            return null;
        }
        return (float) $dt->format('U.u');
    }

    public static function iso(float $ts): string
    {
        $dt = \DateTimeImmutable::createFromFormat('U.u', sprintf('%.6F', $ts), new \DateTimeZone('UTC'));
        return $dt === false ? gmdate('Y-m-d\TH:i:s\Z', (int) $ts) : $dt->format('Y-m-d\TH:i:s.v\Z');
    }
}
