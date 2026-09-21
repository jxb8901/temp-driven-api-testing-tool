package att.load;

/** Common iteration execution boundary used by load schedulers and deterministic tests. */
@FunctionalInterface
interface LoadIterationRunner {
    IterationResult execute(IterationRequest request);
}
