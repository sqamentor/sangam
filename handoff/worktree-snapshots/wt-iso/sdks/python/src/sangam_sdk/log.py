# SPDX-License-Identifier: Apache-2.0
"""Structured logging hook.

Every SDK log line is an ``event`` name (stable, machine-readable, e.g. ``work.completed``) plus key/value
``fields`` (workId, topic, operationId, attemptId, lockVersion, businessKey, ...). By default lines go to the
stdlib logger ``sangam_sdk`` with ``extra={"sangam_event": ..., "sangam_fields": {...}}`` so a JSON formatter can
emit them as-is. Pass ``hook=callable(level, event, message, fields, error)`` to route them elsewhere.
"""
from __future__ import annotations

import logging
from typing import Any, Callable, Mapping, Optional

LogHook = Callable[[int, str, str, Mapping[str, Any], Optional[BaseException]], None]


class SangamLogger:
    def __init__(self, logger: logging.Logger | None = None, hook: LogHook | None = None,
                 base_fields: Mapping[str, Any] | None = None) -> None:
        self._logger = logger or logging.getLogger("sangam_sdk")
        self._hook = hook
        self._base = dict(base_fields or {})

    def bind(self, **fields: Any) -> "SangamLogger":
        """A child logger whose lines always carry ``fields``."""
        merged = dict(self._base)
        merged.update(fields)
        return SangamLogger(self._logger, self._hook, merged)

    @property
    def fields(self) -> dict[str, Any]:
        return dict(self._base)

    def log(self, level: int, event: str, message: str, fields: Mapping[str, Any] | None = None,
            error: BaseException | None = None) -> None:
        merged = dict(self._base)
        if fields:
            merged.update(fields)
        if self._hook is not None:
            self._hook(level, event, message, merged, error)
            return
        if not self._logger.isEnabledFor(level):
            return
        kv = " ".join(f"{k}={v}" for k, v in merged.items())
        self._logger.log(level, 'event=%s msg="%s"%s', event, message, (" " + kv) if kv else "",
                         exc_info=error, extra={"sangam_event": event, "sangam_fields": merged})

    def debug(self, event: str, message: str, fields: Mapping[str, Any] | None = None) -> None:
        self.log(logging.DEBUG, event, message, fields)

    def info(self, event: str, message: str, fields: Mapping[str, Any] | None = None) -> None:
        self.log(logging.INFO, event, message, fields)

    def warn(self, event: str, message: str, fields: Mapping[str, Any] | None = None,
             error: BaseException | None = None) -> None:
        self.log(logging.WARNING, event, message, fields, error)

    def error(self, event: str, message: str, fields: Mapping[str, Any] | None = None,
              error: BaseException | None = None) -> None:
        self.log(logging.ERROR, event, message, fields, error)


def noop_logger() -> SangamLogger:
    return SangamLogger(hook=lambda *_: None)
