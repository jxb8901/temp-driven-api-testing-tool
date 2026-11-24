#!/usr/bin/env sh
# Executes a prepared SQLPlus check and always emits parseable XML before propagating its exit code.
set -u

if [ "$#" -ne 3 ]; then
  echo "usage: fpp_query_sqlplus.sh <sql-file> <output-file> <stderr-path>" >&2
  exit 2
fi

sql_file=$1
output_file=$2
stderr_path=$3
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sqlplus_bin=${FPP_SQLPLUS_BIN:-sqlplus}

mkdir -p "$(dirname "$output_file")" "$(dirname "$stderr_path")"
: > "$output_file"
: > "$stderr_path"

exit_code=0
if [ ! -f "$sql_file" ]; then
  printf 'SQL file does not exist: %s\n' "$sql_file" > "$stderr_path"
  exit_code=2
elif [ -z "${FPP_SQLPLUS_CONNECT:-}" ]; then
  printf 'FPP_SQLPLUS_CONNECT is required; prefer an Oracle wallet or external-authentication alias\n' > "$stderr_path"
  exit_code=2
elif ! command -v "$sqlplus_bin" >/dev/null 2>&1; then
  printf 'SQLPlus executable was not found: %s\n' "$sqlplus_bin" > "$stderr_path"
  exit_code=127
else
  "$sqlplus_bin" -s "$FPP_SQLPLUS_CONNECT" "@$sql_file" > "$output_file" 2> "$stderr_path"
  exit_code=$?
fi

"$script_directory/fpp_sqlplus_to_xml.sh" "$output_file"
exit "$exit_code"
