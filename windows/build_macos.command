#!/usr/bin/env sh
# Double-clickable macOS wrapper. If Gatekeeper asks, allow it only after reviewing source.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$SCRIPT_DIR/build_desktop.sh" "$@"
