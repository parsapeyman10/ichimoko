#!/usr/bin/env sh
# Run the PyQt6 source application directly on Linux or macOS.
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PYTHON_CMD=${PYTHON_CMD:-python3}
cd "$SCRIPT_DIR"

if ! "$PYTHON_CMD" -c 'from PyQt6.QtWidgets import QApplication' >/dev/null 2>&1; then
    echo "PyQt6 is not installed for $PYTHON_CMD." >&2
    echo "Install it with: $PYTHON_CMD -m pip install -r requirements.txt" >&2
    exit 1
fi

exec "$PYTHON_CMD" aurum_edge.py
