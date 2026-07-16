#!/bin/bash
set -euo pipefail
IFS=$'\n\t'

################################################################################
# Script Name: extract_single_trade_log.sh
# Description: Extract full independent trade logs from multiple input log files
# Core Rules:
# 1. All input keywords must appear within logs of the SAME TID (any lines/any log fields)
# 2. Each matched TID trade block will be saved as an individual file
# 3. Only latest N matched TIDs (time descending) will be exported, others discarded
# 4. Two-pass scan design to reduce memory consumption:
#    Pass 1: Use tac to scan logs from newest to oldest, collect qualified TIDs
#    Pass 2: Forward scan aggregated trade blocks, export only filtered top-N TIDs
# Log Format Definition (log4j output):
# [LEVEL] [yyyy/MM/dd HH:mm:ss.SSS] [Class.method] [TIDxxxxxx] [metric] [ThreadPool] [num] Messages...
# The 4th bracket content [TIDxxx] is unique trade ID for a complete transaction
# Multi-line message body is fully supported for one single log entry
# Input Argument Order & Separator:
# $1        Max number of matched trade files to keep (positive integer N)
# $2        Output file prefix (output file name: {prefix}_{TID}.log)
# $3 ... $M Mandatory matching keywords (ALL keywords must exist for valid TID)
# --FILES   Fixed separator marker, all arguments after this marker are input log file paths
# Usage Example:
# ./extract_single_trade_log.sh 10 trade_out "DEBU" "timeout" "pay_error" --FILES /var/log/app/app.log /var/log/app/app.log.26071609
################################################################################

############################## CONFIGURATION SECTION ###########################
# Root directory storing application log files
TRADE_LOG_ROOT_DIR="/var/log/app"
# Configurable regex pattern to identify trade block start line
TRADE_START_PATTERN="TRADE_BEGIN"
# Configurable regex pattern to identify trade block end line
TRADE_END_PATTERN="TRADE_END"
################################################################################

############################## GLOBAL TEMP FILE DEFINITION ####################
# Store all TIDs that satisfy all keyword matching rules (newest TID first)
TMP_TID_CANDIDATES=$(mktemp /tmp/tid_candidate_list.XXXXXX)
# Unified temp file to store all aggregated complete trade blocks from input logs
TMP_AGG_BLOCKS=$(mktemp /tmp/all_agg_trade_blocks.XXXXXX)
# Final filtered TID list, only keep top N latest matched TIDs
TMP_TARGET_TIDS=$(mktemp /tmp/final_target_tid.XXXXXX)

# Clean up temporary files automatically on script exit / SIGINT / SIGTERM
cleanup() {
    rm -f "${TMP_TID_CANDIDATES}" "${TMP_AGG_BLOCKS}" "${TMP_TARGET_TIDS}"
}
trap cleanup EXIT INT TERM

################################################################################
# Function: aggregate_trade_blocks
# Param $1: Absolute path of single raw input log file
# Purpose: Read raw log line by line, assemble multi-line message body into complete trade blocks
#          Split full trade range by TRADE_START_PATTERN and TRADE_END_PATTERN
# Output Format: Each complete trade block prefixed with |||TID=XXX||| for later parsing
# Fallback logic: Handle incomplete unclosed trade blocks caused by log file truncation
################################################################################
aggregate_trade_blocks() {
    local log_file="$1"
    local trade_buffer=""
    local current_tid=""

    # Read log file, handle last line without trailing newline
    while IFS= read -r raw_line || [[ -n "${raw_line}" ]]; do
        # Detect new trade start marker, flush previous cached trade block before creating new block
        if [[ "${raw_line}" =~ ${TRADE_START_PATTERN} ]]; then
            if [[ -n "${trade_buffer}" && -n "${current_tid}" ]]; then
                printf "|||TID=%s|||%s\n" "${current_tid}" "${trade_buffer}"
            fi
            # Reset buffer for new trade session
            trade_buffer="${raw_line}"$'\n'
            # Extract TID string from 4th bracket field: match [TID<digits>]
            current_tid=$(echo "${raw_line}" | grep -oP '\[\KTID\d+(?=\])' || true)
            continue
        fi

        # Append current line to buffer if we are inside an active trade block
        if [[ -n "${trade_buffer}" ]]; then
            trade_buffer+="${raw_line}"$'\n'
            # Detect trade end marker, output full aggregated trade block
            if [[ "${raw_line}" =~ ${TRADE_END_PATTERN} ]]; then
                if [[ -n "${current_tid}" ]]; then
                    printf "|||TID=%s|||%s\n" "${current_tid}" "${trade_buffer}"
                fi
                # Clear buffer after full trade block finished
                trade_buffer=""
                current_tid=""
            fi
        fi
    done < "${log_file}"

    # Output unclosed incomplete trade block to avoid log data loss
    if [[ -n "${trade_buffer}" && -n "${current_tid}" ]]; then
        printf "|||TID=%s|||%s\n" "${current_tid}" "${trade_buffer}"
    fi
}

################################################################################
# Function: split_input_arguments
# Params: All raw input arguments passed to script
# Purpose: Split input args into max export count, output prefix, keyword list, input log file list
# Use "--FILES" as fixed separator to split keywords and log file paths
# Global variables assigned after execution: MAX_RESULT_NUM, OUTPUT_PREFIX, KEYWORD_ARRAY, LOG_FILE_ARRAY
# Add strict validation for input parameter format and file accessibility
################################################################################
split_input_arguments() {
    local all_args=("$@")
    local marker_index=-1

    # Locate the separator marker --FILES in input arguments
    for idx in "${!all_args[@]}"; do
        if [[ "${all_args[$idx]}" == "--FILES" ]]; then
            marker_index="${idx}"
            break
        fi
    done

    # Check mandatory separator marker exists
    if [[ "${marker_index}" -eq -1 ]]; then
        echo "ERROR: Missing required separator marker --FILES to split keywords and log files" >&2
        echo "Standard Usage Format:" >&2
        echo "$0 MAX_EXPORT_COUNT OUTPUT_PREFIX KEYWORD1 [KEYWORD2 ...] --FILES log_file1 log_file2 ..." >&2
        exit 1
    fi

    # Validate minimal argument quantity
    if [[ "${marker_index}" -lt 3 ]]; then
        echo "ERROR: Insufficient input parameters provided" >&2
        echo "Minimum required parameters: MAX_NUM PREFIX SINGLE_KEYWORD --FILES logfile" >&2
        exit 1
    fi

    # Split argument groups
    MAX_RESULT_NUM="${all_args[0]}"
    OUTPUT_PREFIX="${all_args[1]}"
    KEYWORD_ARRAY=("${all_args[@]:2:$((marker_index - 2))}")
    LOG_FILE_ARRAY=("${all_args[@]:$((marker_index + 1))}")

    # Validate max export number is positive integer
    if ! [[ "${MAX_RESULT_NUM}" =~ ^[1-9][0-9]*$ ]]; then
        echo "ERROR: First parameter MAX_EXPORT_COUNT must be a positive integer" >&2
        exit 1
    fi

    # Validate at least one search keyword is provided
    if [[ "${#KEYWORD_ARRAY[@]}" -eq 0 ]]; then
        echo "ERROR: At least one matching keyword must be input" >&2
        exit 1
    fi

    # Validate all input log files exist and readable
    for log_path in "${LOG_FILE_ARRAY[@]}"; do
        if [[ ! -f "${log_path}" || ! -r "${log_path}" ]]; then
            echo "ERROR: Input log file invalid or unreadable: ${log_path}" >&2
            exit 1
        fi
    done
}

################################################################################
# Function: first_pass_reverse_scan
# Param: Array of matching keywords
# Purpose: First memory optimized reverse scan (tac read blocks newest first)
# Matching rule: All keywords must appear in the same full trade block of one TID
# Write all qualified unique TIDs to TMP_TID_CANDIDATES in newest-first order
# Skip duplicated TIDs to avoid redundant keyword matching checks
################################################################################
first_pass_reverse_scan() {
    local keywords=("$@")
    # Clear aggregated block temp file before processing
    > "${TMP_AGG_BLOCKS}"

    # Step 1: Aggregate all input log files into unified trade block temp file
    for log in "${LOG_FILE_ARRAY[@]}"; do
        echo "INFO: Aggregating complete trade blocks from log file: ${log}"
        aggregate_trade_blocks "${log}" >> "${TMP_AGG_BLOCKS}"
    done

    # Step 2: Reverse scan aggregated blocks (newest trade first via tac)
    > "${TMP_TID_CANDIDATES}"
    tac "${TMP_AGG_BLOCKS}" | while IFS= read -r trade_block || [[ -n "${trade_block}" ]]; do
        # Extract TID value from custom block prefix marker
        local tid=$(echo "${trade_block}" | cut -d'=' -f2 | cut -d'|' -f1)
        [[ -z "${tid}" ]] && continue

        # Skip TID already recorded to reduce duplicate matching overhead
        if grep -q "^${tid}$" "${TMP_TID_CANDIDATES}"; then
            continue
        fi

        # Verify every keyword exists inside current full trade block
        local all_keyword_match=1
        for kw in "${keywords[@]}"; do
            if ! echo "${trade_block}" | grep -q -- "${kw}"; then
                all_keyword_match=0
                break
            fi
        done

        # Save TID only when all keywords matched successfully
        if [[ "${all_keyword_match}" -eq 1 ]]; then
            echo "${tid}" >> "${TMP_TID_CANDIDATES}"
        fi
    done

    local raw_match_count=$(wc -l < "${TMP_TID_CANDIDATES}")
    echo "INFO: Total unique TIDs matching all keywords (raw candidate list): ${raw_match_count}"
}

################################################################################
# Function: filter_top_n_latest_tids
# Purpose: Retain only top N latest matched TIDs from candidate list
# The candidate list is already sorted newest TID first from reverse scan
# Write filtered TIDs into TMP_TARGET_TIDS for second pass export
# Exit script early if zero qualified TID found
################################################################################
filter_top_n_latest_tids() {
    # Extract first N lines (newest N TIDs)
    head -n "${MAX_RESULT_NUM}" "${TMP_TID_CANDIDATES}" > "${TMP_TARGET_TIDS}"
    local target_tid_count=$(wc -l < "${TMP_TARGET_TIDS}")
    echo "INFO: Keep top ${MAX_RESULT_NUM} latest matched TIDs, actual selected TID count: ${target_tid_count}"

    # Terminate script normally if no matched TID exists
    if [[ "${target_tid_count}" -eq 0 ]]; then
        echo "INFO: No trade TID satisfies all keyword matching conditions, script exit normally"
        exit 0
    fi
}

################################################################################
# Function: second_pass_forward_export
# Purpose: Second forward scan of aggregated trade blocks in original time order
# For each target TID, export full raw trade content to standalone individual file
# Output file naming rule: ${OUTPUT_PREFIX}_${TID}.log
# Remove custom |||TID=XXX||| prefix before writing to output file
################################################################################
second_pass_forward_export() {
    echo "INFO: Start forward scan to export independent trade log files for target TIDs"
    # Iterate all pre-aggregated trade blocks in original chronological order
    while IFS= read -r trade_block || [[ -n "${trade_block}" ]]; do
        local tid=$(echo "${trade_block}" | cut -d'=' -f2 | cut -d'|' -f1)
        [[ -z "${tid}" ]] && continue

        # Check if current TID exists in filtered target TID list
        if grep -q "^${tid}$" "${TMP_TARGET_TIDS}"; then
            local output_file="${OUTPUT_PREFIX}_${tid}.log"
            # Strip custom TID marker prefix and write raw complete trade log
            echo "${trade_block}" | sed 's/^|||TID=[0-9]*|||//' >> "${output_file}"
            echo "INFO: Successfully exported trade log to file: ${output_file}"
        fi
    done < "${TMP_AGG_BLOCKS}"
}

################################################################################
# Main Execution Entry Point
################################################################################
main() {
    # Parse and split all input arguments into global variables
    split_input_arguments "$@"

    # Print full runtime configuration summary for verification
    echo "==================== SCRIPT RUN CONFIG INFO ===================="
    echo "Max export independent trade file quantity: ${MAX_RESULT_NUM}"
    echo "Output file name prefix: ${OUTPUT_PREFIX}"
    echo "Mandatory full-match keywords: ${KEYWORD_ARRAY[*]}"
    echo "Input log files list:"
    for f in "${LOG_FILE_ARRAY[@]}"; do
        echo " - ${f}"
    done
    echo "Trade block start matching pattern: ${TRADE_START_PATTERN}"
    echo "Trade block end matching pattern: ${TRADE_END_PATTERN}"
    echo "================================================================="

    # Pass 1: Reverse scan to collect all fully matched TIDs
    first_pass_reverse_scan "${KEYWORD_ARRAY[@]}"

    # Filter only latest N matched TIDs
    filter_top_n_latest_tids

    # Pass 2: Forward scan export each TID as separate log file
    second_pass_forward_export

    echo "INFO: All target trade log export tasks finished successfully"
}

# Execute main logic with full input arguments
main "$@"
exit 0