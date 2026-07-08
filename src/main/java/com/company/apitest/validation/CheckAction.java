/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.validation;

/**
 * One ordered action in a PreCheck or PostCheck template.
 */
public class CheckAction {
    private final String name;
    private final String description;
    private final String call;
    private final String expected;

    public CheckAction(String name, String description, String call, String expected) {
        this.name = name;
        this.description = description;
        this.call = call;
        this.expected = expected;
    }

    public String name() { return name; }
    public String description() { return description; }
    public String call() { return call; }
    public String expected() { return expected; }
}
