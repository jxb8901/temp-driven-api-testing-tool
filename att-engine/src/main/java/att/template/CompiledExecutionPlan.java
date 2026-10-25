package att.template;

import att.flow.FlowDefinition;
import att.flow.FlowRegistry;
import att.config.FrameworkConfig;
import att.config.ToolConfig;
import att.config.SshHelperConfig;
import att.config.DbHelperConfig;
import att.config.MqHelperConfig;
import att.config.HttpHelperConfig;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Immutable Load-run compilation of selected Template/Flow actions. */
public final class CompiledExecutionPlan {
    private final Map<TemplateAction, ActionPlan> actions;
    private final List<ActionPlan> orderedActions;
    private final LongAdder evaluations = new LongAdder();

    private CompiledExecutionPlan(Map<TemplateAction, ActionPlan> actions, List<ActionPlan> orderedActions) {
        this.actions = Collections.unmodifiableMap(new IdentityHashMap<TemplateAction, ActionPlan>(actions));
        this.orderedActions = Collections.unmodifiableList(new ArrayList<ActionPlan>(orderedActions));
    }

    public static CompiledExecutionPlan compile(StageTemplate template, FlowRegistry flows) {
        return compile(template, flows, null);
    }

    public static CompiledExecutionPlan compile(StageTemplate template, FlowRegistry flows, FrameworkConfig config) {
        Map<TemplateAction, ActionPlan> compiled = new IdentityHashMap<TemplateAction, ActionPlan>();
        List<ActionPlan> ordered = new ArrayList<ActionPlan>();
        Set<String> visitedFlows = new LinkedHashSet<String>();
        ToolCallParser parser = new ToolCallParser();
        compileTemplate(template, flows, config, parser, compiled, ordered, visitedFlows);
        return new CompiledExecutionPlan(compiled, ordered);
    }

    private static void compileTemplate(StageTemplate template, FlowRegistry flows, FrameworkConfig config, ToolCallParser parser,
                                        Map<TemplateAction, ActionPlan> compiled, List<ActionPlan> orderedActions,
                                        Set<String> visitedFlows) {
        if (template == null) return;
        for (TemplateAction action : template.actions()) {
            ActionPlan plan = new ActionPlan(action, parser, flows, config);
            compiled.put(action, plan);
            orderedActions.add(plan);
            if ("flow".equalsIgnoreCase(action.type()) && flows != null && visitedFlows.add(action.use())) {
                FlowDefinition flow = flows.get(action.use());
                if (flow != null) compileTemplate(new StageTemplate(flow.name(), flow.directory(), flow.actions(),
                        flow.templateSchemaVersion(), flow.directory().resolve("flow.yaml")), flows, config, parser,
                        compiled, orderedActions, visitedFlows);
            }
        }
    }

    public ActionPlan action(TemplateAction action) {
        ActionPlan plan = actions.get(action);
        if (plan == null) throw new IllegalArgumentException("Action is outside the compiled Load execution plan: " + action.id());
        return plan;
    }

    public long actionCount() { return actions.size(); }
    public List<ActionPlan> orderedActions() { return orderedActions; }
    /** Static DB/MQ/HTTP helper inventory for a LOAD run. This inspects parsed
     * authored calls and config-backed Tool implementations; it never evaluates
     * arguments or invokes a Tool. */
    public List<TargetBinding> resourceDependencies() {
        return resourceDependencyInventory(Collections.<String>emptyList()).bindings();
    }

    /** Static helper dependencies plus resource references that cannot be resolved before execution. */
    public DependencyInventory resourceDependencyInventory(Iterable<String> additionalSources) {
        Map<String, TargetBinding> found = new LinkedHashMap<String, TargetBinding>();
        java.util.ArrayDeque<String> sources = new java.util.ArrayDeque<String>();
        Set<String> visitedTools = new LinkedHashSet<String>();
        Set<String> dynamic = new LinkedHashSet<String>();
        UnifiedTemplateEngine renderParser = new UnifiedTemplateEngine(null);
        for (ActionPlan plan : orderedActions) {
            if (plan.action.db() != null && !plan.action.db().trim().isEmpty()) {
                DbHelperConfig db = plan.ownerConfig == null ? null : plan.ownerConfig.dbHelper(plan.action.db());
                if (db != null) found.put("db:" + db.id(), new TargetBinding(null, db, null, null, null));
                else if (isDynamicReference(plan.action.db())) dynamic.add("DB");
            }
            collectStrings(plan.action.raw(), sources);
        }
        if (additionalSources != null) for (String source : additionalSources) if (source != null) sources.addLast(source);
        while (!sources.isEmpty()) {
            String source = sources.removeFirst();
            java.util.List<ToolCallParser.ParsedCall> calls;
            try { calls = renderParser.parseCalls(source); }
            catch (RuntimeException malformed) {
                collectDynamicResourceKinds(source, dynamic);
                continue;
            }
            for (ToolCallParser.ParsedCall call : calls) {
                TargetBinding binding = TargetBinding.resolve(call, ownerConfig());
                if (binding == null) { collectDynamicCallKind(call.name(), dynamic); continue; }
                String kind = resourceKind(call.name());
                if (kind != null && binding.db() == null && binding.mq() == null && binding.http() == null)
                    dynamic.add(kind);
                if (binding.db() != null) found.put("db:" + binding.db().id(), binding);
                if (binding.http() != null) found.put("http:" + binding.http().id(), binding);
                if (binding.mq() != null) {
                    MqHelperConfig mq = binding.mq();
                    for (MqHelperConfig instance : mq.instances().values()) {
                        MqHelperConfig physical = MqHelperConfig.physical(instance, mq.logicalId(), instance.instanceId());
                        found.put("mq:" + physical.poolKey(), new TargetBinding(null, null, physical, null, null));
                    }
                }
                ToolConfig tool = binding.tool();
                if (tool != null && tool.callBacked() && visitedTools.add(tool.name())) sources.addLast(tool.call());
            }
        }
        return new DependencyInventory(new ArrayList<TargetBinding>(found.values()), dynamic);
    }

    private static void collectDynamicResourceKinds(String source, Set<String> output) {
        if (source == null || !(source.contains("${") || source.contains("#{"))) return;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)\\b(db|mq|http)\\s*\\.").matcher(source);
        while (matcher.find()) output.add(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
    }

    private static boolean isDynamicReference(String value) {
        return value != null && (value.contains("${") || value.contains("#{")
                || value.contains("&{") || value.contains("@{"));
    }

    private static void collectDynamicCallKind(String name, Set<String> output) {
        String kind = resourceKind(name);
        if (kind != null && name != null && (name.contains("${") || name.contains("#{"))) output.add(kind);
    }

    private static String resourceKind(String name) {
        if (name == null) return null;
        int dot = name.indexOf('.');
        String prefix = dot < 0 ? name : name.substring(0, dot);
        if ("db".equalsIgnoreCase(prefix)) return "DB";
        if ("mq".equalsIgnoreCase(prefix)) return "MQ";
        if ("http".equalsIgnoreCase(prefix)) return "HTTP";
        return null;
    }

    public static final class DependencyInventory {
        private final List<TargetBinding> bindings;
        private final Set<String> dynamicKinds;
        private DependencyInventory(List<TargetBinding> bindings, Set<String> dynamicKinds) {
            this.bindings = Collections.unmodifiableList(new ArrayList<TargetBinding>(bindings));
            this.dynamicKinds = Collections.unmodifiableSet(new LinkedHashSet<String>(dynamicKinds));
        }
        public List<TargetBinding> bindings() { return bindings; }
        public Set<String> dynamicKinds() { return dynamicKinds; }
    }

    private FrameworkConfig ownerConfig() {
        for (ActionPlan plan : orderedActions) if (plan.ownerConfig != null) return plan.ownerConfig;
        return null;
    }

    private static void collectStrings(Object value, java.util.Queue<String> output) {
        if (value instanceof String) output.add((String) value);
        else if (value instanceof Map) for (Object nested : ((Map<?, ?>) value).values()) collectStrings(nested, output);
        else if (value instanceof Iterable) for (Object nested : (Iterable<?>) value) collectStrings(nested, output);
    }
    public void recordEvaluation() { evaluations.increment(); }
    public long evaluations() { return evaluations.sum(); }

    public static final class ActionPlan {
        private final TemplateAction action;
        private final FrameworkConfig ownerConfig;
        private final ToolCallParser.ParsedCall primaryCall;
        private final ExpressionBlockEvaluator.CompiledExpression runWhen;
        private final ExpressionBlockEvaluator.CompiledExpression assertion;
        private final UnifiedTemplateEngine.CompiledTemplate expected;
        private final UnifiedTemplateEngine.CompiledTemplate actual;
        private final RetryCondition.CompiledCondition retryWhen;
        private final FlowDefinition flow;
        private final ToolConfig configuredTool;
        private final SshHelperConfig configuredSshHelper;
        private final ToolCallParser.ParsedCall configuredToolCall;
        private final TargetBinding primaryTarget;
        private final TargetBinding configuredToolTarget;

        private ActionPlan(TemplateAction action, ToolCallParser parser, FlowRegistry flows, FrameworkConfig config) {
            this.action = action;
            this.ownerConfig = config;
            primaryCall = "tool".equalsIgnoreCase(action.type()) && !action.call().trim().isEmpty()
                    ? parser.parseCompiled(action.call()) : null;
            flow = "flow".equalsIgnoreCase(action.type()) && flows != null ? flows.get(action.use()) : null;
            configuredTool = primaryCall == null || config == null ? null : config.tool(primaryCall.name());
            configuredSshHelper = configuredTool == null || configuredTool.sshHelper().isEmpty() || config == null
                    ? null : config.sshHelper(configuredTool.sshHelper());
            configuredToolCall = configuredTool == null || !configuredTool.callBacked()
                    ? null : parser.parseCompiled(configuredTool.call());
            primaryTarget = TargetBinding.resolve(primaryCall, config);
            configuredToolTarget = TargetBinding.resolve(configuredToolCall, config);
            ExpressionBlockEvaluator compiler = new ExpressionBlockEvaluator();
            runWhen = expression(action.runWhen(), compiler);
            assertion = expression(action.assertion(), compiler);
            UnifiedTemplateEngine templateCompiler = new UnifiedTemplateEngine(null);
            expected = template(action.expected(), templateCompiler);
            actual = template(action.actual(), templateCompiler);
            retryWhen = action.retry().containsKey("when")
                    ? RetryCondition.compile(action.retry().get("when")) : null;
        }
        private static ExpressionBlockEvaluator.CompiledExpression expression(String value,
                                                                               ExpressionBlockEvaluator compiler) {
            return value == null || value.trim().isEmpty() ? null : compiler.compile(value);
        }
        private static UnifiedTemplateEngine.CompiledTemplate template(String value, UnifiedTemplateEngine compiler) {
            return value == null || value.isEmpty() ? null : compiler.compileTemplate(value);
        }
        public ToolCallParser.ParsedCall primaryCall() { return primaryCall; }
        public ExpressionBlockEvaluator.CompiledExpression runWhen() { return runWhen; }
        public ExpressionBlockEvaluator.CompiledExpression assertion() { return assertion; }
        public UnifiedTemplateEngine.CompiledTemplate expected() { return expected; }
        public UnifiedTemplateEngine.CompiledTemplate actual() { return actual; }
        public RetryCondition.CompiledCondition retryWhen() { return retryWhen; }
        public FlowDefinition flow() { return flow; }
        /** Resolved immutable Tool identity, null for built-ins, calls, and standalone plans. */
        public ToolConfig configuredTool() { return configuredTool; }
        public SshHelperConfig configuredSshHelper() { return configuredSshHelper; }
        /** Compiled implementation call for a call-backed configured Tool. */
        public ToolCallParser.ParsedCall configuredToolCall() { return configuredToolCall; }
        public TargetBinding primaryTarget() { return primaryTarget; }
        public TargetBinding configuredToolTarget() { return configuredToolTarget; }
    }

    /** Immutable identity for a configured call target resolved during Load compilation. */
    public static final class TargetBinding {
        private final ToolConfig tool;
        private final DbHelperConfig db;
        private final MqHelperConfig mq;
        private final HttpHelperConfig http;
        private final SshHelperConfig ssh;
        private TargetBinding(ToolConfig tool, DbHelperConfig db, MqHelperConfig mq,
                              HttpHelperConfig http, SshHelperConfig ssh) {
            this.tool = tool; this.db = db; this.mq = mq; this.http = http; this.ssh = ssh;
        }
        public static TargetBinding resolve(ToolCallParser.ParsedCall call, FrameworkConfig config) {
            if (call == null || config == null) return null;
            String name = call.name();
            String[] parts = name.split("\\.", -1);
            if (parts.length < 2) return new TargetBinding(config.tool(name), null, null, null, null);
            if ("db".equals(parts[0])) return new TargetBinding(null, config.dbHelper(parts[1]), null, null, null);
            if ("mq".equals(parts[0])) return new TargetBinding(null, null, config.mqHelper(parts[1]), null, null);
            if ("http".equals(parts[0])) return new TargetBinding(null, null, null, config.httpHelper(parts[1]), null);
            if ("ssh".equals(parts[0])) return new TargetBinding(null, null, null, null, config.sshHelper(parts[1]));
            return new TargetBinding(config.tool(name), null, null, null, null);
        }
        public ToolConfig tool() { return tool; }
        public DbHelperConfig db() { return db; }
        public MqHelperConfig mq() { return mq; }
        public HttpHelperConfig http() { return http; }
        public SshHelperConfig ssh() { return ssh; }
    }
}
