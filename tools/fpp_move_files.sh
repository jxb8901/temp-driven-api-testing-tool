#!/usr/bin/env sh
# Moves non-recursive filename-glob matches after validating the complete target set.
set -eu

if [ "$#" -ne 4 ]; then
  echo "usage: fpp_move_files.sh <source-directory> <pattern> <target-directory> <overwrite>" >&2
  exit 2
fi

source_directory=$1
pattern=$2
target_directory=$3
overwrite=$4

yaml_escape() {
  printf '%s' "$1" | sed "s/'/''/g"
}

emit_empty() {
  printf "count: 0\n"
  printf "errorMessage: '%s'\n" "$(yaml_escape "$1")"
  printf "files: []\n"
}

case "$overwrite" in
  true|false) ;;
  *) emit_empty "overwrite must be true or false"; exit 2 ;;
esac

if [ ! -d "$source_directory" ] || [ -L "$source_directory" ]; then
  emit_empty "Source directory does not exist or is a symbolic link: $source_directory"
  exit 2
fi
if [ -z "$pattern" ]; then
  emit_empty "Filename pattern must not be blank"
  exit 2
fi
if [ -L "$target_directory" ]; then
  emit_empty "Target directory must not be a symbolic link: $target_directory"
  exit 2
fi

list_file="${TMPDIR:-/tmp}/att-fpp-move-$$.list"
trap 'rm -f "$list_file"' EXIT HUP INT TERM
find "$source_directory" -maxdepth 1 -type f -name "$pattern" -print | LC_ALL=C sort > "$list_file"
count=$(wc -l < "$list_file" | tr -d '[:space:]')
if [ "$count" -eq 0 ]; then
  emit_empty "No regular files matched $pattern in $source_directory"
  exit 4
fi

mkdir -p "$target_directory"
while IFS= read -r source; do
  target=$target_directory/$(basename "$source")
  if { [ -e "$target" ] || [ -L "$target" ]; } && [ "$overwrite" = false ]; then
    emit_empty "Target file already exists: $target"
    exit 5
  fi
  if [ -L "$target" ]; then
    emit_empty "Target file must not be a symbolic link: $target"
    exit 5
  fi
done < "$list_file"

while IFS= read -r source; do
  target=$target_directory/$(basename "$source")
  if [ "$overwrite" = true ]; then
    rm -f "$target"
  fi
  mv "$source" "$target"
done < "$list_file"

printf "count: %s\n" "$count"
printf "errorMessage: ''\n"
printf "files:\n"
while IFS= read -r source; do
  target=$target_directory/$(basename "$source")
  printf "  - '%s'\n" "$(yaml_escape "$target")"
done < "$list_file"
