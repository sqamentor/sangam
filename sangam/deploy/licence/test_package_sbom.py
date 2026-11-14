# SPDX-License-Identifier: Apache-2.0
"""Unit tests of package_sbom.py (M-147, CR-03): zero-dependency SDK packages only, never a guess.

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml). Written in Wave 4,
run in the test phase."""
import json
import os
import sys
import tempfile
import unittest

import package_sbom as ps

HERE = os.path.dirname(os.path.abspath(__file__))
SDKS = os.path.join(HERE, "..", "..", "sdks")


class PackageSbom(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, text):
        p = os.path.join(self.tmp.name, name)
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)
        return p

    def run_main(self, eco, manifest):
        out = os.path.join(self.tmp.name, "out.cdx.json")
        return ps.main(["--ecosystem", eco, "--manifest", manifest, "--version", "0.1.0", "--out", out]), out

    def test_npm_zero_deps(self):
        m = self.write("package.json", json.dumps({"name": "@sangam/sdk", "license": "Apache-2.0",
                                                    "devDependencies": {"typescript": "5.9.2"}}))
        code, out = self.run_main("npm", m)
        self.assertEqual(code, 0)
        with open(out, encoding="utf-8") as f:
            sbom = json.load(f)
        self.assertEqual(sbom["components"], [])
        self.assertEqual(sbom["metadata"]["component"]["purl"], "pkg:npm/%40sangam/sdk@0.1.0")
        self.assertEqual(sbom["metadata"]["component"]["licenses"], [{"expression": "Apache-2.0"}])

    def test_npm_runtime_dependency_fails(self):
        for key in ("dependencies", "peerDependencies", "optionalDependencies"):
            m = self.write("package.json", json.dumps({"name": "x", "license": "MIT", key: {"left-pad": "1"}}))
            self.assertEqual(self.run_main("npm", m)[0], 1, key)

    def test_missing_licence_is_blocked(self):
        m = self.write("package.json", json.dumps({"name": "x"}))
        self.assertEqual(self.run_main("npm", m)[0], 2)
        self.assertEqual(self.run_main("npm", os.path.join(self.tmp.name, "nope.json"))[0], 2)

    @unittest.skipIf(ps.tomllib is None, "tomllib needs Python 3.11")
    def test_pypi(self):
        ok = self.write("pyproject.toml", '[project]\nname = "Sangam_SDK"\nlicense = { text = "Apache-2.0" }\n'
                                          'dependencies = []\n[project.optional-dependencies]\ndev = ["pytest"]\n')
        code, out = self.run_main("pypi", ok)
        self.assertEqual(code, 0)
        with open(out, encoding="utf-8") as f:
            self.assertEqual(json.load(f)["metadata"]["component"]["purl"], "pkg:pypi/sangam-sdk@0.1.0")
        bad = self.write("pyproject.toml", '[project]\nname = "x"\nlicense = "MIT"\ndependencies = ["requests"]\n')
        self.assertEqual(self.run_main("pypi", bad)[0], 1)

    def test_nuget(self):
        ok = self.write("Sangam.Sdk.csproj", '<Project><PropertyGroup><PackageId>Sangam.Sdk</PackageId>'
                        '<PackageLicenseExpression>Apache-2.0</PackageLicenseExpression></PropertyGroup><ItemGroup>'
                        '<PackageReference Include="Microsoft.SourceLink.GitHub" PrivateAssets="all" />'
                        '<PackageReference Include="Analyzer"><PrivateAssets>all</PrivateAssets></PackageReference>'
                        '</ItemGroup></Project>')
        self.assertEqual(self.run_main("nuget", ok)[0], 0)
        bad = self.write("Sangam.Sdk.csproj", '<Project><PropertyGroup><PackageLicenseExpression>MIT'
                         '</PackageLicenseExpression></PropertyGroup><ItemGroup>'
                         '<PackageReference Include="Newtonsoft.Json" /></ItemGroup></Project>')
        self.assertEqual(self.run_main("nuget", bad)[0], 1)

    def test_packagist_platform_requirements_only(self):
        ok = self.write("composer.json", json.dumps({"name": "sangam/sdk", "license": "Apache-2.0",
                                                     "require": {"php": ">=8.2", "ext-curl": "*"},
                                                     "require-dev": {"phpunit/phpunit": "^11.5"}}))
        self.assertEqual(self.run_main("packagist", ok)[0], 0)
        bad = self.write("composer.json", json.dumps({"name": "sangam/sdk", "license": "Apache-2.0",
                                                      "require": {"guzzlehttp/guzzle": "^7"}}))
        self.assertEqual(self.run_main("packagist", bad)[0], 1)

    def test_repository_sdks_have_no_runtime_dependency(self):
        cases = [("npm", os.path.join(SDKS, "typescript", "package.json")),
                 ("packagist", os.path.join(SDKS, "php", "composer.json")),
                 ("nuget", os.path.join(SDKS, "dotnet", "src", "Sangam.Sdk", "Sangam.Sdk.csproj"))]
        if ps.tomllib is not None:
            cases.append(("pypi", os.path.join(SDKS, "python", "pyproject.toml")))
        for eco, manifest in cases:
            self.assertEqual(self.run_main(eco, manifest)[0], 0, manifest)


if __name__ == "__main__":
    sys.exit(unittest.main())
