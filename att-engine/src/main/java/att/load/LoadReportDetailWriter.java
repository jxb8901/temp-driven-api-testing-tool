package att.load;

import att.validation.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes versioned sidecars for detailed metric series in an archived LOAD report. */
final class LoadReportDetailWriter {
    static final String MANIFEST_SCHEMA = "att-load-detail-manifest/v1";
    static final String REFERENCE_SCHEMA = "att-load-detail-reference/v1";
    static final String SERIES_SCHEMA = "att-load-detail-series/v1";
    static final String MANIFEST_PATH = "details/v1/manifest.json";
    private static final int MAX_INLINE_ITEMS = 32;
    private static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CHUNK_PAYLOAD_BYTES = MAX_CHUNK_BYTES - 8 * 1024;
    private static final long MAX_DETAIL_BYTES = 100L * 1024 * 1024;
    private static final int MAX_DETAIL_FILES = 9_000;

    private LoadReportDetailWriter() { }

    static long write(Path runDirectory, Map<String, Object> summary) throws IOException {
        DetailBudget budget = new DetailBudget(runDirectory);
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();

        Object rawAggregateMetrics = summary.get("metrics");
        if (rawAggregateMetrics instanceof Map) {
            Map<String, Object> aggregateMetrics = mutableMap(castMap(rawAggregateMetrics));
            List<Map<String, Object>> aggregateSeries = externalizeCollections(aggregateMetrics, "metrics", "aggregate", null,
                    "details/v1/aggregate", budget);
            if (!aggregateSeries.isEmpty()) {
                aggregateMetrics.put("detailSeries", reference(aggregateSeries));
                records.addAll(aggregateSeries);
            }
            summary.put("metrics", aggregateMetrics);
        }

        List<Map<String, Object>> workloadRecords = new ArrayList<Map<String, Object>>();
        Object rawWorkloads = summary.get("workloads");
        if (rawWorkloads instanceof Map) {
            Map<String, Object> summaryWorkloads = new LinkedHashMap<String, Object>();
            int index = 0;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawWorkloads).entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Map)) continue;
                String workloadId = (String) entry.getKey();
                String workloadDir = String.format(java.util.Locale.ROOT, "details/v1/workloads/workload-%04d", ++index);
                Map<String, Object> row = mutableMap(castMap(entry.getValue()));
                Object rawMetrics = row.get("metrics");
                List<Map<String, Object>> workloadSeries = new ArrayList<Map<String, Object>>();
                if (rawMetrics instanceof Map) {
                    Map<String, Object> metrics = mutableMap(castMap(rawMetrics));
                    workloadSeries = externalizeCollections(metrics, "metrics", "workload", workloadId, workloadDir, budget);
                    if (!workloadSeries.isEmpty()) metrics.put("detailSeries", reference(workloadSeries));
                    row.put("metrics", metrics);
                }
                String summaryPath = workloadDir + "/summary.json";
                row.put("detailArtifact", summaryPath);
                Map<String, Object> detail = fields("schemaVersion", att.Version.LOAD_WORKLOAD_DETAIL_SCHEMA_VERSION,
                        "workloadId", workloadId, "workload", row);
                budget.write(summaryPath, JsonSupport.mapper().writeValueAsBytes(detail), MAX_CHUNK_BYTES);
                summaryWorkloads.put(workloadId, row);
                workloadRecords.add(fields("id", workloadId, "summaryPath", summaryPath, "series", workloadSeries));
                records.addAll(workloadSeries);
            }
            summary.put("workloads", summaryWorkloads);
        }

        Map<String, Object> manifest = fields("schemaVersion", MANIFEST_SCHEMA, "complete", Boolean.TRUE,
                "aggregate", fields("series", aggregateSeries(summary)), "workloads", workloadRecords,
                "series", records);
        byte[] manifestBytes = writeManifestWithAccurateBudget(manifest, budget);
        budget.write(MANIFEST_PATH, manifestBytes, MAX_MANIFEST_BYTES);
        summary.put("schemaVersion", att.Version.LOAD_SUMMARY_SCHEMA_V1_3);
        summary.put("detailManifestPath", MANIFEST_PATH);
        return budget.bytes();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> aggregateSeries(Map<String, Object> summary) {
        Object metrics = summary.get("metrics");
        Object reference = metrics instanceof Map ? ((Map<?, ?>) metrics).get("detailSeries") : null;
        Object series = reference instanceof Map ? ((Map<?, ?>) reference).get("series") : null;
        return series instanceof List ? (List<Map<String, Object>>) series : Collections.<Map<String, Object>>emptyList();
    }

    private static byte[] writeManifestWithAccurateBudget(Map<String, Object> manifest, DetailBudget budget) throws IOException {
        byte[] bytes = new byte[0];
        for (int attempt = 0; attempt < 8; attempt++) {
            manifest.put("detailFileCount", budget.fileCount() + 1);
            manifest.put("detailBytes", budget.bytes() + bytes.length);
            byte[] updated = JsonSupport.mapper().writeValueAsBytes(manifest);
            if (updated.length == bytes.length) return updated;
            bytes = updated;
        }
        manifest.put("detailFileCount", budget.fileCount() + 1);
        manifest.put("detailBytes", budget.bytes() + bytes.length);
        bytes = JsonSupport.mapper().writeValueAsBytes(manifest);
        if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("LOAD detail manifest exceeded its 16 MiB file budget.");
        return bytes;
    }

    private static Map<String, Object> reference(List<Map<String, Object>> series) {
        return fields("schemaVersion", REFERENCE_SCHEMA, "complete", Boolean.TRUE, "series", series);
    }

    private static List<Map<String, Object>> externalizeCollections(Map<String, Object> metrics, String root,
            String scope, String workloadId, String directory, DetailBudget budget) throws IOException {
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        CollectionSequence sequence = new CollectionSequence();
        for (Map.Entry<String, Object> entry : new ArrayList<Map.Entry<String, Object>>(metrics.entrySet())) {
            if ("detailSeries".equals(entry.getKey())) continue;
            metrics.put(entry.getKey(), externalize(entry.getValue(), root + "/" + pointer(entry.getKey()), scope,
                    workloadId, directory, budget, records, sequence));
        }
        return records;
    }

    private static Object externalize(Object value, String target, String scope, String workloadId, String directory,
                                      DetailBudget budget, List<Map<String, Object>> records,
                                      CollectionSequence sequence) throws IOException {
        if (value instanceof Map) {
            Map<String, Object> map = mutableMap(castMap(value));
            if (map.size() > MAX_INLINE_ITEMS && isMetricMapSeries(target)) {
                List<String> files = writeMapChunks(map, target, scope, workloadId, directory, budget, sequence.next());
                records.add(seriesRecord(target, scope, workloadId, "map", map.size(), files));
                return previewMap(map, MAX_INLINE_ITEMS);
            }
            for (Map.Entry<String, Object> entry : new ArrayList<Map.Entry<String, Object>>(map.entrySet()))
                map.put(entry.getKey(), externalize(entry.getValue(), target + "/" + pointer(entry.getKey()), scope,
                        workloadId, directory, budget, records, sequence));
            return map;
        }
        if (value instanceof List) {
            List<Object> list = mutableList((List<?>) value);
            if (list.size() > MAX_INLINE_ITEMS) {
                List<String> files = writeListChunks(list, target, scope, workloadId, directory, budget, sequence.next());
                records.add(seriesRecord(target, scope, workloadId, "list", list.size(), files));
                return previewList(list, MAX_INLINE_ITEMS);
            }
            for (int index = 0; index < list.size(); index++)
                list.set(index, externalize(list.get(index), target + "/" + index, scope, workloadId, directory,
                        budget, records, sequence));
            return list;
        }
        return value;
    }

    private static Map<String, Object> seriesRecord(String target, String scope, String workloadId,
                                                    String kind, int count, List<String> files) {
        Map<String, Object> record = fields("scope", scope, "target", target, "kind", kind,
                "count", count, "inlinePreviewCount", Math.min(MAX_INLINE_ITEMS, count),
                "complete", Boolean.TRUE, "files", files);
        if (workloadId != null) record.put("workloadId", workloadId);
        return record;
    }

    private static List<String> writeMapChunks(Map<String, Object> source, String target, String scope,
            String workloadId, String directory, DetailBudget budget, int sequence) throws IOException {
        List<Map<String, Object>> chunks = new ArrayList<Map<String, Object>>();
        Map<String, Object> chunk = new LinkedHashMap<String, Object>();
        int payloadBytes = 2;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            int itemBytes = JsonSupport.mapper().writeValueAsBytes(fields(entry.getKey(), entry.getValue())).length + 1;
            if (itemBytes > MAX_CHUNK_PAYLOAD_BYTES) throw new IOException("A LOAD metric map item exceeds the detailed artifact file budget.");
            if (!chunk.isEmpty() && payloadBytes + itemBytes > MAX_CHUNK_PAYLOAD_BYTES) {
                chunks.add(chunk); chunk = new LinkedHashMap<String, Object>(); payloadBytes = 2;
            }
            chunk.put(entry.getKey(), entry.getValue()); payloadBytes += itemBytes;
        }
        if (!chunk.isEmpty()) chunks.add(chunk);
        List<String> paths = new ArrayList<String>();
        int part = 0;
        for (Map<String, Object> items : chunks) {
            String path = filePath(directory, sequence, ++part);
            Map<String, Object> file = seriesFile(target, scope, workloadId, "map", part, chunks.size(), source.size(), items);
            budget.write(path, JsonSupport.mapper().writeValueAsBytes(file), MAX_CHUNK_BYTES); paths.add(path);
        }
        return paths;
    }

    private static List<String> writeListChunks(List<Object> source, String target, String scope,
            String workloadId, String directory, DetailBudget budget, int sequence) throws IOException {
        List<List<Object>> chunks = new ArrayList<List<Object>>();
        List<Object> chunk = new ArrayList<Object>();
        int payloadBytes = 2;
        for (Object item : source) {
            int itemBytes = JsonSupport.mapper().writeValueAsBytes(Collections.singletonList(item)).length;
            if (itemBytes > MAX_CHUNK_PAYLOAD_BYTES) throw new IOException("A LOAD metric list item exceeds the detailed artifact file budget.");
            if (!chunk.isEmpty() && payloadBytes + itemBytes > MAX_CHUNK_PAYLOAD_BYTES) {
                chunks.add(chunk); chunk = new ArrayList<Object>(); payloadBytes = 2;
            }
            chunk.add(item); payloadBytes += itemBytes;
        }
        if (!chunk.isEmpty()) chunks.add(chunk);
        List<String> paths = new ArrayList<String>();
        int part = 0;
        for (List<Object> items : chunks) {
            String path = filePath(directory, sequence, ++part);
            Map<String, Object> file = seriesFile(target, scope, workloadId, "list", part, chunks.size(), source.size(), items);
            budget.write(path, JsonSupport.mapper().writeValueAsBytes(file), MAX_CHUNK_BYTES); paths.add(path);
        }
        return paths;
    }

    private static Map<String, Object> seriesFile(String target, String scope, String workloadId, String kind,
                                                   int part, int parts, int total, Object items) {
        Map<String, Object> file = fields("schemaVersion", SERIES_SCHEMA, "scope", scope, "target", target,
                "kind", kind, "part", part, "parts", parts, "total", total, "items", items);
        if (workloadId != null) file.put("workloadId", workloadId);
        return file;
    }

    private static String filePath(String directory, int sequence, int part) {
        return directory + "/series-" + String.format(java.util.Locale.ROOT, "%04d-%04d", sequence, part) + ".json";
    }

    private static Map<String, Object> previewMap(Map<String, Object> values, int limit) {
        if (values.size() <= limit) return values;
        List<Map.Entry<String, Object>> entries = new ArrayList<Map.Entry<String, Object>>(values.entrySet());
        Map<String, Object> preview = new LinkedHashMap<String, Object>();
        for (int index = 0; index < limit; index++) {
            int source = (int) Math.round((double) index * (entries.size() - 1) / (limit - 1));
            Map.Entry<String, Object> entry = entries.get(source);
            preview.put(entry.getKey(), entry.getValue());
        }
        return preview;
    }

    private static List<Object> previewList(List<Object> values, int limit) {
        if (values.size() <= limit) return values;
        List<Object> preview = new ArrayList<Object>(limit);
        for (int index = 0; index < limit; index++) {
            int source = (int) Math.round((double) index * (values.size() - 1) / (limit - 1));
            preview.add(values.get(source));
        }
        return preview;
    }

    private static String pointer(String value) { return value.replace("~", "~0").replace("/", "~1"); }

    private static boolean isMetricMapSeries(String target) {
        String field = target.substring(target.lastIndexOf('/') + 1);
        return "buckets".equals(field) || "errorClassifications".equals(field)
                || "runtimeErrorClassifications".equals(field);
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> castMap(Object value) { return (Map<String, Object>) value; }

    private static Map<String, Object> mutableMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source != null) for (Map.Entry<String, Object> entry : source.entrySet()) result.put(entry.getKey(), mutable(entry.getValue()));
        return result;
    }

    private static List<Object> mutableList(List<?> source) {
        List<Object> result = new ArrayList<Object>(source.size());
        for (Object item : source) result.add(mutable(item));
        return result;
    }

    private static Object mutable(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                if (entry.getKey() instanceof String) result.put((String) entry.getKey(), mutable(entry.getValue()));
            return result;
        }
        if (value instanceof List) return mutableList((List<?>) value);
        return value;
    }

    private static Map<String, Object> fields(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private static final class CollectionSequence { private int value; int next() { return ++value; } }

    private static final class DetailBudget {
        private final Path root;
        private long bytes;
        private int files;
        DetailBudget(Path root) { this.root = root; }
        void write(String relative, byte[] content, int maxFileBytes) throws IOException {
            if (content.length > maxFileBytes) throw new IOException("LOAD detailed artifact file exceeded its bounded file budget.");
            if (files >= MAX_DETAIL_FILES) throw new IOException("LOAD detailed series exceeded its artifact entry budget at " + relative + ".");
            if (bytes + content.length > MAX_DETAIL_BYTES) throw new IOException("LOAD detailed series exceeded its 100 MiB budget.");
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root)) throw new IOException("LOAD detail path escaped the report directory.");
            Files.createDirectories(file.getParent()); Files.write(file, content);
            files++; bytes += content.length;
        }
        int fileCount() { return files; }
        long bytes() { return bytes; }
    }
}
