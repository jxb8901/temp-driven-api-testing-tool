#!/usr/bin/env sh
set -eu

output=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output)
      output="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

if [ -z "$output" ]; then
  echo "missing --output" >&2
  exit 2
fi

mkdir -p "$(dirname "$output")"
cat > "$output" <<YAML
matched: true
keywords:
  - PAYMENT
  - POSTED
YAML
