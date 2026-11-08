// SPDX-License-Identifier: Apache-2.0

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

export type LogFields = Record<string, unknown>;

/**
 * Structured logging hook. Every SDK log line is a stable `event` name (for example `work.completed`) plus
 * key/value `fields` (workId, topic, operationId, attemptId, lockVersion, businessKey, ...).
 * Plug in pino, winston, OpenTelemetry logs or metrics by implementing this interface.
 */
export interface Logger {
  log(level: LogLevel, event: string, message: string, fields?: LogFields, error?: unknown): void;
}

const ORDER: Record<LogLevel, number> = { debug: 10, info: 20, warn: 30, error: 40 };

/** Discards everything. */
export const noopLogger: Logger = { log() {} };

/** One JSON object per line on stdout/stderr, at or above `minLevel` (default `info`). */
export function consoleJsonLogger(minLevel: LogLevel = 'info'): Logger {
  return {
    log(level, event, message, fields, error) {
      if (ORDER[level] < ORDER[minLevel]) return;
      const line: Record<string, unknown> = {
        ts: new Date().toISOString(),
        level,
        logger: '@sangam/sdk',
        event,
        msg: message,
        ...fields,
      };
      if (error !== undefined) {
        line.error = error instanceof Error ? { name: error.name, message: error.message, stack: error.stack } : String(error);
      }
      const out = JSON.stringify(line);
      if (level === 'error' || level === 'warn') console.error(out);
      else console.log(out);
    },
  };
}

/** Wraps a logger so every line carries `base` fields. */
export function withFields(logger: Logger, base: LogFields): Logger {
  return {
    log(level, event, message, fields, error) {
      logger.log(level, event, message, { ...base, ...fields }, error);
    },
  };
}
