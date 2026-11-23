#!/usr/bin/env sh
set -eu

shift || true
keywords="$*"
if [ -z "$keywords" ]; then
  cat <<YAML
matched: true
keywords: []
YAML
else
  cat <<YAML
matched: true
keywords:
$(printf '%s\n' "$keywords" | awk '{for (i=1; i<=NF; i++) if ($i != "") print "  - " $i}')
YAML
fi
