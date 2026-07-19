#!/bin/bash
# ==============================================================================
# 提取特定交易應用日誌的工具：在給定的多個日誌文件中搜索包括全部 keywords 的交易日誌，
# 每筆交易日誌保存為獨立文件，最多保存給定個數。具體要求如下：
#    1. 配置：交易日誌目錄、交易開始 pattern、交易結束 pattern、其它模式匹配所用的正則表達式
#    2. Usage: $0 <max_tid_files> <output_prefix> <log_file1> [log_file2 ...] -- <keyword1> [keyword2 ...]，log_file 是無序的
#    3. 應用日誌由 log4j 生成，日誌格式為：[DEBUG] [2026/07/15 13:25:32.589] [JavaClass.method] [TID123456] ...
#       Messages…\n multiple line \n …，每條日誌的第 4 個欄位 TID 為一筆交易的唯一 ID
#    4. 一筆交易的第一／最後一條日誌可能匹配，也可能不匹配可配置的交易開始／結束 pattern；
#       pattern 只會出現在多行日誌的第一行
#    5. 日誌按時間寫入；Messages 可包含多行，keywords 以單行字面值匹配，無需跨行匹配
#    6. 日誌會自動 rotate，輸入可同時包含當前日誌及帶時間後綴的已 rotate 日誌
#    7. 輸入的全部 keywords 必須出現在同一 TID 的日誌中；可出現在不同日誌條目或非 Messages 欄位
#    8. 為控制內存使用兩遍掃描：第一遍倒序找到最新的符合 TID，第二遍正序提取，並使用開始／結束 pattern 限定範圍
#    9. 結果寫入 <output_prefix>.<TID>.log，每個 TID 一個獨立文件；output_prefix 未指定目錄時使用 /tmp
#   10. 有多個符合 TID 時，只處理按各 TID 最新日誌時間倒序排列的前 n 個
#   11. 標準輸出只輸出 ATT output: yaml 可直接解析的結果；掃描進度及診斷寫入標準錯誤
# ==============================================================================
# Author: Jeffrey + ChatGPT
set -o pipefail
export LC_ALL=C
# ------------------------------------------------------------------------------
# 1. CONFIGURATION & PATTERNS (MAINTENANCE ZONE)
# ------------------------------------------------------------------------------
# Bare log filenames are resolved relative to this directory. Paths supplied by
# the caller continue to work as-is. LOG_DIR may also be overridden by the caller.
readonly LOG_DIR="${LOG_DIR:-.}"
readonly DEFAULT_OUTPUT_DIR='/tmp'

# Accept normal Log4j levels such as DEBUG, INFO, WARN, ERROR and TRACE.
readonly LOG_HEADER_REGEXP='^\[[[:upper:]]+\] \[[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}\]'

# The first four bracketed fields end with the transaction ID field.
readonly LOG_TID_EXTRACT_REGEXP='^\[[^]]+\] \[[^]]+\] \[[^]]+\] \[[^]]+\]'
readonly TID_VALUE_REGEXP='^[[:alnum:]_.-]+$'

readonly TX_START_PATTERN='Transaction Started'
readonly TX_END_PATTERN='Transaction Completed'

# Pass configurable regular expressions through ENVIRON so awk does not consume
# their backslashes while processing -v assignments.
export LOGHELPER_RE_HEADER="$LOG_HEADER_REGEXP"
export LOGHELPER_RE_TID_EXTRACT="$LOG_TID_EXTRACT_REGEXP"
export LOGHELPER_RE_TID_VALUE="$TID_VALUE_REGEXP"
export LOGHELPER_TX_START="$TX_START_PATTERN"
export LOGHELPER_TX_END="$TX_END_PATTERN"

# Shared source included in both awk passes. Keeping parsing and common
# environment initialization here prevents the two scanners from drifting.
readonly AWK_COMMON='
function initialize_common() {
    re_header = ENVIRON["LOGHELPER_RE_HEADER"]
    re_tid_ext = ENVIRON["LOGHELPER_RE_TID_EXTRACT"]
    re_tid_value = ENVIRON["LOGHELPER_RE_TID_VALUE"]
    tx_start = ENVIRON["LOGHELPER_TX_START"]
    tx_end = ENVIRON["LOGHELPER_TX_END"]
    tids_file = ENVIRON["LOGHELPER_TIDS_FILE"]
}
function extract_tid(line,    header_part, tags, tag_count, tid) {
    if (match(line, re_tid_ext) == 0) {
        return ""
    }
    header_part = substr(line, RSTART, RLENGTH)
    tag_count = split(header_part, tags, /\] \[/)
    if (tag_count != 4) {
        return ""
    }
    tid = tags[4]
    sub(/\]$/, "", tid)
    if (tid !~ re_tid_value) {
        return ""
    }
    return tid
}
'

usage() {
    echo "Usage: $0 <max_tid_files> <output_prefix> <log_file1> [log_file2 ...] -- <keyword1> [keyword2 ...]" >&2
}

yaml_escape() {
    printf '%s' "$1" | sed "s/'/''/g"
}

emit_empty_result() {
    local success=$1
    local matched=$2
    local error_message=$3

    printf 'success: %s\n' "$success"
    printf 'matched: %s\n' "$matched"
    printf 'count: 0\n'
    printf "errorMessage: '%s'\n" "$(yaml_escape "$error_message")"
    printf 'files: []\n'
}

fail() {
    local error_message=$*

    emit_empty_result false false "$error_message"
    echo "Error: $error_message" >&2
    exit 1
}

canonical_path() {
    local path=$1
    local mode=$2
    local dir base

    case "$path" in
        */*)
            dir=${path%/*}
            base=${path##*/}
            [ -n "$dir" ] || dir=/
            ;;
        *)
            dir=.
            base=$path
            ;;
    esac

    (
        cd -P "$dir" 2>/dev/null || exit 1

        if [ "$mode" = existing-file ]; then
            [ -f "$base" ] || exit 1
        elif [ "$mode" != output ]; then
            exit 2
        fi

        printf '%s/%s\n' "$PWD" "$base"
    )
}

extract_first_log_timestamp() {
    awk '
        BEGIN {
            re_header = ENVIRON["LOGHELPER_RE_HEADER"]
        }
        $0 ~ re_header {
            count = split($0, fields, /\] \[/)
            if (count >= 2) {
                print fields[2]
                exit
            }
        }
    ' "$1"
}

reverse_file() {
    if [ "$REVERSE_COMMAND" = tac ]; then
        tac -- "$1"
    else
        tail -r "$1"
    fi
}

# ------------------------------------------------------------------------------
# 2. ARGUMENT & ENVIRONMENT PREPARATION
# ------------------------------------------------------------------------------
[ "$#" -ge 5 ] || {
    usage
    fail "invalid arguments; expected max_tid_files, output_prefix, one or more log files, '--', and one or more keywords."
}

MAX_FILES=$1
OUTPUT_PREFIX=$2
shift 2

case "$MAX_FILES" in
    ''|0|*[!0-9]*) fail "max_tid_files must be a positive integer." ;;
esac

[ -n "$OUTPUT_PREFIX" ] || fail "output_prefix must not be empty."
case "$OUTPUT_PREFIX" in
    */) fail "output_prefix must include a filename prefix, not only a directory." ;;
esac
case "$OUTPUT_PREFIX" in
    */*) ;;
    *) OUTPUT_PREFIX="$DEFAULT_OUTPUT_DIR/$OUTPUT_PREFIX" ;;
esac

FILES=()
KEYWORDS=()
IN_KEYWORDS=false
SEEN_SEPARATOR=false

for arg in "$@"; do
    if [ "$arg" = "--" ] && [ "$SEEN_SEPARATOR" = false ]; then
        IN_KEYWORDS=true
        SEEN_SEPARATOR=true
        continue
    fi

    if [ "$IN_KEYWORDS" = true ]; then
        [ -n "$arg" ] || fail "keywords must not be empty."
        KEYWORDS+=("$arg")
        continue
    fi

    candidate=$(canonical_path "$arg" existing-file 2>/dev/null || true)
    if [ -z "$candidate" ]; then
        candidate=$(canonical_path "$LOG_DIR/$arg" existing-file 2>/dev/null || true)
    fi
    if [ -z "$candidate" ]; then
        echo "Warning: Log file '$arg' was not found; skipping it." >&2
        continue
    fi

    duplicate=false
    for existing in "${FILES[@]}"; do
        if [ "$existing" = "$candidate" ]; then
            duplicate=true
            break
        fi
    done
    [ "$duplicate" = true ] || FILES+=("$candidate")
done

[ "$SEEN_SEPARATOR" = true ] || fail "missing '--' separator before keywords."
[ "${#FILES[@]}" -gt 0 ] || fail "no valid log files were provided."
[ "${#KEYWORDS[@]}" -gt 0 ] || fail "no keywords were provided after '--'."

OUTPUT_PREFIX=$(canonical_path "$OUTPUT_PREFIX" output) || fail "the output directory does not exist."
OUTPUT_DIR=${OUTPUT_PREFIX%/*}
[ -d "$OUTPUT_DIR" ] || fail "output directory '$OUTPUT_DIR' does not exist."
[ -w "$OUTPUT_DIR" ] || fail "output directory '$OUTPUT_DIR' is not writable."

command -v awk >/dev/null 2>&1 || fail "required command 'awk' was not found."
command -v mktemp >/dev/null 2>&1 || fail "required command 'mktemp' was not found."
if command -v tac >/dev/null 2>&1; then
    readonly REVERSE_COMMAND=tac
elif tail -r /dev/null >/dev/null 2>&1; then
    readonly REVERSE_COMMAND=tail
else
    fail "neither 'tac' nor a 'tail -r' fallback is available."
fi

# Sort unordered files by the first Log4j timestamp in their content. Rotated
# files are assumed to represent non-overlapping chronological ranges.
SORTED_FILES=()
SORTED_KEYS=()
for file in "${FILES[@]}"; do
    time_key=$(extract_first_log_timestamp "$file")
    [ -n "$time_key" ] || fail "log file '$file' contains no recognized Log4j header."

    position=${#SORTED_FILES[@]}
    while [ "$position" -gt 0 ] && [[ "${SORTED_KEYS[$((position - 1))]}" > "$time_key" ]]; do
        SORTED_FILES[$position]=${SORTED_FILES[$((position - 1))]}
        SORTED_KEYS[$position]=${SORTED_KEYS[$((position - 1))]}
        position=$((position - 1))
    done
    SORTED_FILES[$position]=$file
    SORTED_KEYS[$position]=$time_key
done
FILES=("${SORTED_FILES[@]}")

# Environment variables preserve literal keyword contents when passed to awk.
export KW_COUNT=${#KEYWORDS[@]}
keyword_index=1
for keyword in "${KEYWORDS[@]}"; do
    export "KW_${keyword_index}=$keyword"
    keyword_index=$((keyword_index + 1))
done

MATCHED_TIDS_FILE=$(mktemp "${TMPDIR:-/tmp}/loghelper-tids.XXXXXX") || fail "could not create a temporary file."
export LOGHELPER_TIDS_FILE="$MATCHED_TIDS_FILE"
trap 'rm -f "$MATCHED_TIDS_FILE"' EXIT HUP INT TERM

# ------------------------------------------------------------------------------
# 3. PASS 1: REVERSE TIME ORDER SCANNING (FIND ELIGIBLE TIDs)
# ------------------------------------------------------------------------------
echo "Pass 1: scanning logs newest-to-oldest with '$REVERSE_COMMAND'" >&2

{
    file_index=$((${#FILES[@]} - 1))
    while [ "$file_index" -ge 0 ]; do
        reverse_file "${FILES[$file_index]}"
        reverse_status=$?
        [ "$reverse_status" -eq 0 ] || exit "$reverse_status"
        file_index=$((file_index - 1))
    done
} | awk \
    -v max_tids="$MAX_FILES" "$AWK_COMMON"'
function advance_resolved_prefix(    next_rank) {
    next_rank = resolved_prefix + 1
    while (next_rank <= seen_tid_count &&
           (rank_matched[next_rank] == 1 || rank_finalized[next_rank] == 1)) {
        resolved_prefix = next_rank
        if (rank_matched[next_rank] == 1) {
            matched_in_resolved_prefix++
        }
        next_rank++
    }
    return matched_in_resolved_prefix >= max_tids
}
function update_match_state(tid,    rank, keyword_index, matches_all) {
    if (tid_matched[tid] == 1) {
        return 0
    }
    matches_all = 1
    for (keyword_index = 1; keyword_index <= keyword_count; keyword_index++) {
        if (tid_keyword[tid, keyword_index] != 1) {
            matches_all = 0
            break
        }
    }
    if (matches_all == 1) {
        tid_matched[tid] = 1
        rank = tid_rank[tid]
        rank_matched[rank] = 1
        return 1
    }
    return 0
}
BEGIN {
    initialize_common()
    keyword_count = ENVIRON["KW_COUNT"] + 0
    for (keyword_index = 1; keyword_index <= keyword_count; keyword_index++) {
        keywords[keyword_index] = ENVIRON["KW_" keyword_index]
    }
}
{
    # With reversed physical lines, continuation lines arrive before their header.
    for (keyword_index = 1; keyword_index <= keyword_count; keyword_index++) {
        if (index($0, keywords[keyword_index]) > 0) {
            pending_keyword[keyword_index] = 1
        }
    }

    if ($0 ~ re_header) {
        tid = extract_tid($0)
        state_changed = 0
        if (tid != "" && tid_finalized[tid] != 1) {
            if (!(tid in tid_rank)) {
                seen_tid_count++
                tid_rank[tid] = seen_tid_count
                tid_by_rank[seen_tid_count] = tid
            }
            for (keyword_index = 1; keyword_index <= keyword_count; keyword_index++) {
                if (pending_keyword[keyword_index] == 1) {
                    tid_keyword[tid, keyword_index] = 1
                }
            }
            if (update_match_state(tid) == 1) {
                state_changed = 1
            }
            if ($0 ~ tx_start) {
                tid_has_start[tid] = 1
                tid_finalized[tid] = 1
                rank_finalized[tid_rank[tid]] = 1
                state_changed = 1
            }
            if ($0 ~ tx_end) {
                tid_has_end[tid] = 1
            }
        }
        delete pending_keyword

        # Every rank in this prefix is now conclusive: it either already
        # matches or its start boundary proves that no earlier entry can add a
        # missing keyword. Once the prefix contains n matches, older and unseen
        # TIDs cannot change the selected top n.
        if (state_changed == 1 && advance_resolved_prefix() == 1) {
            exit
        }
    }
}
END {
    selected = 0
    for (rank = 1; rank <= seen_tid_count && selected < max_tids; rank++) {
        tid = tid_by_rank[rank]
        if (tid_matched[tid] == 1) {
            print tid "\t" (tid_has_start[tid] == 1 ? 1 : 0) "\t" (tid_has_end[tid] == 1 ? 1 : 0) > tids_file
            selected++
        }
    }
    close(tids_file)
}
'
PASS1_PIPE_STATUS=("${PIPESTATUS[@]}")
PASS1_PRODUCER_STATUS=${PASS1_PIPE_STATUS[0]}
PASS1_AWK_STATUS=${PASS1_PIPE_STATUS[1]}

[ "$PASS1_AWK_STATUS" -eq 0 ] || fail "reverse log scan failed in awk."
if [ "$PASS1_PRODUCER_STATUS" -ne 0 ] && [ "$PASS1_PRODUCER_STATUS" -ne 141 ]; then
    fail "reverse log reader failed with status $PASS1_PRODUCER_STATUS."
fi

if [ ! -s "$MATCHED_TIDS_FILE" ]; then
    emit_empty_result true false ''
    exit 0
fi

SELECTED_TIDS=()
OUTPUT_FILES=()
while IFS=$'\t' read -r tid has_start has_end; do
    [ -n "$tid" ] || continue
    SELECTED_TIDS+=("$tid")
    output_file="${OUTPUT_PREFIX}.${tid}.log"
    output_file=$(canonical_path "$output_file" output) || fail "could not resolve output path for TID '$tid'."

    [ ! -L "$output_file" ] || fail "output file '$output_file' must not be a symbolic link."

    for input_file in "${FILES[@]}"; do
        if [ "$output_file" = "$input_file" ] || { [ -e "$output_file" ] && [ "$output_file" -ef "$input_file" ]; }; then
            fail "output file '$output_file' would overwrite an input log."
        fi
    done
    OUTPUT_FILES+=("$output_file")
done < "$MATCHED_TIDS_FILE"

# Create/truncate only the exact output files selected above. This also makes an
# unwritable output fail before the extraction pass begins.
for output_file in "${OUTPUT_FILES[@]}"; do
    : > "$output_file" || fail "could not write output file '$output_file'."
done

# ------------------------------------------------------------------------------
# 4. PASS 2: FORWARD SCANNING & PER-TID CHRONOLOGICAL EXTRACTION
# ------------------------------------------------------------------------------
echo "Pass 2: extracting selected transactions oldest-to-newest" >&2

export LOGHELPER_OUTPUT_PREFIX="$OUTPUT_PREFIX"

awk "$AWK_COMMON"'
function emit(tid, line,    path) {
    path = output_prefix "." tid ".log"
    print line >> path
    close(path)
}
function finish_previous_entry() {
    if (entry_ends_tid != "") {
        active[entry_ends_tid] = 0
        if (completed[entry_ends_tid] != 1) {
            completed[entry_ends_tid] = 1
            completed_count++
        }
        entry_ends_tid = ""
    }
}
BEGIN {
    initialize_common()
    output_prefix = ENVIRON["LOGHELPER_OUTPUT_PREFIX"]
    while ((getline metadata < tids_file) > 0) {
        field_count = split(metadata, fields, "\t")
        if (field_count >= 3) {
            tid = fields[1]
            selected[tid] = 1
            has_start[tid] = fields[2] + 0
            has_end[tid] = fields[3] + 0
            selected_count++
        }
    }
    close(tids_file)
}
{
    if ($0 ~ re_header) {
        finish_previous_entry()
        if (completed_count == selected_count) {
            exit
        }

        current_output_tid = ""
        tid = extract_tid($0)
        if (selected[tid] == 1 && completed[tid] != 1) {
            if (active[tid] != 1) {
                if (has_start[tid] != 1 || $0 ~ tx_start) {
                    active[tid] = 1
                }
            }
            if (active[tid] == 1) {
                current_output_tid = tid
                if (has_end[tid] == 1 && $0 ~ tx_end) {
                    entry_ends_tid = tid
                }
            }
        }
    }

    if (current_output_tid != "") {
        emit(current_output_tid, $0)
    }
}
' "${FILES[@]}" || fail "forward log extraction failed."

for output_file in "${OUTPUT_FILES[@]}"; do
    [ -s "$output_file" ] || fail "selected transaction produced no output in '$output_file'."
done

printf 'success: true\n'
printf 'matched: true\n'
printf 'count: %s\n' "${#OUTPUT_FILES[@]}"
printf "errorMessage: ''\n"
printf 'files:\n'
output_index=0
while [ "$output_index" -lt "${#OUTPUT_FILES[@]}" ]; do
    printf "  - tid: '%s'\n" "$(yaml_escape "${SELECTED_TIDS[$output_index]}")"
    printf "    path: '%s'\n" "$(yaml_escape "${OUTPUT_FILES[$output_index]}")"
    output_index=$((output_index + 1))
done
