package att.load;

import att.validation.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded failure/sample evidence sink, separate from functional run artifacts. */
public final class LoadEvidenceStore implements LoadEventListener {
    private final LoadEvidencePolicy policy;
    private final List<LoadEvent> retained = new ArrayList<LoadEvent>();
    private final Set<String> reservedSuccesses = new LinkedHashSet<String>();
    public LoadEvidenceStore(LoadEvidencePolicy policy) { this.policy = policy; }
    /** Reserves one bounded success-sample slot before the iteration starts. */
    public synchronized boolean reserveSuccess(String iterationId) {
        if (iterationId == null || reservedSuccesses.contains(iterationId)) return false;
        if (retained.size() + reservedSuccesses.size() >= policy.maxSamples()) return false;
        if (!policy.sampleSuccess(iterationId)) return false;
        reservedSuccesses.add(iterationId);
        return true;
    }
    @Override public synchronized void onEvent(LoadEvent event) {
        if (event == null || event.dropped() || !event.completed()) return;
        boolean reserved = reservedSuccesses.remove(event.iterationId());
        if (retained.size() >= policy.maxSamples()) return;
        if (reserved || policy.retain(event, retained.size())) retained.add(event);
    }
    public synchronized List<LoadEvent> events() { return Collections.unmodifiableList(new ArrayList<LoadEvent>(retained)); }
    public synchronized Map<String, Object> write(Path runDirectory) throws IOException {
        Path failureDir = runDirectory.resolve("failures"); Path sampleDir = runDirectory.resolve("samples");
        Map<String, Object> result = new LinkedHashMap<String, Object>(); List<Map<String, Object>> links = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (LoadEvent event : retained) {
            boolean failure = !event.success(); Path directory = failure ? failureDir : sampleDir; Files.createDirectories(directory);
            String fileName = String.format("%05d-%s.json", ++index, LoadIsolation.shortHash(event.iterationId())); Path file = directory.resolve(fileName);
            Files.write(file, JsonSupport.write(event.toMap(runDirectory)).getBytes(StandardCharsets.UTF_8));
            Map<String, Object> link = new LinkedHashMap<String, Object>(); link.put("iterationId", event.iterationId()); link.put("status", failure ? "FAILURE" : "SAMPLE"); link.put("path", runDirectory.relativize(file).toString().replace('\\', '/')); links.add(link);
        }
        result.put("count", retained.size()); result.put("items", links); return result;
    }
}
