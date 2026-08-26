#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV_ROOT="$PROJECT_ROOT/workers/.venv"
PYTHON_BIN="${PYTHON_BIN:-python3}"

"$PYTHON_BIN" -m venv "$VENV_ROOT"
"$VENV_ROOT/bin/python" -m pip install --upgrade pip
"$VENV_ROOT/bin/python" -m pip install -r "$PROJECT_ROOT/workers/requirements.txt"

echo "AML Worker shared Python: $VENV_ROOT/bin/python"
