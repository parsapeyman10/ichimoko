#!/usr/bin/env sh
# Builds the native artifact for the current Linux or macOS host.
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PYTHON_CMD=${PYTHON_CMD:-}

if [ -z "$PYTHON_CMD" ]; then
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_CMD=python3
  elif command -v python >/dev/null 2>&1; then
    PYTHON_CMD=python
  else
    echo "[ERROR] Python 3.11 or newer was not found." >&2
    exit 1
  fi
fi

cd "$SCRIPT_DIR"
exec "$PYTHON_CMD" build_desktop.py "$@"
