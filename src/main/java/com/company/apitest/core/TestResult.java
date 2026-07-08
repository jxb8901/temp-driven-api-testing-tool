/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Stores the final execution outcome and report fields for one test case.
 */
public class TestResult {
    private final String caseId;
    private final String caseName;
    private final ResultStatus status;
    private final Duration duration;
    private final String expected;
    private final String actual;
    private final Path outputXml;
    private final List<ValidationResult> validations;

    public TestResult(String caseId, String caseName, ResultStatus status, Duration duration, String expected, String actual, Path outputXml, List<ValidationResult> validations) {
        this.caseId = caseId;
        this.caseName = caseName;
        this.status = status;
        this.duration = duration;
        this.expected = expected;
        this.actual = actual;
        this.outputXml = outputXml;
        this.validations = validations;
    }

    public String caseId() { return caseId; }
    public String caseName() { return caseName; }
    public ResultStatus status() { return status; }
    public Duration duration() { return duration; }
    public String expected() { return expected; }
    public String actual() { return actual; }
    public Path outputXml() { return outputXml; }
    public List<ValidationResult> validations() { return validations; }
}
