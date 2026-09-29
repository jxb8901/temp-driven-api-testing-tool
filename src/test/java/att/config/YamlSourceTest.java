package att.config;

import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import att.validation.Diagnostic;
import att.validation.DiagnosticContext;
import att.validation.DiagnosticRenderer;
import att.validation.DiagnosticCodes;
import att.validation.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class YamlSourceTest {
    @TempDir Path root;

    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(root); }
    private Path write(String name, String text) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(StandardCharsets.UTF_8)); return path;
    }

    @Test void capturesNestedSequenceAndQuotedKeysWithoutParsingAgain() throws Exception {
        Path path = write("source.yaml", "tools:\n  echo:\n    command:\n      - echo\n      - hello\n    'a.b': value\n");
        assertTrue(YamlSupport.load(path) instanceof Map);
        SourceLocation item = YamlSupport.location(path, "$.tools.echo.command[1]", null);
        assertEquals(5, item.line()); assertEquals(9, item.column());
        assertEquals(6, YamlSupport.location(path, "/tools/echo/a.b", null).line());
        assertEquals(6, YamlSupport.location(path, "$.tools.echo['a.b']", null).line());
        assertEquals(3, YamlSupport.location(path, "/tools/echo/missing", null).line());
        assertNotNull(item.excerpt(), "Non-sensitive source fields should include a useful excerpt");
    }

    @Test void malformedAndDuplicateYamlRetainPhysicalMarks() throws Exception {
        Path path = write("bad.yaml", "name: one\nname: two\n");
        Exception cause = assertThrows(Exception.class, () -> YamlSupport.load(path));
        DiagnosticException error = YamlSupport.locate(DiagnosticException.wrap(DiagnosticCodes.CONFIG_INVALID,
                "Invalid config", cause, path.toString(), "$", "Correct duplicate keys."), path, "$");
        assertEquals(2, error.source().line()); assertEquals(1, error.source().column());
        assertTrue(error.format().contains("line=2"));
    }

    @Test void invalidToolCallPointsToSpecificCallInsteadOfWholeToolGroup() throws Exception {
        Path group = write("config/tools/db.yaml", "schemaVersion: att-tool-group/v2.6\nid: orders\nname: Orders\ndescription: Query orders\ntools:\n  date:\n    name: Date\n    description: Current date\n    call: \"#{db.orders.scalar(sql='select to_char(d,'yyyymmdd')')}\"\n");
        Path config = write("config/config.yaml", "schemaVersion: att-config/v2.6\ntoolGroups: [config/tools/db.yaml]\n");
        DiagnosticException error = assertThrows(DiagnosticException.class, () -> new FrameworkConfigLoader().load(config, root));
        assertEquals(group.toRealPath().toString(), error.file()); assertEquals("tools.date.call", error.field());
        assertEquals(9, error.source().line());
        String sourceLine = Files.readAllLines(group, StandardCharsets.UTF_8).get(8);
        assertEquals(sourceLine.indexOf("yyyymmdd") + 1, error.source().column());
    }

    @Test void dbHelperFailureDoesNotBlameGlobalConfig() throws Exception {
        Path helper = write("config/db.yaml", "schemaVersion: att-dbhelper/v2.5\nid: sample\nname: Example\ndescription: Example\nconnection: []\n");
        Path config = write("config/config.yaml", "schemaVersion: att-config/v2.6\ndbhelpers: [config/db.yaml]\n");
        DiagnosticException error = assertThrows(DiagnosticException.class, () -> new FrameworkConfigLoader().load(config, root));
        assertEquals(helper.toRealPath().toString(), error.file()); assertNotNull(error.source());
    }

    @Test void reloadingChangedFileReplacesOldSourceMarks() throws Exception {
        Path file = write("a.yaml", "key: value\n"); YamlSupport.load(file);
        write("a.yaml", "\n\nkey: next\n"); YamlSupport.load(file);
        assertEquals(3, YamlSupport.location(file, "key", null).line());
    }

    @Test void foldedAndEscapedScalarsUseHonestRangesAndInlineSecretsAreNotExcerpted() throws Exception {
        Path file = write("ranges.yaml", "folded: >-\n  #{1 + }\nescaped: \"prefix\\n#{1 + }\"\ninline: {password: hidden, value: bad}\napi: {apiKey: hidden, value: bad}\n");
        YamlSupport.load(file);
        att.template.ExpressionSyntaxException syntax = new att.template.ExpressionSyntaxException(6, 7, "operand", "end");
        SourceLocation folded = YamlSupport.location(file, "folded", syntax);
        assertEquals(1, folded.line()); assertTrue(folded.endLine() > folded.line());
        assertEquals(10, YamlSupport.location(file, "escaped", syntax).column());
        assertNull(YamlSupport.location(file, "inline.value", null).excerpt());
        assertNull(YamlSupport.location(file, "api.value", null).excerpt());
    }

    @Test void blockScalarCallErrorsMapToOriginalLineAndRenderBoundedMultilineExcerpts() throws Exception {
        String foldedText = "call: >-\n  #{http.payment.post(path='/x',\n    responseFormat=json,\n    body=)}\n";
        Path folded = write("folded-call.yaml", foldedText);
        Map<?, ?> foldedValue = (Map<?, ?>) YamlSupport.load(folded);
        String call = String.valueOf(foldedValue.get("call"));
        int offset = call.indexOf("body=") + "body=".length();
        att.template.ExpressionSyntaxException failure = new att.template.ExpressionSyntaxException(offset, offset + 1, "value", ")");
        SourceLocation source = YamlSupport.location(folded, "call", failure);
        assertEquals(4, source.line());
        assertEquals(10, source.column());
        assertTrue(source.excerpt().contains("call: >-"));
        assertTrue(source.excerpt().contains("body=)}"));
        assertEquals(source.line(), ((Map<?, ?>) source.toMap()).get("line"));
        assertEquals(1, ((Map<?, ?>) source.toMap()).get("excerptStartLine"));

        Diagnostic diagnostic = new Diagnostic(DiagnosticCodes.TEMPLATE_INVALID, Diagnostic.Severity.ERROR,
                "Invalid call expression", folded.toString(), "call", null, null, null, null, null,
                "Correct the call expression.", null, null, source, DiagnosticContext.EMPTY);
        String rendered = DiagnosticRenderer.validation(diagnostic);
        assertTrue(rendered.contains("call: >-"));
        assertTrue(rendered.contains("body=)}\n             ^"), rendered);

        StringBuilder longBlock = new StringBuilder("call: |-\n");
        for (int line = 0; line < 20; line++) longBlock.append("  part").append(line).append("\n");
        longBlock.append("  body=)}\n");
        Path literal = write("literal-call.yaml", longBlock.toString());
        Map<?, ?> literalValue = (Map<?, ?>) YamlSupport.load(literal);
        String literalCall = String.valueOf(literalValue.get("call"));
        int literalOffset = literalCall.indexOf("body=") + "body=".length();
        SourceLocation bounded = YamlSupport.location(literal, "call",
                new att.template.ExpressionSyntaxException(literalOffset, literalOffset + 1, "value", ")"));
        assertTrue(bounded.excerpt().split("\\r?\\n", -1).length <= 12);
        assertTrue(bounded.excerpt().contains("body=)}"));
        assertTrue(bounded.line() > bounded.excerptStartLine());

        Path single = write("single-call.yaml", "call: \"#{http.payment.get(path='/x', body=)}\"\n");
        Map<?, ?> singleValue = (Map<?, ?>) YamlSupport.load(single);
        String singleCall = String.valueOf(singleValue.get("call"));
        SourceLocation inline = YamlSupport.location(single, "call",
                new att.template.ExpressionSyntaxException(singleCall.indexOf("body=") + 5,
                        singleCall.indexOf("body=") + 6, "value", ")"));
        assertEquals(1, inline.line());
        assertFalse(inline.excerpt().contains("\n"));
    }

    @Test void everySchemaViolationCarriesItsOwnPhysicalSource() throws Exception {
        Path file = write("document.yaml", "a: wrong\nb: wrong\n");
        Path schema = write("schema.json", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"boolean\"}}}");
        Object data = YamlSupport.load(file);
        att.validation.JsonSchemaVerifier.SchemaValidationException failure = assertThrows(
                att.validation.JsonSchemaVerifier.SchemaValidationException.class,
                () -> att.validation.JsonSchemaVerifier.verify(schema, data));
        DiagnosticException error = YamlSupport.locateSchema(DiagnosticException.wrap(DiagnosticCodes.CONFIG_INVALID,
                "Invalid config", failure, file.toString(), failure.field(), "Correct the types."),
                file, failure.structuredViolations());
        assertEquals(2, error.schemaViolations().size());
        java.util.Set<Object> lines = new java.util.HashSet<Object>();
        for (Map<String,Object> item : error.schemaViolations()) lines.add(((Map<?,?>) item.get("source")).get("line"));
        assertEquals(new java.util.HashSet<Object>(java.util.Arrays.asList(1, 2)), lines);
    }
}
