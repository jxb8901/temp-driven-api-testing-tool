/* Author: Jeffrey + ChatGPT */
package att.config;

import att.Version;

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
        Path schema = projectRoot.resolve("schemas/att-sidecar-v2.1.schema.json");
        if (Files.isRegularFile(schema)) att.validation.JsonSchemaVerifier.verify(schema, map);
        SchemaSupport.requireVersion(map, Version.SIDECAR_SCHEMA, "sidecar");
        SchemaSupport.rejectUnknown(map, "sidecar", "schemaVersion", "excel", "stages", "report", "timeoutMs");
        Object excelValue = map.get("excel");
        if (!(excelValue instanceof Map)) throw new IllegalArgumentException("excel is required in sidecar: " + sidecar);
        Map<?, ?> excel = (Map<?, ?>) excelValue;
        SchemaSupport.rejectUnknown(excel, "sidecar.excel", "sheet", "headerRows", "caseId", "tags", "dataColumns");
        String sheet = required(excel, "sheet");
        String caseId = required(excel, "caseId");
        String tags = required(excel, "tags");
        int headerRows = positiveInteger(excel.get("headerRows"), 1, "excel.headerRows");
        List<DataColumnConfig> dataColumns = ColumnSpecParser.dataColumns(optionalString(excel.get("dataColumns"), "excel.dataColumns"));
        rejectReserved(dataColumns, new String[]{"caseId", "groupId", "rowCaseId", "workbook", "sheet", "rowNumber", "tags", "status", "startedAt", "durationMs", "environment", "STAGES"}, "excel.dataColumns");
        List<StageConfig> stages = stages(map.get("stages"));
        if (stages.isEmpty()) throw new IllegalArgumentException("At least one V2 stage is required: " + sidecar);

        ReportConfig report = mergeReport(map.get("report"));
        int timeoutMs = positiveInteger(map.get("timeoutMs"), global.timeoutMs(), "timeoutMs");
        return new FrameworkConfig(global.outputDirectory(), global.reportDirectory(), global.logDirectory(),
                global.environment(), timeoutMs, global.templatesRoot(),
                global.tools(), report, global.run(), ColumnSpecParser.sheets(sheet), caseId, tags, dataColumns, stages, headerRows, global.xmlNamespaceMode());
    }

    private List<StageConfig> stages(Object value) {
        if (!(value instanceof Iterable)) throw new IllegalArgumentException("stages must be a list");
        List<StageConfig> result = new ArrayList<StageConfig>();
        Set<String> keys = new LinkedHashSet<String>();
        for (Object item : (Iterable<?>) value) {
            if (!(item instanceof Map)) throw new IllegalArgumentException("Each stage must be a map");
            Map<?, ?> stage = (Map<?, ?>) item;
            SchemaSupport.rejectUnknown(stage, "sidecar.stages[" + result.size() + "]", "key", "template", "dataColumns", "required", "onFailure", "runWhen");
            String key = required(stage, "key");
            validateContextKey(key, "stage key");
            if (!keys.add(key)) throw new IllegalArgumentException("Duplicate stage key: " + key);
            String onFailure = optionalEnumText(stage.get("onFailure"), "stop", "stages." + key + ".onFailure");
            String runWhen = optionalEnumText(stage.get("runWhen"), "normal", "stages." + key + ".runWhen");
            if (!("stop".equals(onFailure) || "continue".equals(onFailure))) throw new IllegalArgumentException("Invalid onFailure for stage " + key + ": " + onFailure);
            if (!("normal".equals(runWhen) || "onSuccess".equals(runWhen) || "onFailure".equals(runWhen) || "always".equals(runWhen))) throw new IllegalArgumentException("Invalid runWhen for stage " + key + ": " + runWhen);
            List<DataColumnConfig> stageColumns = ColumnSpecParser.dataColumns(optionalString(stage.get("dataColumns"), "stages." + key + ".dataColumns"));
            rejectReserved(stageColumns, new String[]{"key", "status", "startedAt", "durationMs", "TEMPLATE"}, "stages." + key + ".dataColumns");
            result.add(new StageConfig(key, required(stage, "template"), stageColumns,
                    booleanValue(stage.get("required"), false, "required for stage " + key), onFailure, runWhen));
        }
        return result;
    }

    private ReportConfig mergeReport(Object value) {
        if (!(value instanceof Map)) return global.report();
        Map<?, ?> map = (Map<?, ?>) value;
        SchemaSupport.rejectUnknown(map, "sidecar.report", "columns");
        Map<String, String> columns = new LinkedHashMap<String, String>(global.report().columns());
        Object configured = map.get("columns");
        if (configured != null && !(configured instanceof Map)) throw new IllegalArgumentException("sidecar.report.columns must be a map");
        if (configured instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) configured).entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!java.util.Arrays.asList("result", "durationMs", "actualResult", "caseLog", "reportLink", "runTime").contains(key)) throw new IllegalArgumentException("Unknown report column key: " + key);
            if (!(e.getValue() instanceof String) || String.valueOf(e.getValue()).trim().isEmpty()) throw new IllegalArgumentException("Report column header must be a non-blank string: " + key);
            columns.put(key, String.valueOf(e.getValue()));
        }
        return new ReportConfig(global.report().mode(), global.report().fileNamePattern(), columns, global.report().junitCaseLogEmbedThresholdBytes());
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
        return SchemaSupport.string(map.get(key), key, true);
    }
    private static String optionalString(Object value, String owner) { if (value == null) return ""; if (!(value instanceof String)) throw new IllegalArgumentException(owner + " must be a string"); return (String) value; }
    private static String optionalEnumText(Object value, String fallback, String owner) { if (value == null) return fallback; return SchemaSupport.string(value, owner, true); }
    private static int positiveInteger(Object value, int fallback, String owner) {
        if (value != null && !(value instanceof Number)) throw new IllegalArgumentException(owner + " must be an integer");
        int result = value == null ? fallback : ((Number) value).intValue();
        int maximum = "excel.headerRows".equals(owner) ? 86400 : 86400000;
        if (result < 1 || result > maximum) throw new IllegalArgumentException(owner + " must be between 1 and " + maximum);
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
