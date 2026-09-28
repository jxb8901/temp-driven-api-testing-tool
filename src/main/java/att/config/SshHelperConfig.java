package att.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

/** One logical SSH target and its ordered physical instances. */
public final class SshHelperConfig {
    private final String id;
    private final String name;
    private final String description;
    private final String strategy;
    private final int maxConcurrency;
    private final Map<String, SshConfig> instances;
    private final AtomicInteger next = new AtomicInteger();

    public SshHelperConfig(String id, String name, String description, String strategy,
                           int maxConcurrency, Map<String, SshConfig> instances) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.strategy = strategy;
        this.maxConcurrency = maxConcurrency;
        this.instances = Collections.unmodifiableMap(new LinkedHashMap<String, SshConfig>(instances));
        if (this.instances.isEmpty()) throw new IllegalArgumentException("SSH helper requires instances");
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public String strategy() { return strategy; }
    public int maxConcurrency() { return maxConcurrency; }
    public Map<String, SshConfig> instances() { return instances; }

    public String select(String effectiveStrategy) {
        if (instances.size() == 1) return instances.keySet().iterator().next();
        int index = "random".equals(effectiveStrategy) ? ThreadLocalRandom.current().nextInt(instances.size())
                : next.getAndUpdate(value -> value + 1 == instances.size() ? 0 : value + 1);
        return new java.util.ArrayList<String>(instances.keySet()).get(index);
    }
}
