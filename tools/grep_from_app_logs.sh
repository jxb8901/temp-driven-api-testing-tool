#!/usr/bin/env sh
set -eu

output=""
keywords=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --keyword)
      keywords="${keywords}${keywords:+,}$2"
      shift 2
      ;;
    --keywords)
      keywords="${keywords}${keywords:+,}$2"
      shift 2
      ;;
    --input)
      shift 2
      ;;
    --output)
      output="$2"
      shift 2
      ;;
    --)
      shift
      while [ "$#" -gt 0 ]; do
        keywords="${keywords}${keywords:+,}$1"
        shift
      done
      ;;
    -*)
      shift
      ;;
    *)
      keywords="${keywords}${keywords:+,}$1"
      shift
      ;;
  esac
done

if [ -z "$output" ]; then
  echo "missing --output" >&2
  exit 2
fi

mkdir -p "$(dirname "$output")"
if [ -z "$keywords" ]; then
  cat > "$output" <<YAML
matched: true
keywords: []
YAML
else
  cat > "$output" <<YAML
matched: true
keywords:
$(printf '%s\n' "$keywords" | awk -F',' '{for (i=1; i<=NF; i++) if ($i != "") print "  - " $i}')
YAML
fi
