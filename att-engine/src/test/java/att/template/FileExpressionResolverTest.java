package att.template;

import att.Version;
import att.core.CaseRuntimeContext;
import att.core.TestCase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileExpressionResolverTest {
    @TempDir Path project;

    @Test void staticFileIsReturnedAsOneStringAndCachedByFingerprint() throws Exception {
        Path file = write("templates/request.txt", "plain UTF-8 body");
        FileExpressionResolver resolver = new FileExpressionResolver(project);

        String value = resolver.evaluate("templates/request.txt", project.resolve("templates"), runtime(Collections.<String, Object>emptyMap()));

        assertEquals("plain UTF-8 body", value);
        assertEquals(1L, resolver.stats().compiled());
        assertEquals(1L, resolver.stats().staticHits());
        assertEquals(file.toRealPath(), resolver.resolve("templates/request.txt", project.resolve("templates")));
    }

    @Test void projectAwareEngineEvaluatesExactFileValuesWithoutReparsingRenderedOutput() throws Exception {
        write("templates/T/request.txt", "body-${CASE.caseId}");
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2, "suite", "sheet", "TC1", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.emptyMap(), null),
                project, "RUN-1", project, project.resolve("case.log"));
        UnifiedTemplateEngine engine = UnifiedTemplateEngine.forProject(project);

        try (UnifiedTemplateEngine.SourceScope ignored = engine.pushSourceDirectory(project.resolve("templates/T"))) {
            assertEquals("body-suite.TC1", engine.evaluate("&{./request.txt}", context, null));
            assertEquals("prefix body-suite.TC1 suffix", engine.render("prefix &{./request.txt} suffix", context, null));
            assertEquals("BODY-SUITE.TC1", engine.evaluate("#{upper(value=&{./request.txt})}", context, null));
        }
    }

    @Test void dynamicFilePlanReevaluatesContextAndCallWithoutReparsingSource() throws Exception {
        write("templates/request.txt", "id=${EXEC.INPUT.id}|name=#{upper(value=${EXEC.INPUT.name})}");
        FileExpressionResolver resolver = new FileExpressionResolver(project);
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("EXEC.INPUT.id", "A");
        first.put("EXEC.INPUT.name", "alice");
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("EXEC.INPUT.id", "B");
        second.put("EXEC.INPUT.name", "bob");

        String one = resolver.evaluate("templates/request.txt", project.resolve("templates"), runtime(first));
        String two = resolver.evaluate("templates/request.txt", project.resolve("templates"), runtime(second));

        assertEquals("id=A|name=ALICE", one);
        assertEquals("id=B|name=BOB", two);
        assertEquals(1L, resolver.stats().compiled());
        assertTrue(resolver.stats().hits() >= 1L);
        assertEquals(2L, resolver.stats().evaluations());
    }

    @Test void fingerprintChangeRecompilesPlanAndUsesNewContent() throws Exception {
        Path file = write("request.txt", "one");
        FileExpressionResolver resolver = new FileExpressionResolver(project);
        RuntimeStub runtime = runtime(Collections.<String, Object>emptyMap());

        assertEquals("one", resolver.evaluate("request.txt", project, runtime));
        Files.write(file, "changed content".getBytes(StandardCharsets.UTF_8));

        assertEquals("changed content", resolver.evaluate("request.txt", project, runtime));
        assertEquals(2L, resolver.stats().compiled());
    }

    @Test void descriptorRelativeParentInsideRootIsAllowedButOutsideRootIsRejected() throws Exception {
        write("templates/shared/request.txt", "shared");
        Path template = project.resolve("templates/payment");
        Files.createDirectories(template);
        FileExpressionResolver resolver = new FileExpressionResolver(project);

        assertEquals("shared", resolver.evaluate("../shared/request.txt", template, runtime(Collections.<String, Object>emptyMap())));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.evaluate("../../outside.txt", template, runtime(Collections.<String, Object>emptyMap())));
    }

    @Test void symlinkEscapeAndGlobLocatorsAreRejected() throws Exception {
        Path outside = project.getParent().resolve("att-file-expression-outside-" + System.nanoTime() + ".txt");
        Files.write(outside, "secret".getBytes(StandardCharsets.UTF_8));
        Path link = project.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException error) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable");
        }
        try {
            FileExpressionResolver resolver = new FileExpressionResolver(project);
            assertThrows(IllegalArgumentException.class,
                    () -> resolver.evaluate("linked.txt", project, runtime(Collections.<String, Object>emptyMap())));
            assertThrows(IllegalArgumentException.class,
                    () -> resolver.evaluate("*.txt", project, runtime(Collections.<String, Object>emptyMap())));
            assertThrows(IllegalArgumentException.class,
                    () -> resolver.evaluate("C:/outside.txt", project, runtime(Collections.<String, Object>emptyMap())));
            assertThrows(IllegalArgumentException.class,
                    () -> resolver.evaluate("\\\\server\\share\\outside.txt", project, runtime(Collections.<String, Object>emptyMap())));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test void invalidUtf8AndDirectoryValuesAreRejected() throws Exception {
        Files.createDirectories(project.resolve("directory"));
        Files.write(project.resolve("invalid.txt"), new byte[]{(byte) 0xc3, (byte) 0x28});
        FileExpressionResolver resolver = new FileExpressionResolver(project);

        assertThrows(IllegalArgumentException.class,
                () -> resolver.evaluate("invalid.txt", project, runtime(Collections.<String, Object>emptyMap())));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.evaluate("directory", project, runtime(Collections.<String, Object>emptyMap())));
    }

    @Test void loadSnapshotFreezesCompiledPlanAndFileContent() throws Exception {
        Path templateDirectory = project.resolve("templates/T");
        Files.createDirectories(templateDirectory);
        Path source = write("templates/T/request.txt", "before");
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("type", "assign");
        values.put("name", "request");
        values.put("expression", "&{templates/T/request.txt}");
        StageTemplate template = new StageTemplate("T", templateDirectory,
                Collections.singletonList(new TemplateAction("request", values, Version.TEMPLATE_SCHEMA)),
                Version.TEMPLATE_SCHEMA, templateDirectory.resolve("template.yaml"));
        FileExpressionResolver resolver = new FileExpressionResolver(project);
        FileExpressionResolver.FileExpressionSnapshot snapshot = resolver.snapshotFor(template, null);
        Files.write(source, "after".getBytes(StandardCharsets.UTF_8));

        FileExpressionResolver frozen = new FileExpressionResolver(project, snapshot);
        assertEquals("before", frozen.evaluate("templates/T/request.txt", templateDirectory,
                runtime(Collections.<String, Object>emptyMap())));
        assertEquals(1, snapshot.size());
    }

    @Test void directFlowLoadSnapshotTraversesTheActualFlowBehindItsSyntheticWrapper() throws Exception {
        att.TestSchemas.install(project);
        Path flowDirectory = project.resolve("templates/flows/mqtest");
        Files.createDirectories(flowDirectory);
        Path source = write("templates/flows/mqtest/AFT19222.xml", "before-load");
        Files.write(flowDirectory.resolve("flow.yaml"), ("schemaVersion: att-flow/v3.4\n"
                + "id: mqtest.v1\nname: MQ Test\ndescription: MQ Test Flow\nactions:\n"
                + "  request: {type: assign, name: request, expression: '&{./AFT19222.xml}'}\n")
                .getBytes(StandardCharsets.UTF_8));
        att.flow.FlowRegistry flows = new att.flow.FlowRegistry(project, project.resolve("templates"), false);
        Map<String, Object> wrapperAction = values("type", "flow", "use", "mqtest.v1");
        StageTemplate wrapper = new StageTemplate("MQ Test", flowDirectory,
                Collections.singletonList(new TemplateAction("loadFlow", wrapperAction, Version.TEMPLATE_SCHEMA)),
                Version.TEMPLATE_SCHEMA, flowDirectory.resolve("flow.yaml"));

        FileExpressionResolver.FileExpressionSnapshot snapshot = new FileExpressionResolver(project)
                .snapshotForLoadFlow(wrapper, flows, "mqtest.v1");
        assertEquals(1, snapshot.size());
        Files.write(source, "after-load".getBytes(StandardCharsets.UTF_8));
        FileExpressionResolver frozen = new FileExpressionResolver(project, snapshot);
        assertEquals("before-load", frozen.evaluate("./AFT19222.xml", flowDirectory,
                runtime(Collections.<String, Object>emptyMap())));
    }

    @Test void runtimeStringsNeverBecomeFileLocatorsInRunDebugOrLoad() throws Exception {
        write("templates/T/private.txt", "private-content");
        write("templates/T/request.txt", "${EXEC.INPUT.value}");
        Path directory = project.resolve("templates/T");
        CaseRuntimeContext context = new CaseRuntimeContext(
                new TestCase(2, "suite", "sheet", "TC1", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.emptyMap(), null),
                project, "RUN-1", project, project.resolve("case.log"));
        context.beginStage(new att.core.StageCaseData("invoke", "T",
                Collections.<String, Object>singletonMap("value", "&{templates/T/private.txt}")), "T", directory);
        att.config.ToolConfig tool = new att.config.ToolConfig("literal", "literal", "", "Literal", "",
                Collections.<String>emptyList(), "#{str.concat(a=${TOOL.input.value})}",
                Collections.<String>emptyList(), "",
                Collections.singletonMap("value", new att.config.ToolArgumentConfig("value", "", "", true, "")), null, null);
        att.config.FrameworkConfig config = new att.config.FrameworkConfig(project, project, project, "SIT", 1000,
                project, Collections.singletonMap("literal", tool), null, null);
        att.exec.ToolInvoker invoker = new att.exec.ToolInvoker(project, config);
        StageTemplate template = new StageTemplate("T", directory, Collections.singletonList(
                new TemplateAction("request", values("type", "assign", "name", "request",
                        "expression", "&{./request.txt}"), Version.TEMPLATE_SCHEMA)), Version.TEMPLATE_SCHEMA);
        FileExpressionResolver.FileExpressionSnapshot snapshot = new FileExpressionResolver(project).snapshotFor(template, null);
        UnifiedTemplateEngine[] engines = {new UnifiedTemplateEngine(invoker), new UnifiedTemplateEngine(invoker),
                UnifiedTemplateEngine.withFileSnapshot(invoker, null, null, null, new DefaultBuiltInProvider(), snapshot)};
        for (UnifiedTemplateEngine engine : engines) {
            try (UnifiedTemplateEngine.SourceScope ignored = engine.pushSourceDirectory(directory)) {
                assertEquals("prefix &{templates/T/private.txt}", engine.render("prefix ${EXEC.INPUT.value}", context, null));
                assertEquals("&{templates/T/private.txt}", engine.evaluate("${EXEC.INPUT.value}", context, null));
                assertEquals("prefix &{templates/T/private.txt}",
                        engine.render("prefix #{literal(value=${EXEC.INPUT.value})}", context, null));
                assertEquals("&{templates/T/private.txt}",
                        engine.evaluate("#{str.concat(a='${EXEC.INPUT.value}')}", context, null));
                assertEquals("prefix &{templates/T/private.txt}", engine.render("prefix &{./request.txt}", context, null));
            }
        }
        assertEquals(1, snapshot.size());
        assertEquals("private-content", UnifiedTemplateEngine.forProject(project)
                .evaluate("&{templates/T/private.txt}", context, null));
    }

    @Test void nestedFileLocatorsAreRejectedInRunDebugAndLoadIncludingCallArguments() throws Exception {
        Path directory = project.resolve("templates/T");
        write("templates/T/part.txt", "part");
        String[] contents = {"&{./part.txt}", "#{str.concat(a=&{./part.txt})}"};
        for (int index = 0; index < contents.length; index++) {
            String file = "nested" + index + ".txt";
            write("templates/T/" + file, contents[index]);
            StageTemplate template = new StageTemplate("T", directory, Collections.singletonList(
                    new TemplateAction("nested", values("type", "assign", "name", "nested",
                            "expression", "&{" + file + "}"), Version.TEMPLATE_SCHEMA)), Version.TEMPLATE_SCHEMA);
            for (int mode = 0; mode < 2; mode++) {
                FileExpressionResolver resolver = new FileExpressionResolver(project);
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> resolver.evaluate("./" + file, directory, runtime(Collections.<String, Object>emptyMap())));
                assertTrue(error.getMessage().contains("Nested file-content expressions"));
            }
            assertThrows(IllegalArgumentException.class, () -> new FileExpressionResolver(project).snapshotFor(template, null));
        }
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        return values;
    }

    private Path write(String relative, String value) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, value.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private RuntimeStub runtime(final Map<String, Object> values) {
        return new RuntimeStub(values);
    }

    private static final class RuntimeStub implements FileExpressionResolver.Runtime {
        private final Map<String, Object> values;
        private RuntimeStub(Map<String, Object> values) { this.values = values; }
        @Override public Object context(String path, boolean optional) {
            Object value = values.get(path);
            if (value == null && !optional) throw new IllegalArgumentException("missing " + path);
            return value;
        }
        @Override public Object call(String name, Map<String, Object> arguments) {
            assertEquals("upper", name);
            return String.valueOf(arguments.get("value")).toUpperCase(java.util.Locale.ROOT);
        }
        @Override public String interpolate(String value) { return value; }
        @Override public boolean hasContext(String path) { return values.containsKey(path); }
        @Override public String file(String authoredPath) { throw new UnsupportedOperationException(authoredPath); }
    }
}
