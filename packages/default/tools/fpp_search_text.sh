#!/usr/bin/env sh
# Searches literal text without evaluating a regular expression.
set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: fpp_search_text.sh <file> <keyword>" >&2
  exit 2
fi

file=$1
keyword=$2

yaml_escape() {
  printf '%s' "$1" | sed "s/'/''/g"
}

if [ ! -f "$file" ] || [ -L "$file" ]; then
  printf "found: false\ncount: 0\nfile: '%s'\nkeyword: '%s'\nerrorMessage: '%s'\n" \
    "$(yaml_escape "$file")" "$(yaml_escape "$keyword")" "$(yaml_escape "File does not exist or is a symbolic link: $file")"
  exit 2
fi
if [ -z "$keyword" ]; then
  printf "found: false\ncount: 0\nfile: '%s'\nkeyword: ''\nerrorMessage: 'Keyword must not be blank'\n" "$(yaml_escape "$file")"
  exit 2
fi

set +e
count=$(grep -F -c -e "$keyword" "$file")
grep_exit=$?
set -e
if [ "$grep_exit" -gt 1 ]; then
  printf "found: false\ncount: 0\nfile: '%s'\nkeyword: '%s'\nerrorMessage: 'Unable to search file'\n" \
    "$(yaml_escape "$file")" "$(yaml_escape "$keyword")"
  exit 2
fi
if [ "$count" -gt 0 ]; then found=true; else found=false; fi
printf "found: %s\n" "$found"
printf "count: %s\n" "$count"
printf "file: '%s'\n" "$(yaml_escape "$file")"
printf "keyword: '%s'\n" "$(yaml_escape "$keyword")"
printf "errorMessage: ''\n"
