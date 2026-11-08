# SPDX-License-Identifier: Apache-2.0
"""R33 typed values (``TypedValue`` / ``VariableMap`` in sangam-api.yaml 0.1.0).

On the wire a variable is either plain JSON or ``{"type": T, "value": v}`` with
``T`` in Decimal | BigInteger | Long | Integer | Boolean | String | Date | LocalDate | Json | Bytes.
Decimal, BigInteger, Long, Date, LocalDate and Bytes (base64) carry their value as a string, so money is never a
binary float (E18, T18).

* Encoding (:func:`encode_variables`): ``Decimal`` -> Decimal, ``int`` outside the JS-safe range -> BigInteger,
  ``datetime`` -> Date (ISO-8601, UTC when naive), ``date`` -> LocalDate, ``bytes`` -> Bytes, :class:`TypedValue`
  as given; everything else passes through as plain JSON.
* Decoding (:func:`decode_value`): unwraps a TypedValue to the matching Python type.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import date, datetime, timezone
from decimal import Decimal
from typing import Any, Mapping, Optional

TYPES = ("Decimal", "BigInteger", "Long", "Integer", "Boolean", "String", "Date", "LocalDate", "Json", "Bytes")
_SAFE_INT = 2 ** 53 - 1


@dataclass(frozen=True)
class TypedValue:
    type: str
    value: Any

    def __post_init__(self) -> None:
        if self.type not in TYPES:
            raise ValueError(f"unknown TypedValue type {self.type!r}")

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.type, "value": self.value}

    # ---- builders

    @staticmethod
    def decimal(v: Decimal | str | int) -> "TypedValue":
        return TypedValue("Decimal", str(v if isinstance(v, Decimal) else Decimal(str(v))))

    @staticmethod
    def big_integer(v: int | str) -> "TypedValue":
        return TypedValue("BigInteger", str(int(v)))

    @staticmethod
    def long(v: int | str) -> "TypedValue":
        return TypedValue("Long", str(int(v)))

    @staticmethod
    def integer(v: int) -> "TypedValue":
        return TypedValue("Integer", int(v))

    @staticmethod
    def boolean(v: bool) -> "TypedValue":
        return TypedValue("Boolean", bool(v))

    @staticmethod
    def string(v: str) -> "TypedValue":
        return TypedValue("String", str(v))

    @staticmethod
    def date(v: datetime | str) -> "TypedValue":
        if isinstance(v, datetime):
            dt = v if v.tzinfo is not None else v.replace(tzinfo=timezone.utc)
            v = dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        return TypedValue("Date", str(v))

    @staticmethod
    def local_date(v: date | str) -> "TypedValue":
        return TypedValue("LocalDate", v.isoformat() if isinstance(v, date) else str(v))

    @staticmethod
    def bytes(v: bytes) -> "TypedValue":
        return TypedValue("Bytes", base64.b64encode(v).decode("ascii"))

    @staticmethod
    def json(v: Any) -> "TypedValue":
        return TypedValue("Json", v)


def is_typed(v: Any) -> bool:
    return isinstance(v, Mapping) and set(v.keys()) == {"type", "value"} and v.get("type") in TYPES


def encode_value(v: Any) -> Any:
    if isinstance(v, TypedValue):
        return v.to_dict()
    if isinstance(v, bool):
        return v
    if isinstance(v, Decimal):
        return TypedValue.decimal(v).to_dict()
    if isinstance(v, int):
        return v if -_SAFE_INT <= v <= _SAFE_INT else TypedValue.big_integer(v).to_dict()
    if isinstance(v, datetime):
        return TypedValue.date(v).to_dict()
    if isinstance(v, date):
        return TypedValue.local_date(v).to_dict()
    if isinstance(v, (bytes, bytearray)):
        return TypedValue.bytes(bytes(v)).to_dict()
    return v


def encode_variables(m: Optional[Mapping[str, Any]]) -> Optional[dict[str, Any]]:
    """A VariableMap ready for the wire (None stays None)."""
    if m is None:
        return None
    return {k: encode_value(v) for k, v in m.items()}


def decode_value(v: Any) -> Any:
    """Unwraps a TypedValue (``{"type":"Decimal","value":"12.50"}`` -> ``Decimal("12.50")``); plain values as-is."""
    if not is_typed(v):
        return v
    t, val = v["type"], v["value"]
    if val is None:
        return None
    if t == "Decimal":
        return Decimal(str(val))
    if t in ("BigInteger", "Long", "Integer"):
        return int(val)
    if t == "Boolean":
        return val if isinstance(val, bool) else str(val).lower() == "true"
    if t == "String":
        return str(val)
    if t == "Date":
        s = str(val)
        return datetime.fromisoformat(s[:-1] + "+00:00" if s.endswith("Z") else s)
    if t == "LocalDate":
        return date.fromisoformat(str(val))
    if t == "Bytes":
        return base64.b64decode(str(val))
    return val  # Json


def decode_variables(m: Optional[Mapping[str, Any]]) -> dict[str, Any]:
    return {k: decode_value(v) for k, v in (m or {}).items()}
