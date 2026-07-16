#!/bin/bash
#
# ============================================================================
# Transaction Log Extractor Tool
# ============================================================================
# 
# DESCRIPTION:
#   Extracts complete transaction logs from log4j application logs based on
#   keyword matching. Each transaction is saved as a separate file.
#
# USAGE:
#   ./extract_transactions.sh <log_files> <keywords> <output_prefix> <max_results>
#
# PARAMETERS:
#   log_files     - Space-separated list of log file paths (can include wildcards)
#   keywords      - Space-separated list of keywords to search (all must match)
#   output_prefix - Prefix for output files (e.g., "transaction_")
#   max_results   - Maximum number of transaction files to save (integer)
#
# CONFIGURATION:
#   Modify the following variables in the CONFIGURATION section:
#   - LOG_DIR: Directory containing log files
#   - START_PATTERN: Regex pattern for transaction start (optional)
#   - END_PATTERN: Regex pattern for transaction end (optional)
#
# EXAMPLES:
#   # Extract transactions containing "ERROR" and "timeout" from all logs
#   ./extract_transactions.sh "/var/log/app/*.log" "ERROR timeout" "failed_tx" 10
#
#   # Extract from specific rotated logs
#   ./extract_transactions.sh "app.log app.log.2026-07-15" "DEBU TID123456" "tx" 5
#
# NOTES:
#   - TID (Transaction ID) is extracted from the 4th field of log entries
#   - Uses two-pass scanning: reverse (tac) for TID identification, then forward for extraction
#   - Memory efficient - processes files line by line
#   - Handles multi-line log messages correctly
#   - Preserves original log format in output files
#
# ============================================================================

# ============================================================================
# CONFIGURATION - Modify these as needed
# ============================================================================

# Directory containing log files (used if relative paths are provided)
LOG_DIR="/var/log/application"

# Transaction start pattern (regex) - optional, set to empty if not needed
# Example: START_PATTERN="Transaction started"
START_PATTERN=""

# Transaction end pattern (regex) - optional, set to empty if not needed
# Example: END_PATTERN="Transaction completed"
END_PATTERN=""

# Temporary directory for intermediate files
TEMP_DIR="/tmp/tx_extractor_$$"

# ============================================================================
# FUNCTION: Print usage/help message
# ============================================================================
print_usage() {
    cat << EOF
Usage: $0 <log_files> <keywords> <output_prefix> <max_results>

Arguments:
  log_files     - Space-separated list of log file paths (supports wildcards)
  keywords      - Space-separated list of keywords (all must match in transaction)
  output_prefix - Prefix for output files (e.g., "tx_")
  max_results   - Maximum number of transaction files to keep (integer > 0)

Examples:
  $0 "app.log app.log.2026-07-15" "ERROR timeout" "failed" 10
  $0 "/var/log/app/*.log" "DEBU TID123456" "tx" 5

Configuration (edit script variables):
  LOG_DIR        - Base directory for log files ($LOG_DIR)
  START_PATTERN  - Transaction start pattern ($START_PATTERN)
  END_PATTERN    - Transaction end pattern ($END_PATTERN)

EOF
}

# ============================================================================
# FUNCTION: Check if a string contains all keywords
# ============================================================================
contains_all_keywords() {
    local line="$1"
    shift
    local keywords=("$@")
    
    for keyword in "${keywords[@]}"; do
        if [[ ! "$line" =~ $keyword ]]; then
            return 1
        fi
    done
    return 0
}

# ============================================================================
# FUNCTION: Extract TID from a log line
# ============================================================================
extract_tid() {
    local line="$1"
    # Format: [DEBU] [timestamp] [class.method] [TID123456] [...]
    # Extract the 4th field (TID) using awk
    echo "$line" | awk '{print $4}' | sed 's/\[TID//g' | sed 's/\]//g'
}

# ============================================================================
# FUNCTION: Check if line is a valid log entry (starts with log level)
# ============================================================================
is_log_entry() {
    local line="$1"
    # Log entries start with [LEVEL] where LEVEL is DEBUG, INFO, WARN, ERROR, etc.
    if [[ "$line" =~ ^\[[A-Z]+\]\ +\[[0-9]{4}/[0-9]{2}/[0-9]{2}\ +[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]+\] ]]; then
        return 0
    fi
    return 1
}

# ============================================================================
# FUNCTION: Check if line matches transaction start pattern
# ============================================================================
is_transaction_start() {
    local line="$1"
    if [[ -z "$START_PATTERN" ]]; then
        return 0  # No start pattern defined, always true
    fi
    if [[ "$line" =~ $START_PATTERN ]]; then
        return 0
    fi
    return 1
}

# ============================================================================
# FUNCTION: Check if line matches transaction end pattern
# ============================================================================
is_transaction_end() {
    local line="$1"
    if [[ -z "$END_PATTERN" ]]; then
        return 0  # No end pattern defined, always true
    fi
    if [[ "$line" =~ $END_PATTERN ]]; then
        return 0
    fi
    return 1
}

# ============================================================================
# MAIN SCRIPT
# ============================================================================

# Check minimum arguments
if [[ $# -lt 4 ]]; then
    echo "ERROR: Insufficient arguments"
    print_usage
    exit 1
fi

# Parse arguments
LOG_FILES="$1"
KEYWORDS="$2"
OUTPUT_PREFIX="$3"
MAX_RESULTS="$4"

# Validate max_results
if ! [[ "$MAX_RESULTS" =~ ^[0-9]+$ ]] || [[ "$MAX_RESULTS" -lt 1 ]]; then
    echo "ERROR: max_results must be a positive integer"
    exit 1
fi

# Expand wildcards in log files
IFS=' ' read -ra LOG_FILE_ARRAY <<< "$LOG_FILES"
EXPANDED_FILES=()
for file_pattern in "${LOG_FILE_ARRAY[@]}"; do
    # If file doesn't exist as-is, try with LOG_DIR
    if [[ -f "$file_pattern" ]]; then
        EXPANDED_FILES+=("$file_pattern")
    elif [[ -f "$LOG_DIR/$file_pattern" ]]; then
        EXPANDED_FILES+=("$LOG_DIR/$file_pattern")
    else
        # Try glob expansion
        shopt -s nullglob
        if [[ "$file_pattern" == *"*"* ]] || [[ "$file_pattern" == *"?"* ]] || [[ "$file_pattern" == *"["*"]"* ]]; then
            # Pattern contains wildcards
            if [[ "$file_pattern" != /* ]]; then
                # Relative path, try with LOG_DIR first
                glob_files=($LOG_DIR/$file_pattern)
                if [[ ${#glob_files[@]} -gt 0 ]]; then
                    EXPANDED_FILES+=("${glob_files[@]}")
                else
                    # Try as-is
                    glob_files=($file_pattern)
                    EXPANDED_FILES+=("${glob_files[@]}")
                fi
            else
                glob_files=($file_pattern)
                EXPANDED_FILES+=("${glob_files[@]}")
            fi
        else
            echo "WARNING: File not found: $file_pattern"
        fi
    fi
done

# Check if we have any files to process
if [[ ${#EXPANDED_FILES[@]} -eq 0 ]]; then
    echo "ERROR: No log files found"
    exit 1
fi

echo "Processing files: ${EXPANDED_FILES[*]}"
echo "Keywords: $KEYWORDS"
echo "Output prefix: $OUTPUT_PREFIX"
echo "Max results: $MAX_RESULTS"

# Convert keywords to array
IFS=' ' read -ra KEYWORD_ARRAY <<< "$KEYWORDS"

# Create temp directory
mkdir -p "$TEMP_DIR"

# ============================================================================
# PASS 1: Reverse scan to identify TIDs that contain all keywords
# ============================================================================
echo "PASS 1: Identifying transactions with all keywords (reverse scan)..."

# Use associative array to track TIDs that match
declare -A matching_tids
declare -A temp_tid_content

# Process files in reverse order (newest first)
for ((idx=${#EXPANDED_FILES[@]}-1; idx>=0; idx--)); do
    file="${EXPANDED_FILES[idx]}"
    echo "  Scanning (reverse): $file"
    
    # Use tac to read file in reverse order
    tac "$file" 2>/dev/null | while IFS= read -r line; do
        # Check if this is a valid log entry
        if is_log_entry "$line"; then
            tid=$(extract_tid "$line")
            
            # Skip if no TID found
            if [[ -z "$tid" ]]; then
                continue
            fi
            
            # Check if this line contains all keywords
            if contains_all_keywords "$line" "${KEYWORD_ARRAY[@]}"; then
                # Found a matching line - mark this TID as matching
                echo "$tid" >> "$TEMP_DIR/matching_tids.tmp"
            fi
            
            # Track transaction boundaries (if patterns are defined)
            if [[ -n "$START_PATTERN" ]] && is_transaction_start "$line"; then
                echo "START:$tid" >> "$TEMP_DIR/tx_boundaries.tmp"
            fi
            if [[ -n "$END_PATTERN" ]] && is_transaction_end "$line"; then
                echo "END:$tid" >> "$TEMP_DIR/tx_boundaries.tmp"
            fi
        fi
    done
done

# Get unique TIDs that matched
sort -u "$TEMP_DIR/matching_tids.tmp" 2>/dev/null > "$TEMP_DIR/unique_matching_tids.tmp"

# Count matching TIDs
total_matching=$(wc -l < "$TEMP_DIR/unique_matching_tids.tmp" 2>/dev/null || echo "0")
echo "Found $total_matching unique TIDs with all keywords"

if [[ "$total_matching" -eq 0 ]]; then
    echo "No matching transactions found"
    rm -rf "$TEMP_DIR"
    exit 0
fi

# Get top N TIDs (reverse chronological order - we already processed newest first)
head -n "$MAX_RESULTS" "$TEMP_DIR/unique_matching_tids.tmp" > "$TEMP_DIR/top_tids.tmp"
selected_count=$(wc -l < "$TEMP_DIR/top_tids.tmp")
echo "Selected top $selected_count TIDs to extract"

# ============================================================================
# PASS 2: Forward scan to extract complete transactions
# ============================================================================
echo "PASS 2: Extracting complete transactions (forward scan)..."

# Read selected TIDs into array
mapfile -t SELECTED_TIDS < "$TEMP_DIR/top_tids.tmp"

# Initialize tracking variables for each selected TID
declare -A tid_in_transaction
declare -A tid_transaction_lines
declare -A tid_has_start
declare -A tid_has_end

# Initialize arrays for each selected TID
for tid in "${SELECTED_TIDS[@]}"; do
    tid_in_transaction["$tid"]=0
    tid_has_start["$tid"]=0
    tid_has_end["$tid"]=0
done

# Process files in forward order
for file in "${EXPANDED_FILES[@]}"; do
    echo "  Extracting from: $file"
    
    # Read file line by line (forward)
    while IFS= read -r line; do
        # Check if this is a valid log entry
        if is_log_entry "$line"; then
            tid=$(extract_tid "$line")
            
            # Check if this TID is selected for extraction
            if [[ " ${SELECTED_TIDS[*]} " =~ " ${tid} " ]]; then
                # Check if this is start of transaction
                if [[ -z "$START_PATTERN" ]] || is_transaction_start "$line"; then
                    tid_has_start["$tid"]=1
                    tid_in_transaction["$tid"]=1
                    tid_transaction_lines["$tid"]=""
                fi
                
                # If we're in the middle of a transaction, collect lines
                if [[ ${tid_in_transaction["$tid"]} -eq 1 ]]; then
                    # Append line to transaction
                    tid_transaction_lines["$tid"]="${tid_transaction_lines["$tid"]}$line"$'\n'
                    
                    # Check if this is end of transaction
                    if [[ -z "$END_PATTERN" ]] || is_transaction_end "$line"; then
                        tid_has_end["$tid"]=1
                        tid_in_transaction["$tid"]=0
                    fi
                fi
            fi
        elif [[ ${#SELECTED_TIDS[@]} -gt 0 ]]; then
            # For multi-line messages, continue appending to current transaction
            for tid in "${SELECTED_TIDS[@]}"; do
                if [[ ${tid_in_transaction["$tid"]} -eq 1 ]]; then
                    tid_transaction_lines["$tid"]="${tid_transaction_lines["$tid"]}$line"$'\n'
                fi
            done
        fi
    done < "$file"
done

# ============================================================================
# Write transaction files
# ============================================================================
echo "Writing transaction files..."

file_count=0
for tid in "${SELECTED_TIDS[@]}"; do
    # Check if we have content for this TID
    if [[ -n "${tid_transaction_lines["$tid"]}" ]]; then
        # Check start/end patterns if configured
        if [[ -n "$START_PATTERN" ]] && [[ ${tid_has_start["$tid"]} -eq 0 ]]; then
            echo "  WARNING: TID $tid missing start pattern - still saving"
        fi
        if [[ -n "$END_PATTERN" ]] && [[ ${tid_has_end["$tid"]} -eq 0 ]]; then
            echo "  WARNING: TID $tid missing end pattern - still saving"
        fi
        
        # Write to file
        output_file="${OUTPUT_PREFIX}${tid}.log"
        echo "${tid_transaction_lines["$tid"]}" > "$output_file"
        ((file_count++))
        echo "  Saved: $output_file"
    else
        echo "  WARNING: No content collected for TID $tid"
    fi
done

# ============================================================================
# Cleanup and summary
# ============================================================================
echo "Cleaning up temporary files..."
rm -rf "$TEMP_DIR"

echo "============================================"
echo "Extraction complete!"
echo "  Total matching TIDs found: $total_matching"
echo "  Selected TIDs processed: $selected_count"
echo "  Files saved: $file_count"
echo "============================================"

# List saved files
if [[ $file_count -gt 0 ]]; then
    echo "Saved files:"
    ls -la ${OUTPUT_PREFIX}*.log 2>/dev/null || echo "  (no files found)"
fi

exit 0