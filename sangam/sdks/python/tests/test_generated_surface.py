# SPDX-License-Identifier: Apache-2.0
"""M-020 coverage guard: every operation of sangam-api.yaml has a generated method (named by the manifest that
sdks/codegen/generate_sdk_surface.py writes), every schema a TypedDict, and ``SangamClient.api`` exposes the surface.

The one exception is explicit in the contract: an operation marked ``x-sangam-sdk: false`` is called by an outside
system, never by an SDK user (the CloudEvents webhook handshake that Azure Event Grid sends as OPTIONS), and gets no SDK
method. The generator (sdks/codegen/contract_ir.py) leaves out exactly the operations so marked and lists them in the
manifest under ``notForSdk``; this test expects the same split."""
import json
import re
import typing
from pathlib import Path

from sangam_sdk import SangamClient
from sangam_sdk.generated import api as gen_api
from sangam_sdk.generated import models

SDKS = Path(__file__).resolve().parents[2]
CONTRACT = SDKS.parent / "sangam-rest" / "api" / "sangam-api.yaml"
MANIFEST = SDKS / "codegen" / "surface-manifest.json"
OPERATION_ID = re.compile(r"^ {6}operationId: (\S+)$")
OPERATION_END = re.compile(r"^ {0,4}\S")
SDK_MARKER = re.compile(r"^ {6}x-sangam-sdk:(.*)$")


def _manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def _contract_operations(lines: list[str]) -> tuple[set[str], set[str]]:
    """The operationIds of the contract: those an SDK method is generated for, and those marked x-sangam-sdk: false."""
    for_sdk: set[str] = set()
    not_for_sdk: set[str] = set()
    current = None
    for line in lines:
        m = OPERATION_ID.match(line)
        if m:
            current = m.group(1)
            for_sdk.add(current)
        elif OPERATION_END.match(line):
            current = None
        else:
            marker = SDK_MARKER.match(line)
            if marker:
                assert current is not None, f"x-sangam-sdk outside an operation (after its operationId): {line}"
                assert marker.group(1).strip() == "false", f"only 'x-sangam-sdk: false' is written: {line}"
                for_sdk.discard(current)
                not_for_sdk.add(current)
    return for_sdk, not_for_sdk


def test_manifest_covers_every_contract_operation() -> None:
    for_sdk, not_for_sdk = _contract_operations(CONTRACT.read_text(encoding="utf-8").splitlines())
    generated = {r["operationId"] for r in _manifest()["surface"]}
    left = {r["operationId"] for r in _manifest()["notForSdk"]}
    assert len(for_sdk) > 1000, "contract layout changed?"
    assert for_sdk == generated, "the generated surface is stale: run sdks/codegen/generate_sdk_surface.py"
    assert not_for_sdk == left, "the operations without an SDK method are exactly those marked x-sangam-sdk: false"
    assert left == {"eventGridHookHandshake"}, (
        "an operation leaves the SDKs only by a reviewed marker: add it here with the reason")


def test_the_marker_is_read_per_operation() -> None:
    for_sdk, not_for_sdk = _contract_operations([
        "paths:", "  /hooks/x:", "    options:", "      operationId: handshake", "      responses:",
        "        '200':", "          description: Success", "      x-sangam-sdk: false", "    post:",
        "      operationId: deliver", "  /cases:", "    get:", "      operationId: listCases"])
    assert for_sdk == {"deliver", "listCases"}
    assert not_for_sdk == {"handshake"}


def test_every_operation_has_a_method_with_resolvable_annotations() -> None:
    client = SangamClient("http://127.0.0.1:1")
    missing = []
    for r in _manifest()["surface"]:
        group, name = r["python"].split(".")
        fn = getattr(getattr(client.api, group, None), name, None)
        if not callable(fn):
            missing.append(f"{r['operationId']} -> {r['python']}")
            continue
        typing.get_type_hints(fn)  # every annotation names a real model / type
    assert not missing, f"operations without a generated Python method: {missing}"


def test_every_schema_is_a_typed_dict() -> None:
    for name in _manifest()["models"]:
        t = getattr(models, name)
        assert typing.is_typeddict(t), name
        typing.get_type_hints(t)


def test_typed_dicts_keep_the_wire_names() -> None:
    hints = typing.get_type_hints(models.WorkCompleteRequest)
    assert {"workerId", "lockVersion", "attemptId"} <= set(hints)
    assert models.WorkCompleteRequest.__required_keys__ >= {"workerId", "lockVersion"}


def test_raw_and_paged_helpers_are_wired() -> None:
    assert hasattr(gen_api, "RequestOptions")
    assert gen_api.SangamApis is type(SangamClient("http://127.0.0.1:1").api)
