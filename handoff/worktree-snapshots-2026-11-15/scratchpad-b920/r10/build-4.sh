set -euo pipefail
python -m pip install --disable-pip-version-check build==1.2.2.post1 twine==6.1.0
python -m build -o ../../../packages/pypi
python -m twine check --strict ../../../packages/pypi/*
