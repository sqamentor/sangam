#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# SG-21 guard, run before every commit (and in CI): no source file may be hidden by .gitignore.
# A hidden source never reaches git, so a clean checkout does not compile (org.sangam.programme.target was lost).
# Build outputs under src/ (.NET bin/obj) and node_modules are expected to be ignored.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
untracked=$(git ls-files --others --ignored --exclude-standard \
  | grep -E '(^|/)src/' | grep -vE '(^|/)(node_modules|bin|obj)/' || true)
tracked=$(git ls-files -z | git check-ignore --no-index -z --stdin | tr '\0' '\n' | grep -E '(^|/)src/' || true)
if [ -n "$untracked$tracked" ]; then
  echo "Source files hidden by .gitignore:"
  printf '%s\n' "$untracked" "$tracked" | sed '/^$/d'
  exit 1
fi
echo "OK: no source file is hidden by .gitignore"
