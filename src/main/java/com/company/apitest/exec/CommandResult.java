/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.exec;

/**
 * Holds the captured outcome of a shell command invocation.
 */
public class CommandResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean timedOut;

    public CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.timedOut = timedOut;
    }

    public int exitCode() { return exitCode; }
    public String stdout() { return stdout; }
    public String stderr() { return stderr; }
    public boolean timedOut() { return timedOut; }
}
