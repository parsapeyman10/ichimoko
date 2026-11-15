#!/usr/bin/env sh
# Run the source application directly on Linux or macOS.
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PYTHON_CMD=${PYTHON_CMD:-python3}
cd "$SCRIPT_DIR"
exec "$PYTHON_CMD" aurum_edge.py
