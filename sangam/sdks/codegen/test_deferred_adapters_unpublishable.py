# SPDX-License-Identifier: Apache-2.0
"""M-027 / RM-48: the framework adapters the owner kept out of the 0.1.0 publish set refuse publishing at the source,
so no release job (a solution-wide dotnet pack, a python -m build of the frameworks folder) can ship them by mistake.

- decision 132 (C-17): Sangam.Sdk.Hosting is source only: IsPackable false (dotnet pack of the solution skips it).
- decision 131 (C-16): the FastAPI adapter is deferred: its distribution carries the "Private :: Do Not Upload"
  classifier, which PyPI refuses on upload.

Runs with the other tests in this folder: python -m unittest discover -s sangam/sdks/codegen -p "test_*.py".
"""
import os
import sys
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
SDKS = os.path.abspath(os.path.join(HERE, ".."))
HOSTING = os.path.join(SDKS, "dotnet", "src", "Sangam.Sdk.Hosting", "Sangam.Sdk.Hosting.csproj")
CORE_DOTNET = os.path.join(SDKS, "dotnet", "src", "Sangam.Sdk", "Sangam.Sdk.csproj")
FASTAPI = os.path.join(SDKS, "python-frameworks", "fastapi", "pyproject.toml")


def is_packable(csproj):
    """The project's IsPackable value (MSBuild default true when absent)."""
    values = [e.text.strip().lower() for e in ET.parse(csproj).getroot().iter("IsPackable") if e.text]
    return values[-1] != "false" if values else True


class DeferredAdaptersAreNotPublishable(unittest.TestCase):
    def test_hosting_is_source_only(self):
        self.assertFalse(is_packable(HOSTING), "decision 132: Sangam.Sdk.Hosting is never packed in 0.1.0")

    def test_core_dotnet_sdk_stays_packable(self):
        self.assertTrue(is_packable(CORE_DOTNET), "decision 105: Sangam.Sdk is in the publish set")

    @unittest.skipIf(sys.version_info < (3, 11), "tomllib needs Python 3.11 (the codegen job runs 3.13)")
    def test_fastapi_distribution_refuses_upload(self):
        import tomllib
        with open(FASTAPI, "rb") as f:
            classifiers = tomllib.load(f)["project"]["classifiers"]
        self.assertIn("Private :: Do Not Upload", classifiers, "decision 131: the FastAPI adapter is not published")


if __name__ == "__main__":
    unittest.main()
