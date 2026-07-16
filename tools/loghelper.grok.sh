#!/bin/bash

# extract_transactions.sh
# 
# Usage: ./extract_transactions.sh <logfile1> [logfile2 ...] <result_prefix> <max_results> <keyword1> [keyword2 ...]
# 
# Description:
#   Searches multiple log files for transactions (grouped by TID) that contain ALL provided keywords.
#   Outputs up to <max_results> matching transactions (newest first) as separate files: <result_prefix>_<TID>.log
#   Uses two-pass scanning for memory efficiency: backward scan (via tac) to identify TIDs, forward to extract full transactions.

set -euo pipefail

# ==================== CONFIGURATION (USER MUST ADJUST) ====================
# Transaction start and end patterns for bounding extraction
TRANSACTION_START_PATTERN="\[DEBU\]"   # Adjust to match first line of a transaction
TRANSACTION_END_PATTERN="\[INFO\]"     # Adjust to match last line. Can be loose.

# Log entry start regex for parsing multi-line log4j entries
LOG_ENTRY_START_REGEX="^\[[A-Z]{3,4}\] \[[0-9\/ :.]+\]"

# =========================================================================

# Argument parsing
if [ $# -lt 4 ]; then
    echo "Usage: $0 <logfile1> [logfile2 ...] <result_prefix> <max_results> <keyword1> [keyword2 ...]"
    echo "Example: $0 app.log app.log.20260715 result_ 5 error timeout"
    exit 1
fi

# Collect log files (any *.log* files at beginning)
LOG_FILES=()
i=1
while [ $i -le $# ] && [[ "${!i}" == *.log* ]]; do
    LOG_FILES+=("${!i}")
    ((i++))
done

# Remaining arguments
shift $((i-1))
RESULT_PREFIX="$1"
shift
MAX_RESULTS="$1"
shift
KEYWORDS=("$@")

if [ ${#LOG_FILES[@]} -eq 0 ] || [ -z "$RESULT_PREFIX" ] || [ -z "$MAX_RESULTS" ] || [ ${#KEYWORDS[@]} -eq 0 ]; then
    echo "Error: Invalid arguments. Provide log files, prefix, max_results, and keywords."
    exit 1
fi

echo "Processing ${#LOG_FILES[@]} log file(s). Max results: $MAX_RESULTS. Keywords: ${KEYWORDS[*]}"

# Temporary files/directories
TID_LIST=$(mktemp)
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TID_LIST" "$TEMP_DIR"' EXIT

# =================================================================
# FIRST PASS: Backward scan (tac) to find matching TIDs, newest first
# =================================================================
echo "First pass (backward): Identifying TIDs containing all keywords..."

> "$TID_LIST"

for log in "${LOG_FILES[@]}"; do
    if [ ! -f "$log" ]; then
        echo "Warning: $log not found, skipping."
        continue
    fi

    tac "$log" | awk '
    BEGIN {
        num_kw = '"${#KEYWORDS[@]}"';
        split("'"$(printf "%s|" "${KEYWORDS[@]}")"'", kws, "|");
        for(i=1; i<=num_kw; i++) kws[i] = tolower(kws[i]);
        current_tid = "";
        tid_content = "";
        tids_found[""] = 0;
    }
    # Detect new log entry start
    '"${LOG_ENTRY_START_REGEX}"' {
        if (current_tid != "" && tid_content != "") {
            process_tid_block();
        }
        # Extract TID from 4th bracket [TIDxxx]
        tid_str = "";
        n = split($0, fields, /\[[^]]*\]/);
        if (n >= 5) {
            tid_str = fields[4];
            gsub(/^\[|\]$/, "", tid_str);
            gsub(/^TID/, "", tid_str);  # Normalize TID
        }
        current_tid = tid_str;
        tid_content = $0 "\n";
    }
    # Multi-line continuation
    {
        if (current_tid != "" && $0 !~ /^'"${LOG_ENTRY_START_REGEX}"'/) {
            tid_content = tid_content $0 "\n";
        }
    }
    END {
        if (current_tid != "") process_tid_block();
        for (t in tids_found) {
            if (tids_found[t] == 1) print t;
        }
    }
    function process_tid_block() {
        lower_cont = tolower(tid_content);
        all_match = 1;
        for (i=1; i<=num_kw; i++) {
            if (index(lower_cont, kws[i]) == 0) {
                all_match = 0;
                break;
            }
        }
        if (all_match && current_tid != "") {
            tids_found[current_tid] = 1;
        }
        tid_content = "";
    }
    ' >> "$TID_LIST"
done

# Unique TIDs, limit to newest MAX_RESULTS
sort -u "$TID_LIST" -o "$TID_LIST"
head -n "$MAX_RESULTS" "$TID_LIST" > "${TID_LIST}.limited"
mv "${TID_LIST}.limited" "$TID_LIST"

NUM_FOUND=$(wc -l < "$TID_LIST")
echo "Found $NUM_FOUND matching TIDs. Will extract up to $MAX_RESULTS."

# =================================================================
# SECOND PASS: Forward extraction of full transactions for selected TIDs
# =================================================================
echo "Second pass (forward): Extracting full transaction logs..."

for log in "${LOG_FILES[@]}"; do
    [ ! -f "$log" ] && continue

    awk -v tidfile="$TID_LIST" '
    BEGIN {
        while ((getline tid < tidfile) > 0) if (tid != "") matching[tid]=1;
        close(tidfile);
        current_tid = "";
        collecting = 0;
        trans_content = "";
    }
    '"${LOG_ENTRY_START_REGEX}"' {
        if (collecting && trans_content != "") {
            print trans_content > "'"$TEMP_DIR"'/" current_tid ".part"
            collecting = 0;
        }
        # Parse current TID
        tid_str = "";
        n = split($0, fields, /\[[^]]*\]/);
        if (n >= 5) {
            tid_str = fields[4];
            gsub(/^\[|\]$/, "", tid_str);
            gsub(/^TID/, "", tid_str);
        }
        current_tid = tid_str;

        if (matching[current_tid]) {
            collecting = 1;
            trans_content = $0 "\n";
        }
    }
    # Continuation lines
    {
        if (collecting && $0 !~ /^'"${LOG_ENTRY_START_REGEX}"'/) {
            trans_content = trans_content $0 "\n";
        }
    }
    END {
        if (collecting && trans_content != "") {
            print trans_content > "'"$TEMP_DIR"'/" current_tid ".part"
        }
    }
    ' "$log"
done

# Finalize output files
COUNT=0
for part in "${TEMP_DIR}"/*.part; do
    [ ! -f "$part" ] && continue
    tid=$(basename "$part" .part)
    final="${RESULT_PREFIX}_${tid}.log"
    mv "$part" "$final"
    echo "✓ Saved TID ${tid} to ${final}"
    ((COUNT++))
done

echo "Extraction completed. ${COUNT} transaction file(s) generated with prefix '${RESULT_PREFIX}'."