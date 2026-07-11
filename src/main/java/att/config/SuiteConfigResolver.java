/* Author: Jeffrey + ChatGPT */
package att.config;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves the mandatory V2 sidecar for one Excel workbook. */
public final class SuiteConfigResolver {
    private final Path projectRoot;
    private final FrameworkConfig global;

    public SuiteConfigResolver(Path projectRoot, FrameworkConfig global) {
        this.projectRoot = projectRoot;
        this.global = global;
    }

    public FrameworkConfig resolve(Path suitePath) throws Exception {
        Path sidecar = sidecarPath(suitePath);
        if (!Files.exists(sidecar)) throw new IllegalArgumentException("Missing mandatory V2 sidecar: " + sidecar);
        Map<String, Object> map = load(sidecar);
        if (map.containsKey("testcase")) throw new IllegalArgumentException("V2 sidecar must use excel, not testcase: " + sidecar);
        Object excelValue = map.get("excel");
        if (!(excelValue instanceof Map)) throw new IllegalArgumentException("excel is required in sidecar: " + sidecar);
        Map<?, ?> excel = (Map<?, ?>) excelValue;
        String sheet = required(excel, "sheet");
        String caseId = required(excel, "caseId");
        String tags = required(excel, "tags");
        int headerRows = positiveInteger(excel.get("headerRows"), 1, "excel.headerRows");
        List<DataColumnConfig> dataColumns = ColumnSpecParser.dataColumns(text(excel.get("dataColumns"), ""));
        rejectReserved(dataColumns, new String[]{"caseId", "groupId", "rowCaseId", "workbook", "sheet", "rowNumber", "tags", "status", "startedAt", "durationMs", "environment", "STAGES"}, "excel.dataColumns");
        List<StageConfig> stages = stages(map.get("stages"));
        if (stages.isEmpty()) throw new IllegalArgumentException("At least one V2 stage is required: " + sidecar);

        ReportConfig report = mergeReport(map.get("report"));
        int timeoutSeconds = positiveInteger(map.get("timeoutSeconds"), global.timeoutSeconds(), "timeoutSeconds");
        return new FrameworkConfig(global.outputDirectory(), global.reportDirectory(), global.logDirectory(),
                global.environment(), timeoutSeconds, global.templatesRoot(),
                global.tools(), report, global.run(), ColumnSpecParser.sheets(sheet), caseId, tags, dataColumns, stages, headerRows);
    }

    private List<StageConfig> stages(Object value) {
        if (!(value instanceof Iterable)) throw new IllegalArgumentException("stages must be a list");
        List<StageConfig> result = new ArrayList<StageConfig>();
        Set<String> keys = new LinkedHashSet<String>();
        for (Object item : (Iterable<?>) value) {
            if (!(item instanceof Map)) throw new IllegalArgumentException("Each stage must be a map");
            Map<?, ?> stage = (Map<?, ?>) item;
            if (stage.containsKey("name") || stage.containsKey("templateColumn") || stage.containsKey("templatePath")) {
                throw new IllegalArgumentException("V2 stage supports key/template/dataColumns/required/onFailure/runWhen only");
            }
            String key = required(stage, "key");
            validateContextKey(key, "stage key");
            if (!keys.add(key)) throw new IllegalArgumentException("Duplicate stage key: " + key);
            String onFailure = defaultedText(stage.get("onFailure"), "stop");
            String runWhen = defaultedText(stage.get("runWhen"), "normal");
            if (!("stop".equals(onFailure) || "continue".equals(onFailure))) throw new IllegalArgumentException("Invalid onFailure for stage " + key + ": " + onFailure);
            if (!("normal".equals(runWhen) || "onSuccess".equals(runWhen) || "onFailure".equals(runWhen) || "always".equals(runWhen))) throw new IllegalArgumentException("Invalid runWhen for stage " + key + ": " + runWhen);
            List<DataColumnConfig> stageColumns = ColumnSpecParser.dataColumns(text(stage.get("dataColumns"), ""));
            rejectReserved(stageColumns, new String[]{"key", "status", "startedAt", "durationMs", "TEMPLATE"}, "stages." + key + ".dataColumns");
            result.add(new StageConfig(key, required(stage, "template"), stageColumns,
                    booleanValue(stage.get("required"), false, "required for stage " + key), onFailure, runWhen));
        }
        return result;
    }

    private ReportConfig mergeReport(Object value) {
        if (!(value instanceof Map)) return global.report();
        Map<?, ?> map = (Map<?, ?>) value;
        Map<String, String> columns = new LinkedHashMap<String, String>(global.report().columns());
        Object configured = map.get("columns");
        if (configured instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) configured).entrySet()) columns.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        return new ReportConfig(text(map.get("mode"), global.report().mode()), text(map.get("fileNamePattern"), global.report().fileNamePattern()), columns);
    }

    private Path sidecarPath(Path suitePath) {
        String file = suitePath.getFileName().toString();
        String yaml = file.replaceFirst("(?i)\\.xlsx$", ".yaml");
        return suitePath.resolveSibling(yaml);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(Path path) throws Exception {
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = YamlSupport.parser().load(reader);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Sidecar must be a YAML map: " + path);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) loaded).entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
            return result;
        }
    }

    private static String required(Map<?, ?> map, String key) {
        String value = text(map.get(key), "");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private static String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private static String defaultedText(Object value, String fallback) {
        String result = text(value, "").trim();
        return result.isEmpty() ? fallback : result;
    }
    private static int positiveInteger(Object value, int fallback, String owner) {
        int result = value == null ? fallback : Integer.parseInt(String.valueOf(value));
        if (result < 1) throw new IllegalArgumentException(owner + " must be at least 1");
        return result;
    }
    private static boolean booleanValue(Object value, boolean fallback, String owner) {
        if (value == null) return fallback;
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        throw new IllegalArgumentException(owner + " must be true or false");
    }
    private static void rejectReserved(List<DataColumnConfig> columns, String[] reserved, String owner) {
        java.util.Set<String> names = new java.util.HashSet<String>(java.util.Arrays.asList(reserved));
        for (DataColumnConfig column : columns) if (names.contains(column.key())) throw new IllegalArgumentException("Reserved key '" + column.key() + "' cannot be used in " + owner);
    }
    private static void validateContextKey(String value, String owner) {
        if (value.contains(".")) throw new IllegalArgumentException(owner + " must not contain '.': " + value);
    }
}
