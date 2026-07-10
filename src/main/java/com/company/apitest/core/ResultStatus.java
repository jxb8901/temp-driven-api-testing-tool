/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

/**
 * Standard result states used by execution, validation and reporting.
 */
public enum ResultStatus {
    PASS,
    FAIL,
    PRECHECK_FAILED,
    POSTCHECK_FAILED,
    ERROR,
    INVALID,
    SKIPPED
}
