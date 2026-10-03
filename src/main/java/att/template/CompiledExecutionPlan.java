package att.template;

import att.flow.FlowDefinition;
import att.flow.FlowRegistry;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        Map<TemplateAction, ActionPlan> compiled = new IdentityHashMap<TemplateAction, ActionPlan>();
        List<ActionPlan> ordered = new ArrayList<ActionPlan>();
        Set<String> visitedFlows = new LinkedHashSet<String>();
        ToolCallParser parser = new ToolCallParser();
        compileTemplate(template, flows, parser, compiled, ordered, visitedFlows);
        return new CompiledExecutionPlan(compiled, ordered);
    }

    private static void compileTemplate(StageTemplate template, FlowRegistry flows, ToolCallParser parser,
                                        Map<TemplateAction, ActionPlan> compiled, List<ActionPlan> orderedActions,
                                        Set<String> visitedFlows) {
        if (template == null) return;
        for (TemplateAction action : template.actions()) {
            ActionPlan plan = new ActionPlan(action, parser);
            compiled.put(action, plan);
            orderedActions.add(plan);
            if ("flow".equalsIgnoreCase(action.type()) && flows != null && visitedFlows.add(action.use())) {
                FlowDefinition flow = flows.get(action.use());
                if (flow != null) compileTemplate(new StageTemplate(flow.name(), flow.directory(), flow.actions(),
                        flow.templateSchemaVersion(), flow.directory().resolve("flow.yaml")), flows, parser,
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
    public void recordEvaluation() { evaluations.increment(); }
    public long evaluations() { return evaluations.sum(); }

    public static final class ActionPlan {
        private final ToolCallParser.ParsedCall primaryCall;
        private final ExpressionBlockEvaluator.CompiledExpression runWhen;
        private final ExpressionBlockEvaluator.CompiledExpression assertion;
        private final UnifiedTemplateEngine.CompiledTemplate expected;
        private final UnifiedTemplateEngine.CompiledTemplate actual;
        private final RetryCondition.CompiledCondition retryWhen;

        private ActionPlan(TemplateAction action, ToolCallParser parser) {
            primaryCall = "tool".equalsIgnoreCase(action.type()) && !action.call().trim().isEmpty()
                    ? parser.parseCompiled(action.call()) : null;
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
    }
}
