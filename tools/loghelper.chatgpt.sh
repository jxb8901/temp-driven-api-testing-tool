#!/usr/bin/env bash
# Extracts the newest transactions whose complete log entries collectively match all keywords.
set -euo pipefail

export LC_ALL=C

usage() {
  cat >&2 <<'USAGE'
Usage:
  extract_transaction_logs.sh \
    --log-file FILE [--log-file FILE ...] \
    --keyword TEXT [--keyword TEXT ...] \
    --result-prefix PATH_PREFIX \
    --max-results N \
    [--log-dir DIR] \
    [--start-pattern ERE] \
    [--end-pattern ERE]

Configuration (command-line options override environment variables):
  TXN_LOG_DIR           transaction log directory (default: .)
  TXN_START_PATTERN     extended regular expression for a transaction start entry
  TXN_END_PATTERN       extended regular expression for a transaction end entry

Repeat --log-file and --keyword once per value. Input log files are ordered by
modification time; newest qualifying TIDs are selected first. Output files are
named PATH_PREFIX_<TID>.log. Patterns are case-sensitive EREs; keywords are
case-sensitive literal strings matched against complete entries, including headers.
USAGE
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 2
}

require_value() {
  local option=$1
  local count=$2
  [[ $count -ge 2 ]] || die "$option requires a value"
}

for command_name in awk tac sort head stat mktemp sed cksum dirname mkdir mv readlink rm; do
  command -v "$command_name" >/dev/null 2>&1 || die "required command not found: $command_name"
done

log_dir=${TXN_LOG_DIR:-.}
start_pattern=${TXN_START_PATTERN:-}
end_pattern=${TXN_END_PATTERN:-}
result_prefix=
max_results=
declare -a log_names=()
declare -a keywords=()

while (($#)); do
  case "$1" in
    --log-file)
      require_value "$1" "$#"
      log_names+=("$2")
      shift 2
      ;;
    --keyword)
      require_value "$1" "$#"
      keywords+=("$2")
      shift 2
      ;;
    --result-prefix)
      require_value "$1" "$#"
      result_prefix=$2
      shift 2
      ;;
    --max-results)
      require_value "$1" "$#"
      max_results=$2
      shift 2
      ;;
    --log-dir)
      require_value "$1" "$#"
      log_dir=$2
      shift 2
      ;;
    --start-pattern)
      require_value "$1" "$#"
      start_pattern=$2
      shift 2
      ;;
    --end-pattern)
      require_value "$1" "$#"
      end_pattern=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

((${#log_names[@]} > 0)) || die "at least one --log-file is required"
((${#keywords[@]} > 0)) || die "at least one --keyword is required"
[[ -n $result_prefix ]] || die "--result-prefix must not be blank"
[[ $max_results =~ ^[1-9][0-9]*$ ]] || die "--max-results must be a positive integer"
[[ -n $start_pattern ]] || die "transaction start pattern is required"
[[ -n $end_pattern ]] || die "transaction end pattern is required"
[[ -d $log_dir ]] || die "transaction log directory does not exist: $log_dir"
[[ $log_dir != *$'\n'* && $log_dir != *$'\t'* ]] || die "transaction log directory must not contain tabs or newlines"
[[ $result_prefix != *$'\n'* && $result_prefix != *$'\t'* ]] || die "result prefix must not contain tabs or newlines"
[[ $start_pattern != *$'\n'* && $end_pattern != *$'\n'* ]] || die "patterns must not contain newlines"

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/extract-transaction-logs.XXXXXX")
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT HUP INT TERM

keywords_file=$work_dir/keywords
start_pattern_file=$work_dir/start-pattern
end_pattern_file=$work_dir/end-pattern
order_file=$work_dir/log-order
candidates_file=$work_dir/candidates
sorted_candidates_file=$work_dir/candidates-sorted
selected_file=$work_dir/selected
selected_map_file=$work_dir/selected-map

: >"$keywords_file"
for keyword in "${keywords[@]}"; do
  [[ -n $keyword ]] || die "keywords must not be blank"
  [[ $keyword != *$'\n'* && $keyword != *$'\t'* ]] || die "keywords must not contain tabs or newlines"
  printf '%s\n' "$keyword" >>"$keywords_file"
done
printf '%s\n' "$start_pattern" >"$start_pattern_file"
printf '%s\n' "$end_pattern" >"$end_pattern_file"

# Force both configured EREs to compile before scanning large files.
awk -v pattern_file="$start_pattern_file" 'BEGIN { getline pattern < pattern_file; close(pattern_file); ignored = ("" ~ pattern) }' </dev/null \
  || die "invalid transaction start pattern"
awk -v pattern_file="$end_pattern_file" 'BEGIN { getline pattern < pattern_file; close(pattern_file); ignored = ("" ~ pattern) }' </dev/null \
  || die "invalid transaction end pattern"

declare -a canonical_logs=()
: >"$order_file"
sequence=0
for log_name in "${log_names[@]}"; do
  [[ -n $log_name ]] || die "log file names must not be blank"
  [[ $log_name != */* && $log_name != . && $log_name != .. ]] || die "--log-file must be a file name below the configured log directory: $log_name"
  [[ $log_name != *$'\n'* && $log_name != *$'\t'* ]] || die "log file names must not contain tabs or newlines"
  log_path=$log_dir/$log_name
  [[ -f $log_path && ! -L $log_path ]] || die "log file does not exist, is not regular, or is a symbolic link: $log_path"
  canonical_path=$(readlink -f -- "$log_path")
  [[ $canonical_path != *$'\n'* && $canonical_path != *$'\t'* ]] || die "resolved log path must not contain tabs or newlines: $log_path"
  if ((${#canonical_logs[@]} > 0)); then
    for existing_path in "${canonical_logs[@]}"; do
      [[ $existing_path != "$canonical_path" ]] || die "duplicate log file: $log_name"
    done
  fi
  canonical_logs+=("$canonical_path")
  mtime=$(stat -c '%Y' -- "$canonical_path")
  printf '%s\t%09d\t%s\n' "$mtime" "$sequence" "$canonical_path" >>"$order_file"
  ((sequence += 1))
done

sort -t $'\t' -k1,1nr -k2,2n "$order_file" >"$work_dir/log-order-sorted"
declare -a newest_first_files=()
while IFS=$'\t' read -r _mtime _sequence log_path; do
  newest_first_files+=("$log_path")
done <"$work_dir/log-order-sorted"

# Pass 1: tac presents entries newest-first. Only per-TID ranks and keyword-hit
# flags are retained; complete log bodies are never accumulated per transaction.
tac -- "${newest_first_files[@]}" | awk -v keyword_file="$keywords_file" '
  function header_tid(line, rest, close_at, field, i, tid) {
    rest = line
    tid = ""
    for (i = 1; i <= 7; i++) {
      if (substr(rest, 1, 1) != "[") return ""
      close_at = index(rest, "]")
      if (close_at < 2) return ""
      field = substr(rest, 2, close_at - 2)
      if (i == 4) tid = field
      rest = substr(rest, close_at + 1)
      sub(/^[[:space:]]+/, "", rest)
    }
    sub(/^[[:space:]]+/, "", tid)
    sub(/[[:space:]]+$/, "", tid)
    return tid
  }

  function inspect_entry(tid, entry, i) {
    if (tid == "" || index(tid, "\t") != 0) return
    entry_rank++
    if (!(tid in newest_rank)) newest_rank[tid] = entry_rank
    for (i = 1; i <= keyword_count; i++) {
      if (!hit[tid, i] && index(entry, keyword[i]) != 0) {
        hit[tid, i] = 1
        hit_count[tid]++
      }
    }
  }

  BEGIN {
    while ((getline line < keyword_file) > 0) keyword[++keyword_count] = line
    close(keyword_file)
  }

  {
    tid = header_tid($0)
    if (tid != "") {
      entry = $0
      if (reverse_body != "") entry = entry "\n" reverse_body
      inspect_entry(tid, entry)
      reverse_body = ""
    } else {
      if (reverse_body == "") reverse_body = $0
      else reverse_body = $0 "\n" reverse_body
    }
  }

  END {
    for (tid in newest_rank) {
      if (hit_count[tid] == keyword_count) print newest_rank[tid] "\t" tid
    }
  }
' >"$candidates_file"

if [[ ! -s $candidates_file ]]; then
  printf 'No transactions matched all keywords.\n' >&2
  exit 0
fi

sort -t $'\t' -k1,1n -k2,2 "$candidates_file" >"$sorted_candidates_file"
head -n "$max_results" "$sorted_candidates_file" >"$selected_file"

output_directory=$(dirname -- "$result_prefix")
mkdir -p -- "$output_directory"
declare -a selected_tids=()
declare -a temporary_outputs=()
declare -a final_outputs=()
: >"$selected_map_file"

while IFS=$'\t' read -r _rank tid; do
  safe_tid=$(printf '%s' "$tid" | sed 's/[^A-Za-z0-9._-]/_/g')
  [[ -n $safe_tid ]] || safe_tid=TID
  if [[ $safe_tid == "$tid" ]]; then
    output_file=${result_prefix}_${safe_tid}.log
  else
    tid_checksum=$(printf '%s' "$tid" | cksum | awk '{print $1}')
    output_file=${result_prefix}_${safe_tid}_${tid_checksum}.log
  fi
  if ((${#final_outputs[@]} > 0)); then
    for existing_output in "${final_outputs[@]}"; do
      [[ $existing_output != "$output_file" ]] || die "multiple TIDs resolve to the same output file: $output_file"
    done
  fi
  temporary_output=$work_dir/output-${#selected_tids[@]}.log
  : >"$temporary_output"
  selected_tids+=("$tid")
  temporary_outputs+=("$temporary_output")
  final_outputs+=("$output_file")
  printf '%s\t%s\n' "$tid" "$temporary_output" >>"$selected_map_file"
done <"$selected_file"

declare -a oldest_first_files=()
for ((i = ${#newest_first_files[@]} - 1; i >= 0; i--)); do
  oldest_first_files+=("${newest_first_files[$i]}")
done

# Pass 2: scan forward and write only entries belonging to selected TIDs. The
# first observed TID entry is the fallback start because boundary entries may
# not carry configured markers; a matching end entry closes that transaction.
awk -v selected_map_file="$selected_map_file" \
    -v start_pattern_file="$start_pattern_file" \
    -v end_pattern_file="$end_pattern_file" '
  function header_tid(line, rest, close_at, field, i, tid) {
    rest = line
    tid = ""
    for (i = 1; i <= 7; i++) {
      if (substr(rest, 1, 1) != "[") return ""
      close_at = index(rest, "]")
      if (close_at < 2) return ""
      field = substr(rest, 2, close_at - 2)
      if (i == 4) tid = field
      rest = substr(rest, close_at + 1)
      sub(/^[[:space:]]+/, "", rest)
    }
    sub(/^[[:space:]]+/, "", tid)
    sub(/[[:space:]]+$/, "", tid)
    return tid
  }

  function emit_entry(tid, entry) {
    if (!(tid in output_file) || finished[tid]) return
    observed[tid] = 1
    if (entry ~ start_pattern) start_seen[tid] = 1
    print entry >> output_file[tid]
    close(output_file[tid])
    if (entry ~ end_pattern) {
      finished[tid] = 1
      finished_count++
      if (finished_count == selected_count) exit
    }
  }

  BEGIN {
    while ((getline selected_line < selected_map_file) > 0) {
      separator = index(selected_line, "\t")
      if (separator > 0) {
        tid = substr(selected_line, 1, separator - 1)
        output_file[tid] = substr(selected_line, separator + 1)
        selected[++selected_count] = tid
      }
    }
    close(selected_map_file)
    getline start_pattern < start_pattern_file
    close(start_pattern_file)
    getline end_pattern < end_pattern_file
    close(end_pattern_file)
  }

  {
    next_tid = header_tid($0)
    if (next_tid != "") {
      if (have_entry) emit_entry(entry_tid, entry)
      entry_tid = next_tid
      entry = $0
      have_entry = 1
    } else if (have_entry) {
      entry = entry "\n" $0
    }
  }

  END {
    if (have_entry) emit_entry(entry_tid, entry)
    for (i = 1; i <= selected_count; i++) {
      tid = selected[i]
      if (!observed[tid]) print "warning: selected TID was not found during extraction: " tid > "/dev/stderr"
      else {
        if (!start_seen[tid]) print "warning: start pattern was not observed for TID: " tid > "/dev/stderr"
        if (!finished[tid]) print "warning: end pattern was not observed for TID: " tid > "/dev/stderr"
      }
    }
  }
' "${oldest_first_files[@]}"

for ((i = 0; i < ${#selected_tids[@]}; i++)); do
  [[ -s ${temporary_outputs[$i]} ]] || {
    printf 'warning: no complete entries were extracted for TID: %s\n' "${selected_tids[$i]}" >&2
    continue
  }
  mv -f -- "${temporary_outputs[$i]}" "${final_outputs[$i]}"
  printf '%s\n' "${final_outputs[$i]}"
done
