# SPDX-License-Identifier: Apache-2.0
"""Tier-2 SDKs (ADR-D-4, R18 "generated, community-maintained"; M-022): Go, Rust and Kotlin REST clients generated
from sangam-rest/api/sangam-api.yaml with openapi-generator, next to a small hand-written worker-loop helper per
language (sdks/go/worker, sdks/rust/sangam-worker, sdks/kotlin).

The generated clients are build outputs, not sources: this script writes them under sdks/tier2/out/<lang> (ignored by
git) and sdks.yml compiles them on every change of the contract. The generator is pinned (OPENAPI_GENERATOR_VERSION)
and runs either from a local jar or from the official image:

  python sdks/tier2/generate_tier2.py --jar path/to/openapi-generator-cli-7.10.0.jar   # any of go, rust, kotlin
  python sdks/tier2/generate_tier2.py --docker                                        # CI
  python sdks/tier2/generate_tier2.py --docker --lang=go

Each language has a config file next to this script (package names and library choices). --print shows the commands
without running them.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys

OPENAPI_GENERATOR_VERSION = "7.10.0"
IMAGE = f"openapitools/openapi-generator-cli:v{OPENAPI_GENERATOR_VERSION}"
HERE = os.path.dirname(os.path.abspath(__file__))
SANGAM = os.path.abspath(os.path.join(HERE, "..", ".."))
CONTRACT_REL = "sangam-rest/api/sangam-api.yaml"
LANGS = {
    # language -> (openapi-generator name, config file)
    "go": ("go", "config-go.yaml"),
    "rust": ("rust", "config-rust.yaml"),
    "kotlin": ("kotlin", "config-kotlin.yaml"),
}
# Generator switches a language needs beyond its config file.
# kotlin, skipFormModel=false: caseFileAddDocument takes application/json (CfDocumentRequest) or multipart/form-data (an
# inline object). The generator names the inline object caseFileAddDocument_request and, by default, writes no class
# for a schema used only by form parameters ("not generated since it's marked as unused (due to form parameters) and
# `skipFormModel` (global property) set to true"), but the operation's JSON body makes CaseFilesApi.kt import it all
# the same: "CaseFilesApi.kt:22:33 Unresolved reference: CaseFileAddDocumentRequest" (sdks.yml tier2-kotlin,
# 2026-11-15). With the switch off the form models are written, so every import resolves; no operation or schema is
# left out. Go and Rust build without it (their generated code does not import the model).
EXTRA_ARGS = {
    "kotlin": ["--global-property", "skipFormModel=false"],
}


def command(lang: str, jar: str | None) -> list[str]:
    gen, cfg = LANGS[lang]
    out_rel = f"sdks/tier2/out/{lang}"
    args = ["generate", "-g", gen, "-c", f"sdks/tier2/{cfg}", "-o", out_rel,
            # the contract is validated by gen_openapi.py and its own CI gates; generator-side validation would only
            # repeat it with a different (stricter on 3.1 keywords) parser
            "--skip-validate-spec", *EXTRA_ARGS.get(lang, [])]
    if jar:
        return ["java", "-jar", jar, *args[:1], "-i", CONTRACT_REL, *args[1:]]
    return ["docker", "run", "--rm", "-u", f"{os.getuid()}:{os.getgid()}" if hasattr(os, "getuid") else "0:0",
            "-v", f"{SANGAM}:/local", "-w", "/local", IMAGE, *args[:1], "-i", CONTRACT_REL, *args[1:]]


# Heap of the Gradle daemon and of the Kotlin compile daemon for the generated Kotlin client. The contract has more than
# 1100 operations and the generator writes one class per tag (RegistryApi.kt alone has more than 12 000 lines); with
# Gradle's default daemon heap (512 MiB, which the Kotlin daemon inherits) compileKotlin ends in
# "java.lang.OutOfMemoryError: Java heap space" (sdks.yml tier2-kotlin, 2026-11-15). The jvm-okhttp4 library of the
# generator writes no gradle.properties of its own.
KOTLIN_BUILD_HEAP = "4g"


def write_kotlin_build_memory(out: str) -> None:
    path = os.path.join(out, "gradle.properties")
    if os.path.exists(path):
        raise RuntimeError(f"{path}: the generator now writes gradle.properties; merge the heap settings into it")
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("# written by sdks/tier2/generate_tier2.py (not by openapi-generator)\n"
                 f"org.gradle.jvmargs=-Xmx{KOTLIN_BUILD_HEAP}\n"
                 f"kotlin.daemon.jvmargs=-Xmx{KOTLIN_BUILD_HEAP}\n")


def main(argv: list[str]) -> int:
    jar = next((a.split("=", 1)[1] for a in argv if a.startswith("--jar=")), None)
    if "--jar" in argv:
        jar = argv[argv.index("--jar") + 1]
    docker = "--docker" in argv
    show = "--print" in argv
    langs = [a.split("=", 1)[1] for a in argv if a.startswith("--lang=")] or list(LANGS)
    if not jar and not docker and not show:
        print(__doc__)
        return 2
    for lang in langs:
        cmd = command(lang, jar)
        print("$", " ".join(cmd))
        if show:
            continue
        out = os.path.join(HERE, "out", lang)
        shutil.rmtree(out, ignore_errors=True)
        r = subprocess.run(cmd, cwd=SANGAM)
        if r.returncode != 0:
            return r.returncode
        if lang == "kotlin":
            write_kotlin_build_memory(out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
