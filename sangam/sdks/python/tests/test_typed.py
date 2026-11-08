# SPDX-License-Identifier: Apache-2.0
"""R33 / T18 typed-value round trips: money never travels as a binary float."""
from datetime import date, datetime, timezone
from decimal import Decimal

import pytest

from sangam_sdk import TypedValue, decode_value, decode_variables, encode_variables


def test_decimal_is_a_string_on_the_wire():
    assert encode_variables({"amount": Decimal("12.50")}) == {"amount": {"type": "Decimal", "value": "12.50"}}


def test_big_integer_outside_js_safe_range():
    big = 2 ** 60
    wire = encode_variables({"n": big, "small": 7, "flag": True})
    assert wire == {"n": {"type": "BigInteger", "value": str(big)}, "small": 7, "flag": True}


def test_dates_and_bytes():
    wire = encode_variables({"at": datetime(2026, 1, 2, 3, 4, 5), "on": date(2026, 1, 2), "b": b"\x00\x01"})
    assert wire["at"] == {"type": "Date", "value": "2026-01-02T03:04:05Z"}
    assert wire["on"] == {"type": "LocalDate", "value": "2026-01-02"}
    assert wire["b"] == {"type": "Bytes", "value": "AAE="}


def test_round_trip():
    src = {"amount": Decimal("99.99"), "on": date(2026, 3, 31), "at": datetime(2026, 3, 31, tzinfo=timezone.utc),
           "b": b"abc", "plain": {"k": [1, 2]}, "none": None}
    assert decode_variables(encode_variables(src)) == src


def test_decode_plain_and_none():
    assert decode_value({"type": "Decimal", "value": None}) is None
    assert decode_value({"other": 1}) == {"other": 1}
    assert encode_variables(None) is None


def test_unknown_type_rejected():
    with pytest.raises(ValueError):
        TypedValue("Float", 1.0)
