<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Log;

/** Default logger: one JSON object per line on STDERR, at or above $minLevel. */
final class StderrJsonLogger implements SangamLogger
{
    /** @var resource */
    private $stream;

    public function __construct(private readonly Level $minLevel = Level::Info, $stream = null)
    {
        $this->stream = $stream ?? (defined('STDERR') ? STDERR : fopen('php://stderr', 'wb'));
    }

    public function log(Level $level, string $event, string $message, array $fields = [], ?\Throwable $error = null): void
    {
        if ($level->rank() < $this->minLevel->rank()) {
            return;
        }
        $line = [
            'ts' => gmdate('Y-m-d\TH:i:s') . sprintf('.%03dZ', (int) ((microtime(true) * 1000) % 1000)),
            'level' => $level->value,
            'logger' => 'sangam.sdk',
            'event' => $event,
            'msg' => $message,
            'pid' => getmypid(),
        ] + $fields;
        if ($error !== null) {
            $line['error'] = get_class($error) . ': ' . $error->getMessage();
        }
        fwrite($this->stream, json_encode($line, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PARTIAL_OUTPUT_ON_ERROR) . "\n");
    }
}
