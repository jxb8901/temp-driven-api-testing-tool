#!/usr/bin/env sh
# Inspects non-recursive filename-glob matches and optionally verifies every line count.
set -eu

if [ "$#" -ne 3 ]; then
  echo "usage: fpp_inspect_files.sh <directory> <pattern> <expected-line-count>" >&2
  exit 2
fi

directory=$1
pattern=$2
expected_line_count=$3

yaml_escape() {
  printf '%s' "$1" | sed "s/'/''/g"
}

if [ ! -d "$directory" ] || [ -L "$directory" ]; then
  printf "count: 0\nlineCountMatches: false\nerrorMessage: '%s'\nfiles: []\n" \
    "$(yaml_escape "Directory does not exist or is a symbolic link: $directory")"
  exit 2
fi
if [ -z "$pattern" ]; then
  printf "count: 0\nlineCountMatches: false\nerrorMessage: 'Filename pattern must not be blank'\nfiles: []\n"
  exit 2
fi
case "$expected_line_count" in
  ''|*[!0-9]*)
    if [ -n "$expected_line_count" ]; then
      printf "count: 0\nlineCountMatches: false\nerrorMessage: 'Expected line count must be a non-negative integer'\nfiles: []\n"
      exit 2
    fi
    ;;
esac

list_file="${TMPDIR:-/tmp}/att-fpp-inspect-$$.list"
trap 'rm -f "$list_file"' EXIT HUP INT TERM
find "$directory" -maxdepth 1 -type f -name "$pattern" -print | LC_ALL=C sort > "$list_file"
count=$(wc -l < "$list_file" | tr -d '[:space:]')
line_count_matches=true
while IFS= read -r file; do
  lines=$(wc -l < "$file" | tr -d '[:space:]')
  if [ -n "$expected_line_count" ] && [ "$lines" -ne "$expected_line_count" ]; then
    line_count_matches=false
  fi
done < "$list_file"

printf "count: %s\n" "$count"
printf "lineCountMatches: %s\n" "$line_count_matches"
printf "errorMessage: ''\n"
if [ "$count" -eq 0 ]; then
  printf "files: []\n"
else
  printf "files:\n"
  while IFS= read -r file; do
    lines=$(wc -l < "$file" | tr -d '[:space:]')
    printf "  - path: '%s'\n" "$(yaml_escape "$file")"
    printf "    lineCount: %s\n" "$lines"
  done < "$list_file"
fi
