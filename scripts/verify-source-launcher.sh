#!/usr/bin/env sh
# Regression check for a clean source checkout without Maven.
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
# This gate must prove the javac fallback can build from sources. Maven's
# preceding verify may have left classes that would otherwise mask that path.
rm -rf "$ROOT_DIR/att-engine/target/classes" "$ROOT_DIR/att-cli/target/classes"
OUTPUT="$(cd "$ROOT_DIR" && ATT_FORCE_JAVAC=true ./att.sh version)"
case "$OUTPUT" in *"ATT V"*) ;; *) echo "Unexpected version output: $OUTPUT" >&2; exit 1 ;; esac
ENGINE_MARKER="$ROOT_DIR/att-engine/target/classes/att-build.properties"
CLI_MARKER="$ROOT_DIR/att-cli/target/classes/att-build.properties"
[ -s "$ENGINE_MARKER" ] || { echo "Engine build marker is missing." >&2; exit 1; }
[ -f "$CLI_MARKER" ] || { echo "CLI build marker is missing." >&2; exit 1; }
if grep -q '\${project.version}\|\${maven.build.timestamp}\|\${att.gitCommit}' "$ENGINE_MARKER"; then
  echo "Engine build marker contains unexpanded Maven values." >&2
  exit 1
fi
echo "Source launcher javac fallback passed."
