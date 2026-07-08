/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

/**
 * Captures one validation assertion result from XML, database or log validation.
 */
public class ValidationResult {
    private final String source;
    private final String name;
    private final ResultStatus status;
    private final String expected;
    private final String actual;
    private final String message;

    public ValidationResult(String source, String name, ResultStatus status, String expected, String actual, String message) {
        this.source = source;
        this.name = name;
        this.status = status;
        this.expected = expected;
        this.actual = actual;
        this.message = message;
    }

    public String source() { return source; }
    public String name() { return name; }
    public ResultStatus status() { return status; }
    public String expected() { return expected; }
    public String actual() { return actual; }
    public String message() { return message; }
}
