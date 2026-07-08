#!/usr/bin/env sh
set -eu

input=""
output=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --input)
      input="$2"
      shift 2
      ;;
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
if [ -n "$input" ] && [ -f "$input" ]; then
  cat "$input" > "$output"
else
  cat > "$output" <<XML
<Response>
  <Status>SUCCESS</Status>
  <RejectCode>0000</RejectCode>
</Response>
XML
fi
