/* Author: Jeffrey + ChatGPT */
package att.core;

import att.config.FrameworkConfig;
import att.template.StageTemplate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, fully resolved input to one run; building it performs no output mutation. */
public final class ExecutionPlan {
    private final String runId;
    private final Path outputRoot;
    private final Path finalRunDirectory;
    private final List<Suite> suites;

    public ExecutionPlan(String runId, Path outputRoot, Path finalRunDirectory, List<Suite> suites) {
        this.runId = runId; this.outputRoot = outputRoot; this.finalRunDirectory = finalRunDirectory;
        this.suites = Collections.unmodifiableList(new ArrayList<Suite>(suites));
    }
    public String runId() { return runId; }
    public Path outputRoot() { return outputRoot; }
    public Path finalRunDirectory() { return finalRunDirectory; }
    public List<Suite> suites() { return suites; }
    public int caseCount() { int count = 0; for (Suite suite : suites) count += suite.cases.size(); return count; }

    public static final class Suite {
        private final Path workbook;
        private final FrameworkConfig config;
        private final List<TestCase> cases;
        private final Map<String, StageTemplate> templates;
        public Suite(Path workbook, FrameworkConfig config, List<TestCase> cases, Map<String, StageTemplate> templates) {
            this.workbook = workbook; this.config = config;
            this.cases = Collections.unmodifiableList(new ArrayList<TestCase>(cases));
            this.templates = Collections.unmodifiableMap(new LinkedHashMap<String, StageTemplate>(templates));
        }
        public Path workbook() { return workbook; }
        public FrameworkConfig config() { return config; }
        public List<TestCase> cases() { return cases; }
        public StageTemplate template(String reference) { return templates.get(reference); }
    }
}
