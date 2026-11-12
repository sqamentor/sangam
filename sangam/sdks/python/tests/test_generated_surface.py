# SPDX-License-Identifier: Apache-2.0
"""M-020 coverage guard: every operation of sangam-api.yaml has a generated method (named by the manifest that
sdks/codegen/generate_sdk_surface.py writes), every schema a TypedDict, and ``SangamClient.api`` exposes the surface."""
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
OPERATION_ID = re.compile(r"^ {6}operationId: (\S+)$", re.M)


def _manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def test_manifest_covers_every_contract_operation() -> None:
    contract = set(OPERATION_ID.findall(CONTRACT.read_text(encoding="utf-8")))
    generated = {r["operationId"] for r in _manifest()["surface"]}
    assert len(contract) > 1000, "contract layout changed?"
    assert contract == generated, "the generated surface is stale: run sdks/codegen/generate_sdk_surface.py"


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
