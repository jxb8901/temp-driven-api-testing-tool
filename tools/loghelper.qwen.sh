#!/bin/bash

################################################################################
# Script Name: extract_trade_logs.sh
# Description: Extracts specific trade logs from multiple log files based on 
#              multiple keywords. Uses a two-pass scanning approach to optimize
#              memory usage.
#
# Usage: ./extract_trade_logs.sh <result_prefix> <max_results> <keywords...> -- <log_files...>
#
# Arguments:
#   result_prefix  : Prefix for the output log files (e.g., "matched_trade")
#   max_results    : Maximum number of trade log files to generate (Top N by time desc)
#   keywords       : One or more keywords that must ALL appear in the trade logs
#   --             : Separator between keywords and log files
#   log_files      : One or more log file paths to search
#
# Example:
#   ./extract_trade_logs.sh "trade_log" 3 "DEBU" "ERROR" "TID999" -- /var/log/app.log /var/log/app.log.2026-07-15
#
# Configuration:
#   Modify the variables in the 'Configurable Parameters' section below.
################################################################################

# ======================= Configurable Parameters =======================
# Directory where extracted trade logs will be saved
OUTPUT_DIR="./extracted_logs"

# Regex pattern to identify the START of a trade (matches the entire log line)
# If empty or not matched, the first log line with a new TID is considered the start.
START_PATTERN="^\[.*\] \[.*\] \[.*\] \[.*\] \[.*\] \[.*\] \[.*\] .*START.*"

# Regex pattern to identify the END of a trade (matches the entire log line)
# If empty or not matched, the last log line with the same TID is considered the end.
END_PATTERN="^\[.*\] \[.*\] \[.*\] \[.*\] \[.*\] \[.*\] \[.*\] .*END.*"
# =======================================================================

# ======================= Argument Parsing =======================
if [ $# -lt 4 ]; then
    echo "Error: Insufficient arguments."
    echo "Usage: $0 <result_prefix> <max_results> <keywords...> -- <log_files...>"
    exit 1
fi

RESULT_PREFIX="$1"
shift
MAX_RESULTS="$1"
shift

# Parse keywords until we hit the '--' separator
KEYWORDS=()
while [ $# -gt 0 ]; do
    if [ "$1" == "--" ]; then
        shift
        break
    fi
    KEYWORDS+=("$1")
    shift
done

# Remaining arguments are log files
LOG_FILES=("$@")

if [ ${#KEYWORDS[@]} -eq 0 ]; then
    echo "Error: At least one keyword must be provided."
    exit 1
fi

if [ ${#LOG_FILES[@]} -eq 0 ]; then
    echo "Error: At least one log file must be provided."
    exit 1
fi

# Create output directory if it doesn't exist
mkdir -p "$OUTPUT_DIR"

# ======================= Helper Functions =======================

# Function to extract TID from a log line.
# Log format: [DEBU] [2026/07/15 13:25:32.589] [JavaClass.method] [TID123456] ...
# TID is the 4th bracketed field.
extract_tid() {
    local line="$1"
    # Use grep/sed to extract the 4th [...] block
    echo "$line" | sed -n 's/^\(\[[^]]*\] \)\{3\}\[\([^]]*\)\].*/\2/p'
}

# Function to check if a line contains ALL specified keywords
check_keywords() {
    local line="$1"
    for kw in "${KEYWORDS[@]}"; do
        # Use grep with fixed strings (-F) for exact substring match.
        # If case-insensitive match is needed, add -i flag.
        if ! echo "$line" | grep -qF "$kw"; then
            return 1
        fi
    done
    return 0
}

# ======================= Pass 1: Reverse Scan to Find Matching TIDs =======================
# We use tac to read files from bottom to top (newest to oldest).
# We collect TIDs that contain ALL keywords. Since we scan newest first,
# the first N unique TIDs we find are the most recent ones.

echo "Pass 1: Scanning logs in reverse order to find matching TIDs..."

# Use an associative array to store found TIDs (acts as a Set and preserves insertion order)
declare -A FOUND_TIDS
FOUND_TID_LIST=()

# Concatenate all log files and reverse them using tac
# Note: tac handles multi-line content naturally as it reverses line-by-line.
tac "${LOG_FILES[@]}" | while IFS= read -r line || [ -n "$line" ]; do
    # Skip empty lines
    [ -z "$line" ] && continue

    # Extract TID from the current line
    TID=$(extract_tid "$line")
    
    # If no TID found, this line is a continuation of the previous log entry.
    # In log4j multi-line logs, continuation lines belong to the most recently seen TID.
    # However, for Pass 1, we only need to know if a TID *ever* matches all keywords.
    # We can simply check keywords on every line. If a continuation line matches,
    # we need to associate it with the current active TID.
    
    if [ -z "$TID" ]; then
        # Continuation line: associate with the last seen TID in this reverse stream
        # Since we are in a subshell (pipe), we cannot easily update a variable from the outer scope.
        # Alternative approach for Pass 1: 
        # To handle multi-line correctly in a pipe, we should preprocess or use awk.
        # Let's use a more robust approach with awk for Pass 1 to handle state.
        :
    fi
done

# Re-implementing Pass 1 with AWK for robust multi-line TID tracking and keyword matching
# AWK will read from tac output, track the current TID, and check if ALL keywords appear 
# in ANY line belonging to that TID.

PASS1_RESULT=$(tac "${LOG_FILES[@]}" | awk -v keywords="${KEYWORDS[*]}" '
BEGIN {
    # Split keywords into an array
    n_kw = split(keywords, kw_arr, " ")
    # Initialize current TID
    current_tid = ""
    # Initialize keyword match tracker for the current TID
    # We use a string of 0s and 1s or an array. Let us use an array.
    for (i = 1; i <= n_kw; i++) {
        kw_matched[i] = 0
    }
}

{
    line = $0
    
    # Try to extract TID. Format: ... [TID] ...
    # Match the 4th bracketed group
    if (match(line, /^\[[^\]]*\] \[[^\]]*\] \[[^\]]*\] \[([^\]]*)\]/, arr)) {
        new_tid = arr[1]
        
        # If we have a previous TID, check if it matched all keywords before switching
        if (current_tid != "" && current_tid != new_tid) {
            all_matched = 1
            for (i = 1; i <= n_kw; i++) {
                if (kw_matched[i] == 0) {
                    all_matched = 0
                    break
                }
            }
            if (all_matched) {
                print current_tid
            }
            # Reset for new TID
            for (i = 1; i <= n_kw; i++) {
                kw_matched[i] = 0
            }
        }
        current_tid = new_tid
    }
    # If no new TID, line belongs to current_tid (multi-line continuation)
    
    # Check each keyword against the current line
    if (current_tid != "") {
        for (i = 1; i <= n_kw; i++) {
            if (kw_matched[i] == 0 && index(line, kw_arr[i]) > 0) {
                kw_matched[i] = 1
            }
        }
    }
}

END {
    # Check the last TID processed
    if (current_tid != "") {
        all_matched = 1
        for (i = 1; i <= n_kw; i++) {
            if (kw_matched[i] == 0) {
                all_matched = 0
                break
            }
        }
        if (all_matched) {
            print current_tid
        }
    }
}
')

# Parse the AWK output into an array (TIDs are already in reverse chronological order)
while IFS= read -r tid; do
    [ -z "$tid" ] && continue
    # Avoid duplicates (though AWK should output each TID only once)
    if [ -z "${FOUND_TIDS[$tid]}" ]; then
        FOUND_TIDS["$tid"]=1
        FOUND_TID_LIST+=("$tid")
    fi
done <<< "$PASS1_RESULT"

# Limit to MAX_RESULTS
if [ ${#FOUND_TID_LIST[@]} -gt $MAX_RESULTS ]; then
    FOUND_TID_LIST=("${FOUND_TID_LIST[@]:0:$MAX_RESULTS}")
fi

if [ ${#FOUND_TID_LIST[@]} -eq 0 ]; then
    echo "No trades found matching all keywords: ${KEYWORDS[*]}"
    exit 0
fi

echo "Found ${#FOUND_TID_LIST[@]} matching TID(s): ${FOUND_TID_LIST[*]}"

# ======================= Pass 2: Forward Scan to Extract Full Trade Logs =======================
# For each found TID, scan the original files from top to bottom.
# Extract all lines belonging to the TID, bounded by START_PATTERN and END_PATTERN if configured.

echo "Pass 2: Extracting full trade logs for each TID..."

for TID in "${FOUND_TID_LIST[@]}"; do
    OUTPUT_FILE="${OUTPUT_DIR}/${RESULT_PREFIX}_${TID}.log"
    echo "Extracting TID: $TID -> $OUTPUT_FILE"
    
    # Use AWK for precise extraction with start/end pattern boundaries
    cat "${LOG_FILES[@]}" | awk -v target_tid="$TID" \
        -v start_pat="$START_PATTERN" \
        -v end_pat="$END_PATTERN" \
        -v outfile="$OUTPUT_FILE" '
    BEGIN {
        in_trade = 0
        print "" > outfile  # Clear/create the output file
    }
    
    {
        line = $0
        
        # Extract TID from the line
        current_tid = ""
        if (match(line, /^\[[^\]]*\] \[[^\]]*\] \[[^\]]*\] \[([^\]]*)\]/, arr)) {
            current_tid = arr[1]
        }
        
        # If we are currently inside the target trade
        if (in_trade == 1) {
            # Print this line to the output file
            print line >> outfile
            
            # Check if this line matches the END pattern
            # If END_PATTERN is set and matches, or if we hit a new TID, end the trade
            if (end_pat != "" && line ~ end_pat) {
                in_trade = 0
            } else if (current_tid != "" && current_tid != target_tid) {
                # New TID started, so the previous trade ended at the previous line
                in_trade = 0
            }
        } 
        # If we are not inside a trade, check if this line starts the target trade
        else {
            if (current_tid == target_tid) {
                # Check START pattern
                # If START_PATTERN is set, only start if it matches.
                # If START_PATTERN is empty, start immediately on first TID match.
                if (start_pat == "" || line ~ start_pat) {
                    in_trade = 1
                    print line >> outfile
                }
                # If start_pat is set but does not match, we ignore this line 
                # and wait for the next line with the same TID that might match start_pat.
                # Note: If the first line of a TID never matches start_pat, 
                # and start_pat is strict, this trade will not be extracted.
                # To handle cases where start_pat is optional, adjust logic as needed.
            }
        }
    }
    
    END {
        close(outfile)
    }
    '
    
    # Check if the output file has content
    if [ ! -s "$OUTPUT_FILE" ]; then
        echo "Warning: No content extracted for TID $TID. Removing empty file."
        rm -f "$OUTPUT_FILE"
    else
        echo "Successfully extracted $(wc -l < "$OUTPUT_FILE") lines for TID $TID"
    fi
done

echo "Extraction complete. Results saved in: $OUTPUT_DIR"