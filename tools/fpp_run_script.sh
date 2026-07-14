#!/usr/bin/env sh
# Executes one pre-reviewed script with optional atomic arguments and returns its result as YAML.
set -eu

if [ "$#" -lt 3 ]; then
  echo "usage: fpp_run_script.sh <script> <stdout-path> <stderr-path> [argument ...]" >&2
  exit 2
fi

script=$1
stdout_path=$2
stderr_path=$3
shift 3

if [ "$stdout_path" = "$stderr_path" ]; then
  echo "stdout and stderr paths must be different" >&2
  exit 2
fi

mkdir -p "$(dirname "$stdout_path")" "$(dirname "$stderr_path")"
: > "$stdout_path"
: > "$stderr_path"

if [ ! -f "$script" ]; then
  exit_code=127
  printf 'Script does not exist: %s\n' "$script" > "$stderr_path"
elif [ ! -x "$script" ]; then
  exit_code=126
  printf 'Script is not executable: %s\n' "$script" > "$stderr_path"
else
  set +e
  "$script" "$@" > "$stdout_path" 2> "$stderr_path"
  exit_code=$?
  set -e
fi

error_message=$(sed -n '1p' "$stderr_path")
yaml_escape() {
  printf '%s' "$1" | sed "s/'/''/g"
}

if [ "$exit_code" -eq 0 ]; then
  success=true
else
  success=false
fi

printf "exitCode: %s\n" "$exit_code"
printf "success: %s\n" "$success"
printf "errorMessage: '%s'\n" "$(yaml_escape "$error_message")"
printf "stdoutPath: '%s'\n" "$(yaml_escape "$stdout_path")"
printf "stderrPath: '%s'\n" "$(yaml_escape "$stderr_path")"
