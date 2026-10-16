#!/usr/bin/env bash
set -euo pipefail

tool_key=$1
shift

if [[ -z "$tool_key" || $# -eq 0 ]]; then
  echo "tool group dispatcher requires a tool key and command" >&2
  exit 2
fi

exec "$@"
