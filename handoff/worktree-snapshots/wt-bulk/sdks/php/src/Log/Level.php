<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Log;

/** Log levels; the string values match PSR-3 level names. */
enum Level: string
{
    case Debug = 'debug';
    case Info = 'info';
    case Warning = 'warning';
    case Error = 'error';

    public function rank(): int
    {
        return match ($this) {
            self::Debug => 0,
            self::Info => 1,
            self::Warning => 2,
            self::Error => 3,
        };
    }
}
