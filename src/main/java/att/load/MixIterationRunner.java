package att.load;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dispatches to immutable target-specific executors prepared before scheduling. */
final class MixIterationRunner implements LoadIterationRunner {
    private final Map<String, IterationExecutor> executors;
    MixIterationRunner(Map<String, IterationExecutor> executors) {
        this.executors = Collections.unmodifiableMap(new LinkedHashMap<String, IterationExecutor>(executors));
    }
    @Override public IterationResult execute(IterationRequest request) {
        IterationExecutor executor = executors.get(request.mixId());
        if (executor == null) throw new IllegalArgumentException("No pre-resolved Load target for mix entry '" + request.mixId() + "'");
        return executor.execute(request);
    }
}
