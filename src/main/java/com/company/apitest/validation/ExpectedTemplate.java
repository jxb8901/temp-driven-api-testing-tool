/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory representation of one expected-template YAML file.
 */
public class ExpectedTemplate {
    private final List<XmlRule> xmlRules = new ArrayList<XmlRule>();
    private final List<CommandRule> databaseRules = new ArrayList<CommandRule>();
    private final List<CommandRule> logRules = new ArrayList<CommandRule>();

    public List<XmlRule> xmlRules() { return xmlRules; }
    public List<CommandRule> databaseRules() { return databaseRules; }
    public List<CommandRule> logRules() { return logRules; }

    public static class XmlRule {
        private final String name;
        private final String xpath;
        private final String equals;

        public XmlRule(String name, String xpath, String equals) {
            this.name = name;
            this.xpath = xpath;
            this.equals = equals;
        }

        public String name() { return name; }
        public String xpath() { return xpath; }
        public String equals() { return equals; }
    }

    public static class CommandRule {
        private final String name;
        private final String command;
        private final String expected;

        public CommandRule(String name, String command, String expected) {
            this.name = name;
            this.command = command;
            this.expected = expected;
        }

        public String name() { return name; }
        public String command() { return command; }
        public String expected() { return expected; }
    }
}
