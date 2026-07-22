#!/usr/bin/env sh
# Executes one command with optional atomic arguments and returns its result as YAML.
set -eu

stdout_path=
stderr_path=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --stdout)
      [ "$#" -ge 2 ] || { echo "--stdout requires a path" >&2; exit 2; }
      [ -z "$stdout_path" ] || { echo "--stdout may be specified only once" >&2; exit 2; }
      stdout_path=$2
      shift 2
      ;;
    --stderr)
      [ "$#" -ge 2 ] || { echo "--stderr requires a path" >&2; exit 2; }
      [ -z "$stderr_path" ] || { echo "--stderr may be specified only once" >&2; exit 2; }
      stderr_path=$2
      shift 2
      ;;
    --)
      shift
      break
      ;;
    *)
      echo "usage: exehelper.sh [--stdout <path>] [--stderr <path>] -- <command> [argument ...]" >&2
      exit 2
      ;;
  esac
done

[ "$#" -ge 1 ] || {
  echo "usage: exehelper.sh [--stdout <path>] [--stderr <path>] -- <command> [argument ...]" >&2
  exit 2
}
command_name=$1
shift

paths_are_same() {
  [ "$1" = "$2" ] && return 0
  if [ -e "$1" ] && [ -e "$2" ] && [ "$1" -ef "$2" ]; then
    return 0
  fi

  first_dir=$(dirname "$1")
  second_dir=$(dirname "$2")
  mkdir -p "$first_dir" "$second_dir"
  first_path=$(cd "$first_dir" && printf '%s/%s\n' "$(pwd -P)" "$(basename "$1")")
  second_path=$(cd "$second_dir" && printf '%s/%s\n' "$(pwd -P)" "$(basename "$2")")
  [ "$first_path" = "$second_path" ]
}

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/fpp-exec-command.XXXXXX")
command_stdout=$work_dir/stdout
command_stderr=$work_dir/stderr
command_combined=$work_dir/combined
cleanup() { rm -rf "$work_dir"; }
trap cleanup EXIT HUP INT TERM
: > "$command_stdout"
: > "$command_stderr"
: > "$command_combined"

merge_streams=false
if { [ -z "$stdout_path" ] && [ -z "$stderr_path" ]; } || \
   { [ -n "$stdout_path" ] && [ -n "$stderr_path" ] && paths_are_same "$stdout_path" "$stderr_path"; }; then
  merge_streams=true
fi

if [ "$merge_streams" = true ]; then
  diagnostic_file=$command_combined
else
  diagnostic_file=$command_stderr
fi

case "$command_name" in
  */*)
    if [ ! -e "$command_name" ]; then
      exit_code=127
      printf 'Command does not exist: %s\n' "$command_name" > "$diagnostic_file"
    elif [ ! -x "$command_name" ]; then
      exit_code=126
      printf 'Command is not executable: %s\n' "$command_name" > "$diagnostic_file"
    else
      set +e
      if [ "$merge_streams" = true ]; then
        "$command_name" "$@" > "$command_combined" 2>&1
      else
        "$command_name" "$@" > "$command_stdout" 2> "$command_stderr"
      fi
      exit_code=$?
      set -e
    fi
    ;;
  *)
    if ! command -v "$command_name" >/dev/null 2>&1; then
      exit_code=127
      printf 'Command not found: %s\n' "$command_name" > "$diagnostic_file"
    else
      set +e
      if [ "$merge_streams" = true ]; then
        "$command_name" "$@" > "$command_combined" 2>&1
      else
        "$command_name" "$@" > "$command_stdout" 2> "$command_stderr"
      fi
      exit_code=$?
      set -e
    fi
    ;;
esac

if [ "$merge_streams" = true ]; then
  if [ "$exit_code" -eq 0 ]; then
    error_message=
  else
    error_message=$(sed -n '1p' "$command_combined")
  fi

  if [ -n "$stdout_path" ]; then
    mkdir -p "$(dirname "$stdout_path")"
    cat "$command_combined" > "$stdout_path"
  elif [ -s "$command_combined" ]; then
    printf '%s\n' '[command output]' >&2
    cat "$command_combined" >&2
  fi
else
  error_message=$(sed -n '1p' "$command_stderr")

  if [ -n "$stdout_path" ]; then
    mkdir -p "$(dirname "$stdout_path")"
    cat "$command_stdout" > "$stdout_path"
  elif [ -s "$command_stdout" ]; then
    printf '%s\n' '[command stdout]' >&2
    cat "$command_stdout" >&2
  fi

  if [ -n "$stderr_path" ]; then
    mkdir -p "$(dirname "$stderr_path")"
    cat "$command_stderr" > "$stderr_path"
  elif [ -s "$command_stderr" ]; then
    printf '%s\n' '[command stderr]' >&2
    cat "$command_stderr" >&2
  fi
fi

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
