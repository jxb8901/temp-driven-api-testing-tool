#!/bin/bash
# ==============================================================================
# 提取特定交易應用日誌的工具：在給定的多個日誌文件中搜索包括全部 keywords 的交易日誌，
# 每筆交易日誌保存為獨立文件，最多保存給定個數。具體要求如下：
#    1. 配置：交易日誌目錄、交易開始 pattern、交易結束 pattern、其它模式匹配所用的正則表達式
#    2. Usage: $0 --output-prefix <path> --log-file <path> [<path> ...] --keyword <text> [<text> ...] [--max-tid-files <n>] [--min-tid-files <n>] [--recent-log-count <n>] [--ssh]，log_file 是無序的
#    3. 應用日誌由 log4j 生成，日誌格式為：[DEBUG] [2026/07/15 13:25:32.589] [JavaClass.method] [TID123456] ...
#       Messages…\n multiple line \n …，每條日誌的第 4 個欄位 TID 為一筆交易的唯一 ID
#    4. 一筆交易的第一／最後一條日誌可能匹配，也可能不匹配可配置的交易開始／結束 pattern；
#       pattern 只會出現在多行日誌的第一行
#    5. 日誌按時間寫入；Messages 可包含多行，keywords 以單行字面值匹配，無需跨行匹配
#    6. 日誌會自動 rotate，輸入可同時包含當前日誌及帶時間後綴的已 rotate 日誌
#    7. 輸入的全部 keywords 必須出現在同一 TID 的日誌中；可出現在不同日誌條目或非 Messages 欄位
#    8. 為控制內存使用兩遍掃描：第一遍倒序找到最新的符合 TID，第二遍正序提取，並使用開始／結束 pattern 限定範圍
#    9. 結果寫入 <output_prefix>-<TID>-<HOST>.log，每個 TID 一個獨立文件；output_prefix 未指定目錄時使用 /tmp
#   10. 最多返回 --max-tid-files 個結果文件，默認 10；--min-tid-files 默認 1；相同 TID 在不同 HOST 的文件分別計數
#   11. 默認只搜索按文件首個 timestamp 排序後最近 2 個日誌；--recent-log-count 0 表示搜索全部日誌
#   12. 指定 --ssh 且已收集的結果文件少於 --min-tid-files 時，按配置順序繼續搜索遠程服務器，並將新結果複製到本地相同路徑
#   13. 標準輸出只輸出 ATT output: yaml 可直接解析的結果；掃描進度及診斷寫入標準錯誤
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

# Result filename host identifier. Override with LOGHELPER_HOST when required;
# otherwise use the last two characters of the current hostname.
DEFAULT_HOSTNAME=$(hostname 2>/dev/null || true)
readonly HOST="${LOGHELPER_HOST:-${DEFAULT_HOSTNAME: -2}}"
unset DEFAULT_HOSTNAME

# Format: 'host|user|port|identity_file|remote_loghelper_path'
# Leave identity_file empty to use the SSH agent/default identities.
# Servers are searched in the order listed. For example:
# SSH_SERVERS=(
#     'server1.example.com|appuser|22||/opt/att/tools/loghelper.sh'
#     'server2.example.com|appuser|2222|/path/to/id_ed25519|/opt/att/tools/loghelper.sh'
# )
SSH_SERVERS=()

# Optional environment-based configuration. Each non-empty line uses the same
# five-field format as SSH_SERVERS and replaces the list above.
readonly SSH_SERVERS_OVERRIDE="${LOGHELPER_SSH_SERVERS:-}"

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
    echo "Usage: $0 --output-prefix <path> --log-file <path> [<path> ...] --keyword <text> [<text> ...] [--max-tid-files <n>] [--min-tid-files <n>] [--recent-log-count <n>] [--ssh]" >&2
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

emit_collected_result() {
    local output_index

    if [ "${#OUTPUT_FILES[@]}" -eq 0 ]; then
        emit_empty_result true false ''
        return 0
    fi

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

yaml_boolean() {
    local payload=$1
    local field=$2

    printf '%s\n' "$payload" | awk -v key="$field:" '
        $1 == key && ($2 == "true" || $2 == "false") {
            print $2
            exit
        }
    '
}

yaml_file_records() {
    local payload=$1

    printf '%s\n' "$payload" | awk '
        BEGIN {
            quote = sprintf("%c", 39)
        }
        function unquote(value) {
            if (substr(value, 1, 1) != quote || substr(value, length(value), 1) != quote) {
                return ""
            }
            value = substr(value, 2, length(value) - 2)
            gsub(quote quote, quote, value)
            return value
        }
        /^[[:space:]]+- tid:[[:space:]]/ {
            value = $0
            sub(/^[[:space:]]+- tid:[[:space:]]*/, "", value)
            current_tid = unquote(value)
            next
        }
        /^[[:space:]]+path:[[:space:]]/ {
            value = $0
            sub(/^[[:space:]]+path:[[:space:]]*/, "", value)
            path = unquote(value)
            if (current_tid != "" && path != "") {
                print current_tid "\t" path
            }
            current_tid = ""
        }
    '
}

remote_quote() {
    printf "'"
    printf '%s' "$1" | sed "s/'/'\"'\"'/g"
    printf "'"
}

cleanup_copy_temps() {
    local copy_temp
    for copy_temp in "$@"; do
        [ -n "$copy_temp" ] || continue
        rm -f -- "$copy_temp"
    done
}

copy_remote_results() {
    local ssh_destination=$1
    local payload=$2
    local remote_tid remote_file local_directory copy_temp copy_status copy_index parsed_record_count
    local -a remote_tids remote_files copy_temp_files

    COPY_ERROR=''
    parsed_record_count=0
    while IFS=$'\t' read -r remote_tid remote_file; do
        [ -n "$remote_tid" ] || continue
        [ -n "$remote_file" ] || continue
        parsed_record_count=$((parsed_record_count + 1))
        [ "$(( ${#OUTPUT_FILES[@]} + ${#remote_files[@]} ))" -lt "$MAX_FILES" ] || break
        remote_tids+=("$remote_tid")
        remote_files+=("$remote_file")
    done < <(yaml_file_records "$payload")

    if [ "${#remote_files[@]}" -eq 0 ]; then
        if [ "$parsed_record_count" -gt 0 ]; then
            return 0
        fi
        COPY_ERROR='remote loghelper.sh reported a match without valid result file records.'
        return 1
    fi

    for remote_file in "${remote_files[@]}"; do
        case "$remote_file" in
            /*) ;;
            *)
                COPY_ERROR="remote result path must be absolute: $remote_file"
                cleanup_copy_temps "${copy_temp_files[@]}"
                return 1
                ;;
        esac

        local_directory=${remote_file%/*}
        [ -n "$local_directory" ] || local_directory=/
        if [ ! -d "$local_directory" ] || [ ! -w "$local_directory" ]; then
            COPY_ERROR="local result directory does not exist or is not writable: $local_directory"
            cleanup_copy_temps "${copy_temp_files[@]}"
            return 1
        fi
        if [ -L "$remote_file" ]; then
            COPY_ERROR="local result path must not be a symbolic link: $remote_file"
            cleanup_copy_temps "${copy_temp_files[@]}"
            return 1
        fi

        copy_temp=$(mktemp "${remote_file}.loghelper.XXXXXX") || {
            COPY_ERROR="could not create a local temporary file for: $remote_file"
            cleanup_copy_temps "${copy_temp_files[@]}"
            return 1
        }
        copy_temp_files+=("$copy_temp")

        scp "${SCP_OPTIONS[@]}" -- "$ssh_destination:$remote_file" "$copy_temp"
        copy_status=$?
        if [ "$copy_status" -ne 0 ]; then
            COPY_ERROR="could not copy remote result file: $remote_file"
            cleanup_copy_temps "${copy_temp_files[@]}"
            return "$copy_status"
        fi
    done

    copy_index=0
    while [ "$copy_index" -lt "${#remote_files[@]}" ]; do
        mv -f -- "${copy_temp_files[$copy_index]}" "${remote_files[$copy_index]}" || {
            COPY_ERROR="could not install local result file: ${remote_files[$copy_index]}"
            copy_temp_files[$copy_index]=''
            cleanup_copy_temps "${copy_temp_files[@]}"
            return 1
        }
        copy_temp_files[$copy_index]=''
        SELECTED_TIDS+=("${remote_tids[$copy_index]}")
        OUTPUT_FILES+=("${remote_files[$copy_index]}")
        copy_index=$((copy_index + 1))
    done

    return 0
}

append_remote_error() {
    local message=$1
    if [ -n "$REMOTE_ERRORS" ]; then
        REMOTE_ERRORS="$REMOTE_ERRORS; $message"
    else
        REMOTE_ERRORS=$message
    fi
}

search_remote_servers() {
    local server_record SSH_HOST SSH_USER SSH_PORT SSH_IDENTITY_FILE REMOTE_LOGHELPER EXTRA_FIELD
    local SSH_DESTINATION REMOTE_COMMAND REMOTE_OUTPUT REMOTE_STATUS REMOTE_SUCCESS REMOTE_MATCHED
    local REMOTE_ERRORS=''
    local REMOTE_SUCCEEDED=false
    local search_arg

    if [ -n "$SSH_SERVERS_OVERRIDE" ]; then
        SSH_SERVERS=()
        while IFS= read -r server_record; do
            [ -n "$server_record" ] || continue
            SSH_SERVERS+=("$server_record")
        done <<< "$SSH_SERVERS_OVERRIDE"
    elif [ "${#SSH_SERVERS[@]}" -eq 0 ] && [ -n "${LOGHELPER_SSH_HOST:-}" ]; then
        SSH_SERVERS+=("${LOGHELPER_SSH_HOST}|${LOGHELPER_SSH_USER:-}|${LOGHELPER_SSH_PORT:-22}|${LOGHELPER_SSH_IDENTITY_FILE:-}|${LOGHELPER_REMOTE_LOGHELPER:-}")
    fi

    [ "${#SSH_SERVERS[@]}" -gt 0 ] || fail "SSH_SERVERS must be configured at the top of loghelper.sh when --ssh is used."
    command -v ssh >/dev/null 2>&1 || fail "required command 'ssh' was not found."

    for server_record in "${SSH_SERVERS[@]}"; do
        if [ "${#OUTPUT_FILES[@]}" -ge "$MIN_FILES" ] || [ "${#OUTPUT_FILES[@]}" -ge "$MAX_FILES" ]; then
            return 0
        fi

        IFS='|' read -r SSH_HOST SSH_USER SSH_PORT SSH_IDENTITY_FILE REMOTE_LOGHELPER EXTRA_FIELD <<< "$server_record"
        SSH_DESTINATION="$SSH_USER@$SSH_HOST"

        if [ -n "$EXTRA_FIELD" ] || [ -z "$SSH_HOST" ] || [ -z "$SSH_USER" ] || [ -z "$REMOTE_LOGHELPER" ]; then
            append_remote_error "$SSH_DESTINATION has an invalid SSH_SERVERS entry"
            continue
        fi
        case "$SSH_PORT" in
            ''|0|*[!0-9]*)
                append_remote_error "$SSH_DESTINATION has an invalid SSH port"
                continue
                ;;
        esac
        if [ -n "$SSH_IDENTITY_FILE" ] && { [ ! -f "$SSH_IDENTITY_FILE" ] || [ -L "$SSH_IDENTITY_FILE" ]; }; then
            append_remote_error "$SSH_DESTINATION has an invalid identity file: $SSH_IDENTITY_FILE"
            continue
        fi

        SSH_OPTIONS=(
            -o BatchMode=yes
            -o StrictHostKeyChecking=yes
            -p "$SSH_PORT"
        )
        SCP_OPTIONS=(
            -o BatchMode=yes
            -o StrictHostKeyChecking=yes
            -P "$SSH_PORT"
        )
        if [ -n "$SSH_IDENTITY_FILE" ]; then
            SSH_OPTIONS+=(-i "$SSH_IDENTITY_FILE")
            SCP_OPTIONS+=(-i "$SSH_IDENTITY_FILE")
        fi

        echo "Collected ${#OUTPUT_FILES[@]} result file(s), below minimum $MIN_FILES; searching $SSH_DESTINATION" >&2

        REMOTE_COMMAND="$(remote_quote /bin/bash) $(remote_quote "$REMOTE_LOGHELPER")"
        for search_arg in "${REMOTE_SEARCH_ARGS[@]}"; do
            REMOTE_COMMAND="$REMOTE_COMMAND $(remote_quote "$search_arg")"
        done

        REMOTE_OUTPUT=$(ssh "${SSH_OPTIONS[@]}" -- "$SSH_DESTINATION" "$REMOTE_COMMAND")
        REMOTE_STATUS=$?
        REMOTE_SUCCESS=$(yaml_boolean "$REMOTE_OUTPUT" success)
        REMOTE_MATCHED=$(yaml_boolean "$REMOTE_OUTPUT" matched)

        if [ -z "$REMOTE_SUCCESS" ] || [ -z "$REMOTE_MATCHED" ]; then
            append_remote_error "$SSH_DESTINATION returned invalid YAML (ssh status $REMOTE_STATUS)"
            continue
        fi
        if [ "$REMOTE_STATUS" -ne 0 ] || [ "$REMOTE_SUCCESS" != true ]; then
            append_remote_error "$SSH_DESTINATION failed (ssh status $REMOTE_STATUS)"
            continue
        fi
        REMOTE_SUCCEEDED=true
        if [ "$REMOTE_MATCHED" = false ]; then
            continue
        fi
        if [ "$REMOTE_MATCHED" != true ]; then
            append_remote_error "$SSH_DESTINATION returned an invalid matched value: $REMOTE_MATCHED"
            continue
        fi

        command -v scp >/dev/null 2>&1 || fail "required command 'scp' was not found."
        if copy_remote_results "$SSH_DESTINATION" "$REMOTE_OUTPUT"; then
            continue
        fi
        append_remote_error "$SSH_DESTINATION: $COPY_ERROR"
    done

    if [ "${#OUTPUT_FILES[@]}" -ge "$MIN_FILES" ]; then
        return 0
    fi
    if [ -n "$REMOTE_ERRORS" ]; then
        fail "$REMOTE_ERRORS"
    fi
    [ "$REMOTE_SUCCEEDED" = true ] || fail 'all configured SSH servers failed.'
    return 0
}

# ------------------------------------------------------------------------------
# 2. ARGUMENT & ENVIRONMENT PREPARATION
# ------------------------------------------------------------------------------
MAX_FILES=10
MIN_FILES=1
OUTPUT_PREFIX=''
RECENT_LOG_COUNT=2
LOG_FILES=()
KEYWORDS=()
SEEN_MAX_FILES=false
SEEN_MIN_FILES=false
SEEN_OUTPUT_PREFIX=false
SEEN_RECENT_LOG_COUNT=false
SEEN_LOG_FILES=false
SEEN_KEYWORDS=false
SSH_ENABLED=false
SEEN_SSH=false

while [ "$#" -gt 0 ]; do
    option=$1
    case "$option" in
        --max-tid-files)
            [ "$#" -ge 2 ] || {
                usage
                fail "missing value for --max-tid-files."
            }
            [ "$SEEN_MAX_FILES" = false ] || fail "--max-tid-files must be specified only once."
            MAX_FILES=$2
            SEEN_MAX_FILES=true
            shift 2
            ;;
        --min-tid-files)
            [ "$#" -ge 2 ] || {
                usage
                fail "missing value for --min-tid-files."
            }
            [ "$SEEN_MIN_FILES" = false ] || fail "--min-tid-files must be specified only once."
            MIN_FILES=$2
            SEEN_MIN_FILES=true
            shift 2
            ;;
        --output-prefix)
            [ "$#" -ge 2 ] || {
                usage
                fail "missing value for --output-prefix."
            }
            [ "$SEEN_OUTPUT_PREFIX" = false ] || fail "--output-prefix must be specified only once."
            OUTPUT_PREFIX=$2
            SEEN_OUTPUT_PREFIX=true
            shift 2
            ;;
        --recent-log-count)
            [ "$#" -ge 2 ] || {
                usage
                fail "missing value for --recent-log-count."
            }
            [ "$SEEN_RECENT_LOG_COUNT" = false ] || fail "--recent-log-count must be specified only once."
            RECENT_LOG_COUNT=$2
            SEEN_RECENT_LOG_COUNT=true
            shift 2
            ;;
        --ssh)
            [ "$SEEN_SSH" = false ] || fail "--ssh must be specified only once."
            SSH_ENABLED=true
            SEEN_SSH=true
            shift
            ;;
        --log-file)
            [ "$SEEN_LOG_FILES" = false ] || fail "--log-file must be specified only once."
            SEEN_LOG_FILES=true
            shift
            while [ "$#" -gt 0 ]; do
                case "$1" in
                    --max-tid-files|--min-tid-files|--output-prefix|--recent-log-count|--log-file|--keyword|--ssh) break ;;
                esac
                [ -n "$1" ] || fail "log file paths must not be empty."
                LOG_FILES+=("$1")
                shift
            done
            [ "${#LOG_FILES[@]}" -gt 0 ] || {
                usage
                fail "missing value for --log-file."
            }
            ;;
        --keyword)
            [ "$SEEN_KEYWORDS" = false ] || fail "--keyword must be specified only once."
            SEEN_KEYWORDS=true
            shift
            while [ "$#" -gt 0 ]; do
                case "$1" in
                    --max-tid-files|--min-tid-files|--output-prefix|--recent-log-count|--log-file|--keyword|--ssh) break ;;
                esac
                [ -n "$1" ] || fail "keywords must not be empty."
                KEYWORDS+=("$1")
                shift
            done
            [ "${#KEYWORDS[@]}" -gt 0 ] || {
                usage
                fail "missing value for --keyword."
            }
            ;;
        *)
            usage
            fail "unknown argument: $option"
            ;;
    esac
done

[ "$SEEN_OUTPUT_PREFIX" = true ] || fail "missing required argument --output-prefix."
[ "${#LOG_FILES[@]}" -gt 0 ] || fail "at least one --log-file is required."
[ "${#KEYWORDS[@]}" -gt 0 ] || fail "at least one --keyword is required."

# Canonical named arguments forwarded to remote loghelper.sh. --ssh is omitted
# deliberately so a remote helper never starts another SSH fallback.
REMOTE_SEARCH_ARGS=(
    --max-tid-files "$MAX_FILES"
    --min-tid-files "$MIN_FILES"
    --output-prefix "$OUTPUT_PREFIX"
    --recent-log-count "$RECENT_LOG_COUNT"
    --log-file
)
for arg in "${LOG_FILES[@]}"; do
    REMOTE_SEARCH_ARGS+=("$arg")
done
REMOTE_SEARCH_ARGS+=(--keyword)
for arg in "${KEYWORDS[@]}"; do
    REMOTE_SEARCH_ARGS+=("$arg")
done

case "$MAX_FILES" in
    ''|0|*[!0-9]*) fail "--max-tid-files must be a positive integer." ;;
esac
case "$MIN_FILES" in
    ''|0|*[!0-9]*) fail "--min-tid-files must be a positive integer." ;;
esac
[ "$MIN_FILES" -le "$MAX_FILES" ] || fail "--min-tid-files must not exceed --max-tid-files."
case "$HOST" in
    ''|*[![:alnum:]_.-]*) fail "configured HOST must contain only letters, digits, dot, underscore, or hyphen." ;;
esac
case "$RECENT_LOG_COUNT" in
    ''|*[!0-9]*) fail "--recent-log-count must be zero or a positive integer." ;;
esac

[ -n "$OUTPUT_PREFIX" ] || fail "--output-prefix must not be empty."
case "$OUTPUT_PREFIX" in
    */) fail "--output-prefix must include a filename prefix, not only a directory." ;;
esac
case "$OUTPUT_PREFIX" in
    */*) ;;
    *) OUTPUT_PREFIX="$DEFAULT_OUTPUT_DIR/$OUTPUT_PREFIX" ;;
esac

FILES=()
for arg in "${LOG_FILES[@]}"; do
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

[ "${#FILES[@]}" -gt 0 ] || fail "no valid log files were provided."

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

if [ "$RECENT_LOG_COUNT" -gt 0 ] && [ "${#FILES[@]}" -gt "$RECENT_LOG_COUNT" ]; then
    RECENT_FILES=()
    file_index=$((${#FILES[@]} - RECENT_LOG_COUNT))
    while [ "$file_index" -lt "${#FILES[@]}" ]; do
        RECENT_FILES+=("${FILES[$file_index]}")
        file_index=$((file_index + 1))
    done
    FILES=("${RECENT_FILES[@]}")
fi

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
    -v max_files="$MAX_FILES" "$AWK_COMMON"'
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
    return matched_in_resolved_prefix >= max_files
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
    for (rank = 1; rank <= seen_tid_count && selected < max_files; rank++) {
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

SELECTED_TIDS=()
OUTPUT_FILES=()
if [ ! -s "$MATCHED_TIDS_FILE" ]; then
    if [ "$SSH_ENABLED" = true ] && [ "${#OUTPUT_FILES[@]}" -lt "$MIN_FILES" ]; then
        search_remote_servers
    fi
    emit_collected_result
    exit 0
fi

while IFS=$'\t' read -r tid has_start has_end; do
    [ -n "$tid" ] || continue
    SELECTED_TIDS+=("$tid")
    output_file="${OUTPUT_PREFIX}-${tid}-${HOST}.log"
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
export LOGHELPER_OUTPUT_HOST="$HOST"

awk "$AWK_COMMON"'
function emit(tid, line,    path) {
    path = output_prefix "-" tid "-" output_host ".log"
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
    output_host = ENVIRON["LOGHELPER_OUTPUT_HOST"]
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

if [ "$SSH_ENABLED" = true ] && [ "${#OUTPUT_FILES[@]}" -lt "$MIN_FILES" ]; then
    search_remote_servers
fi
emit_collected_result
