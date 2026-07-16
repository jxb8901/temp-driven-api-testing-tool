#!/usr/bin/env bash

set -o pipefail

# ========================
# Help & argument parsing
# ========================
usage() {
    grep "^#" "$0" | sed -e 's/^#//' -e 's/^ //'
    exit 1
}

LOG_DIR=""
START_PAT=""
END_PAT=""
KEYWORDS=()
OUT_PREFIX=""
MAX_TX=0
FILES=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -d) LOG_DIR="$2"; shift 2 ;;
        -s) START_PAT="$2"; shift 2 ;;
        -e) END_PAT="$2"; shift 2 ;;
        -k) IFS=',' read -r -a KEYWORDS <<< "$2"; shift 2 ;;
        -p) OUT_PREFIX="$2"; shift 2 ;;
        -n) MAX_TX="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) FILES+=("$1"); shift ;;
    esac
done

if [[ ${#FILES[@]} -eq 0 || ${#KEYWORDS[@]} -eq 0 || -z "$OUT_PREFIX" || "$MAX_TX" -le 0 ]]; then
    echo "ERROR: Missing required arguments."
    usage
fi

# ========================
# Utility functions
# ========================

# Extract TID from a log line (4th bracket field)
get_tid() {
    # Example: [DEBU] [2026/07/15 13:25:32.589] [JavaClass.method] [TID123456] ...
    echo "$1" | sed -E 's/^\[[^]]+\] \[[^]]+\] \[[^]]+\] \[([^]]+)\].*/\1/'
}

# Check if a log line belongs to a specific TID
is_tid_line() {
    local line="$1"
    local tid="$2"
    [[ "$line" =~ ^\[.*\]\[.*\]\[.*\]\[${tid}\] ]]
}

# ========================
# First pass (reverse scan)
# ========================
# Goal: Find TIDs where ALL keywords appear somewhere in their logs.
echo "INFO: First pass (reverse scan) to locate candidate TIDs..."

declare -A TID_HIT_COUNT
declare -A TID_SEEN_KEYWORD

for kw in "${KEYWORDS[@]}"; do
    TID_SEEN_KEYWORD["$kw"]=""
done

# Use tac to scan logs newest-first
tac "${FILES[@]}" | while read -r line; do
    tid=$(get_tid "$line")
    [[ -z "$tid" ]] && continue

    for kw in "${KEYWORDS[@]}"; do
        if echo "$line" | grep -qF "$kw"; then
            TID_HIT_COUNT["$tid"]=$(( ${TID_HIT_COUNT["$tid"]:-0} + 1 ))
            TID_SEEN_KEYWORD["$kw"]="${TID_SEEN_KEYWORD["$kw"]} $tid"
        fi
    done
done

# Determine TIDs matching ALL keywords
MATCHED_TIDS=()
for tid in "${!TID_HIT_COUNT[@]}"; do
    ok=1
    for kw in "${KEYWORDS[@]}"; do
        if ! echo "${TID_SEEN_KEYWORD[$kw]}" | grep -qw "$tid"; then
            ok=0
            break
        fi
    done
    [[ "$ok" -eq 1 ]] && MATCHED_TIDS+=("$tid")
done

# Limit number of TIDs (newest-first already due to tac)
if [[ ${#MATCHED_TIDS[@]} -gt $MAX_TX ]]; then
    MATCHED_TIDS=("${MATCHED_TIDS[@]:0:$MAX_TX}")
fi

echo "INFO: Matched TIDs (up to $MAX_TX): ${MATCHED_TIDS[*]}"

[[ ${#MATCHED_TIDS[@]} -eq 0 ]] && echo "No matching transactions found." && exit 0

# ========================
# Second pass (forward scan)
# ========================
# Goal: Extract full transaction logs between START_PAT and END_PAT per TID
echo "INFO: Second pass (forward scan) to extract full transaction logs..."

for tid in "${MATCHED_TIDS[@]}"; do
    out_file="${OUT_PREFIX}${tid}.log"
    echo "INFO: Writing transaction $tid -> $out_file"

    inside=0
    > "$out_file"

    for file in "${FILES[@]}"; do
        while IFS= read -r line; do
            if [[ -n "$START_PAT" && "$line" =~ $START_PAT ]] && is_tid_line "$line" "$tid"; then
                inside=1
            fi

            if [[ $inside -eq 1 && $(get_tid "$line") == "$tid" ]]; then
                echo "$line" >> "$out_file"
            fi

            if [[ -n "$END_PAT" && "$line" =~ $END_PAT ]] && is_tid_line "$line" "$tid"; then
                inside=0
            fi
        done < "$file"
    done
done

echo "INFO: Extraction completed."
exit 0