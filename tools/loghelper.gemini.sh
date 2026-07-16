#!/bin/bash

# ==============================================================================
# USAGE INSTRUCTIONS:
# ==============================================================================
# This script extracts log4j transaction logs based on a set of keywords.
# Every matching transaction ID (TID) is extracted into its own separate file.
#
# Syntax:
#   ./extract_tx_by_kv.sh <max_results> <out_prefix> <file1> [file2...] -- <kw1> [kw2...]
#
# Arguments:
#   max_results  : The maximum number of distinct TIDs to process (sorted by reverse chronological order).
#   out_prefix   : The filename prefix for generated output files (e.g., prefix_TID123.log).
#   file1 file2  : Space-separated list of log files to scan, provided in CHRONOLOGICAL order.
#   --           : A mandatory delimiter separating file arguments from search keywords.
#   kw1 kw2      : One or more keywords. ALL keywords must appear within the lifecycle of a single TID.
#
# Example:
#   ./extract_tx_by_kv.sh 5 tx_report app.log.26071523 app.log -- "DEBU" "ERROR" "OrderRetry"
# ==============================================================================

# ==============================================================================
# 1. CORE CONFIGURATION
# ==============================================================================
LOG_DIR="/path/to/log/directory"         # Directory containing the application logs
TX_START_PATTERN="Transaction Started"   # Regex pattern identifying transaction start
TX_END_PATTERN="Transaction Ended"       # Regex pattern identifying transaction end

# ==============================================================================
# 2. PARAMETER VALIDATION & PARSING
# ==============================================================================
if [ "$#" -lt 4 ]; then
    echo "Error: Insufficient arguments provided."
    echo "Usage: $0 <max_results> <out_prefix> <file1> [file2...] -- <keyword1> [keyword2...]"
    exit 1
fi

MAX_FILES="$1"
OUTPUT_PREFIX="$2"
shift 2

FILES=()
KEYWORDS=()
IS_KW=0

# Separate log file names from search keywords using the '--' delimiter
for arg in "$@"; do
    if [ "$arg" == "--" ]; then
        IS_KW=1
        continue
    fi
    if [ $IS_KW -eq 0 ]; then
        # Resolve path: use absolute path if provided, otherwise append to LOG_DIR
        if [[ "$arg" = /* ]]; then
            FILES+=("$arg")
        else
            FILES+=("${LOG_DIR}/${arg}")
        fi
    else
        KEYWORDS+=("$arg")
    fi
done

# Basic safety check for parsed arguments
if [ ${#FILES[@]} -eq 0 ] || [ ${#KEYWORDS[@]} -eq 0 ]; then
    echo "Error: Missing files or keywords. Ensure you use '--' as a separator."
    exit 1
fi

# Export keywords into the environment matrix so awk can access them natively
export KW_COUNT="${#KEYWORDS[@]}"
i=1
for kw in "${KEYWORDS[@]}"; do
    export "KW_$i"="$kw"
    ((i++))
done

# ==============================================================================
# 3. PASS 1: REVERSE TIME ORDER SCANNING (FIND ELIGIBLE TIDs)
# ==============================================================================
echo "=== Pass 1: Scanning logs in reverse time order with 'tac' ==="

# Create a secure temporary file to pass identified TIDs from Pass 1 to Pass 2
MATCHED_TIDS_FILE=$(mktemp)
trap 'rm -f "$MATCHED_TIDS_FILE"' EXIT

# Reverse the list of files provided so that tac scans the newest log files first
REVERSED_FILES=()
for ((i=${#FILES[@]}-1; i>=0; i--)); do
    if [ -f "${FILES[$i]}" ]; then
        REVERSED_FILES+=("${FILES[$i]}")
    fi
done

if [ ${#REVERSED_FILES[@]} -eq 0 ]; then
    echo "Error: None of the specified log files exist."
    exit 1
fi

# Pipeline execution: tac stream inputs backwards into awk.
# Inside awk, lines are re-assembled to recover multiline log entries without large buffers.
tac "${REVERSED_FILES[@]}" | awk -v tx_start="$TX_START_PATTERN" -v tx_end="$TX_END_PATTERN" -v max_tids="$MAX_FILES" -v tids_log="$MATCHED_TIDS_FILE" '
BEGIN {
    # Initialize keyword dictionary from shell environment variables
    kw_count = ENVIRON["KW_COUNT"]
    for (i = 1; i <= kw_count; i++) {
        keywords[i] = ENVIRON["KW_" i]
    }
    found_count = 0
    buffer = ""
}
{
    # Check if the line matches log4j header signature: e.g., [DEBU] [2026/07/15 ...
    if ($0 ~ /^\[[A-Z]{4}\] \[[0-9]{4}\/[0-9]{2}\/[0-9]{2}/) {
        
        # Encountered a header! Since we read backwards, prepend current line to accumulated multiline text
        full_record = $0 (buffer != "" ? "\n" : "") buffer
        buffer = "" # Reset buffer for the next record
        
        # Extract TID from the 4th set of brackets: [LEVEL][TIMESTAMP][CLASS][TID]
        match(full_record, /^\[[^\]]+\] \[[^\]]+\] \[[^\]]+\] \[[^\]]+\]/)
        if (RSTART > 0) {
            header_part = substr(full_record, RSTART, RLENGTH)
            split(header_part, tags, "][")
            gsub(/[\[\]]/, "", tags[4])
            tid = tags[4]
        } else {
            tid = ""
        }
        
        # Process the record if TID is valid and quota limit has not been breached
        if (tid != "" && found_count < max_tids) {
            
            # Map keyword presence across the entire transaction lifecycle
            for (k = 1; k <= kw_count; k++) {
                if (tid_kw_matched[tid, k] == 0 && index(full_record, keywords[k]) > 0) {
                    tid_kw_matched[tid, k] = 1
                }
            }
            
            # Record structural lifecycles (reverse scanning means tx_end is reached before tx_start)
            if (full_record ~ tx_end && !tid_has_end[tid]) {
                tid_has_end[tid] = 1
            }
            if (full_record ~ tx_start) {
                tid_has_start[tid] = 1
            }
            
            # Evaluate if all specified keywords have hit this particular TID
            if (tid_validated[tid] == 0) {
                all_match = 1
                for (k = 1; k <= kw_count; k++) {
                    if (tid_kw_matched[tid, k] == 0) {
                        all_match = 0
                        break
                    }
                }
                # If fully matched, lock the TID and write out to the intermediary log
                if (all_match == 1) {
                    tid_validated[tid] = 1
                    found_count++
                    print tid >> tids_log
                    
                    # Performance optimization: Exit early once max target count is achieved
                    if (found_count >= max_tids) {
                        exit 
                    }
                }
            }
        }
    } else {
        # This line is part of a multiline trace. Prepend it because tac reads bottom-up.
        buffer = $0 (buffer != "" ? "\n" : "") buffer
    }
}
'

# Exit early if no matching transactions were detected
if [ ! -s "$MATCHED_TIDS_FILE" ]; then
    echo "No TIDs matched all keywords."
    exit 0
fi

echo "Found target TIDs to extract:"
cat "$MATCHED_TIDS_FILE"

# ==============================================================================
# 4. PASS 2: CHRONOLOGICAL LOG EXTRACTION (ISOLATE VALIDATED TIDs)
# ==============================================================================
echo "=== Pass 2: Extracting logs in chronological order ==="

# Load verified TIDs into a bash array securely
if ! command -v mapfile &> /dev/null; then
    VALID_TIDS=()
    while IFS= read -r line; do VALID_TIDS+=("$line"); done < "$MATCHED_TIDS_FILE"
else
    mapfile -t VALID_TIDS < "$MATCHED_TIDS_FILE"
fi

# Package target array into space-separated environment variables for O(1) matching in awk
export VALID_TIDS_STR="${VALID_TIDS[*]}"

# Process raw files forward (chronologically using primary FILES array order)
awk -v tx_start="$TX_START_PATTERN" -v tx_end="$TX_END_PATTERN" -v prefix="$OUTPUT_PREFIX" '
BEGIN {
    # Convert space-delimited text back into an awk lookup table
    split(ENVIRON["VALID_TIDS_STR"], t_arr, " ")
    for (x in t_arr) {
        target_tids[t_arr[x]] = 1
    }
    current_tid = ""
    record_buffer = ""
}

# Helper routine to flash buffered logs out to discrete files
function flush_record() {
    if (current_tid != "" && target_tids[current_tid] == 1) {
        
        # Trigger activation flag if current log matches explicit start boundaries
        if (record_buffer ~ tx_start) {
            tid_active[current_tid] = 1
        }
        
        # Print logs if active or if no rigid starting boundary was established/matched
        if (tid_active[current_tid] == 1 || !tid_has_explicit_start[current_tid]) {
            print record_buffer >> (prefix "_" current_tid ".log")
        }
        
        # Terminate subsequent output streaming for this TID if end boundary is breached
        if (record_buffer ~ tx_end) {
            tid_active[current_tid] = 0
            target_tids[current_tid] = 0 
        }
    }
}

{
    # Forward check for standard log4j header signature
    if ($0 ~ /^\[[A-Z]{4}\] \[[0-9]{4}\/[0-9]{2}\/[0-9]{2}/) {
        
        # Commit previous transaction trace before starting a new record
        if (record_buffer != "") {
            flush_record()
        }
        
        record_buffer = $0
        
        # Extract target transaction ID
        match($0, /^\[[^\]]+\] \[[^\]]+\] \[[^\]]+\] \[[^\]]+\]/)
        if (RSTART > 0) {
            header_part = substr($0, RSTART, RLENGTH)
            split(header_part, tags, "][")
            gsub(/[\[\]]/, "", tags[4])
            current_tid = tags[4]
        } else {
            current_tid = ""
        }
        
        # Flag if this transaction possesses an explicit entry signature boundary
        if (target_tids[current_tid] == 1 && $0 ~ tx_start) {
            tid_has_explicit_start[current_tid] = 1
        }
    } else {
        # Chronological reconstruction: append multiline additions standardly down
        record_buffer = record_buffer "\n" $0
    }
}
END {
    # Handle flushing remaining structural stream element left in memory
    if (record_buffer != "") {
        flush_record()
    }
}
' "${FILES[@]}"

echo "Extraction complete. Individual log files generated with prefix '$OUTPUT_PREFIX'."