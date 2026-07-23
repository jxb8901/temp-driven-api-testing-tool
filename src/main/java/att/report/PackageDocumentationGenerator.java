/* Author: Jeffrey + ChatGPT */
package att.report;

import att.Version;
import att.config.FrameworkConfig;
import att.config.DbHelperConfig;
import att.config.SuiteConfigResolver;
import att.config.ToolArgumentConfig;
import att.config.ToolConfig;
import att.core.CaseRuntimeContext;
import att.core.StageCaseData;
import att.core.TestCase;
import att.excel.ExcelTestSuiteLoader;
import att.template.StageTemplateLoader;
import att.template.TemplateAction;
import att.template.UnifiedTemplateEngine;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Stream;

/** Generates offline JavaDoc-like testcase/template/tool reference pages. */
public final class PackageDocumentationGenerator {
    /** Generates the one self-contained, searchable package reference. */
    public Path generate(Path projectRoot, FrameworkConfig config) throws Exception {
        Path output = projectRoot.resolve("build/docs");
        GeneratedOutputCleaner.deleteDirectory(output);
        Files.createDirectories(output);
        Map<String, Set<String>> filters = filterValues(projectRoot, config);
        String controls = "<nav aria-label=\"Package index\"><div class=\"index-links\"><strong>Index</strong><a href=\"#testcases\">Testcases</a><a href=\"#templates\">Templates</a><a href=\"#tools\">Tools</a><a href=\"#dbhelpers\">DB helpers</a><a href=\"#builtins\">Built-ins</a></div><div class=\"filters\"><input id=\"search\" type=\"search\" placeholder=\"Search any keyword\">" + select("workbookFilter", "All workbooks", filters.get("workbook")) + select("sheetFilter", "All sheets", filters.get("sheet")) + select("caseFilter", "All Case IDs", filters.get("caseid")) + select("toolFilter", "All tools / DB helpers / built-ins", filters.get("tool")) + "</div></nav>";
        String single = page("ATT V" + Version.PRODUCT + " Single-page Reference", "<header><h1>ATT V" + Version.PRODUCT + " Package Reference</h1><p>Testcases · Templates · Tools · DB helpers · Built-ins</p></header>" + controls
                + section("testcases", testcasePage(projectRoot, config, new ArrayList<String>()))
                + section("templates", templatePage(projectRoot, config, new ArrayList<String>()))
                + section("tools", toolPage(config, new ArrayList<String>()))
                + section("dbhelpers", dbHelperPage(config))
                + section("builtins", builtInPage())
                + "<script>(()=>{const q=document.querySelector('#search'),w=document.querySelector('#workbookFilter'),s=document.querySelector('#sheetFilter'),c=document.querySelector('#caseFilter'),t=document.querySelector('#toolFilter');function apply(){const term=q.value.trim().toLowerCase();document.querySelectorAll('.doc-item').forEach(x=>{const d=x.dataset;x.hidden=!((!term||x.innerText.toLowerCase().includes(term))&&(!w.value||d.workbook===w.value)&&(!s.value||d.sheet===s.value)&&(!c.value||d.caseid===c.value)&&(!t.value||(d.tool||'').split(' ').includes(t.value)))})}[q,w,s,c,t].forEach(x=>x.addEventListener(x===q?'input':'change',apply));apply()})();</script>");
        Path index = output.resolve("index.html");
        write(index, single);
        return index;
    }

    private String testcasePage(Path root, FrameworkConfig global, List<String> search) throws Exception {
        StringBuilder body = new StringBuilder("<h1>Testcases</h1><p><a href=\"../index.html\">Home</a></p>");
        StageTemplateLoader templateLoader = new StageTemplateLoader(root, global.templatesRoot());
        for (Path workbook : files(root.resolve(global.testcasesRoot()), ".xlsx")) {
            if (!Files.isRegularFile(workbook.resolveSibling(workbook.getFileName().toString().replaceFirst("(?i)\\.xlsx$", ".yaml")))) continue;
            FrameworkConfig suite = new SuiteConfigResolver(root, global).resolve(workbook);
            List<TestCase> cases = new ExcelTestSuiteLoader(suite).load(workbook);
            Map<String, List<TestCase>> casesBySheet = new LinkedHashMap<String, List<TestCase>>();
            for (TestCase test : cases) {
                if (!casesBySheet.containsKey(test.sheetName())) casesBySheet.put(test.sheetName(), new ArrayList<TestCase>());
                casesBySheet.get(test.sheetName()).add(test);
            }
            body.append("<h2>").append(escape(workbook.getFileName().toString())).append("</h2>");
            search.add(workbook.getFileName().toString());
            for (Map.Entry<String, List<TestCase>> sheet : casesBySheet.entrySet()) {
                body.append("<section class=\"sheet-group\" data-sheet=\"").append(escape(sheet.getKey())).append("\"><h3>Sheet: ").append(escape(sheet.getKey())).append("</h3><table><tr><th>Case ID</th><th>Name</th><th>Tags</th><th>Stages → Templates</th><th>Expected Result</th></tr>");
                for (TestCase test : sheet.getValue()) {
                    String templates = test.stages().values().stream().map(StageCaseData::templateName).reduce((a,b)->a+" "+b).orElse("");
                    Set<String> usedTools = new LinkedHashSet<String>();
                    UnifiedTemplateEngine callParser = new UnifiedTemplateEngine(null);
                    for (StageCaseData stage : test.stages().values()) for (TemplateAction action : templateLoader.load(stage.templateName()).actions()) {
                        if ("db".equalsIgnoreCase(action.type())) usedTools.add("db." + action.db());
                        String call=action.call();
                        if(call.startsWith("#{")&&call.indexOf('(')>2) usedTools.add(call.substring(2,call.indexOf('(')));
                        if ("assign".equalsIgnoreCase(action.type())) for (att.template.ToolCallParser.ParsedCall parsed : callParser.parseCalls(action.expression())) {
                            if (suite.tool(parsed.name()) != null) usedTools.add(parsed.name());
                        }
                    }
                    String expectedResult = expectedResult(root, suite, test, templateLoader);
                    String searchable=(test.workbookId()+" "+test.sheetName()+" "+test.caseId()+" "+test.caseName()+" "+test.tags()+" "+expectedResult.replace('\n', ' ')+" "+templates+" "+String.join(" ",usedTools)).toLowerCase(java.util.Locale.ROOT);
                    body.append("<tr class=\"doc-item\" data-search=\"").append(escape(searchable)).append("\" data-workbook=\"").append(escape(test.workbookId())).append("\" data-sheet=\"").append(escape(test.sheetName())).append("\" data-caseid=\"").append(escape(test.caseId())).append("\" data-tool=\"").append(escape(String.join(" ",usedTools))).append("\" data-template=\"").append(escape(templates)).append("\"><td>").append(escape(test.caseId())).append("</td><td>").append(escape(test.caseName())).append("</td><td>").append(escape(String.valueOf(test.tags()))).append("</td><td>");
                    for (StageCaseData stage : test.stages().values()) {
                        String canonical = templateLoader.load(stage.templateName()).name();
                        body.append(escape(stage.key())).append(" → <a href=\"../templates/index.html#").append(anchor(canonical)).append("\">").append(escape(stage.templateName())).append("</a><br>");
                    }
                    body.append("</td><td class=\"multiline\">").append(escape(expectedResult)).append("</td></tr>"); search.add(test.caseId()); search.add(test.caseName());
                }
                body.append("</table></section>");
            }
        }
        return page("Testcases", body.toString());
    }

    private String expectedResult(Path root, FrameworkConfig suite, TestCase test, StageTemplateLoader loader) throws Exception {
        CaseRuntimeContext context = new CaseRuntimeContext(test, root.resolve("build/docs"), "DOCS", root.resolve("build/docs"), root.resolve("build/docs/docs.log"));
        context.put("CASE.environment", suite.environment());
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null);
        StringBuilder result = new StringBuilder();
        for (StageCaseData stage : test.stages().values()) {
            att.template.StageTemplate template = loader.load(stage.templateName());
            context.beginStage(stage, template.name(), template.directory());
            for (TemplateAction action : template.actions()) {
                if ("assign".equalsIgnoreCase(action.type())) context.put("CASE.VARS." + action.name(), null);
                if ("assert".equalsIgnoreCase(action.type())) {
                    appendLine(result, engine.renderValidationValues(action.description(), context));
                    appendLine(result, engine.renderValidationValues(action.expected(), context));
                }
            }
        }
        return result.toString();
    }

    private void appendLine(StringBuilder output, String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) return;
        if (output.length() > 0) output.append('\n');
        output.append(normalized);
    }

    @SuppressWarnings("unchecked")
    private String templatePage(Path root, FrameworkConfig config, List<String> search) throws Exception {
        Path templateRoot = config.templatesRoot().isAbsolute() ? config.templatesRoot() : root.resolve(config.templatesRoot());
        StringBuilder body = new StringBuilder("<h1>Templates</h1><p><a href=\"../index.html\">Home</a></p>");
        for (Path file : files(templateRoot, "template.yaml")) {
            Map<String, Object> yaml = yaml(file);
            String path = templateRoot.relativize(file.getParent()).toString().replace('\\', '/');
            String name = String.valueOf(yaml.get("name") == null ? path : yaml.get("name"));
            body.append("<section class=\"doc-item\" data-search=\"").append(escape((name+" "+path+" "+String.valueOf(yaml.get("description"))).toLowerCase(java.util.Locale.ROOT))).append("\" data-template=\"").append(escape(name + " " + path)).append("\" id=\"").append(anchor(name)).append("\"><h2>").append(escape(name)).append("</h2><p><code>").append(escape(path)).append("</code></p><p>").append(escape(String.valueOf(yaml.get("description") == null ? "" : yaml.get("description")))).append("</p>");
            Object actions = yaml.get("actions");
            body.append("<table><tr><th>Action</th><th>Type</th><th>Tool / Details</th></tr>");
            if (actions instanceof Map) for (Map.Entry<?, ?> action : ((Map<?, ?>) actions).entrySet()) {
                Map<?, ?> value = action.getValue() instanceof Map ? (Map<?, ?>) action.getValue() : Collections.emptyMap();
                String call = String.valueOf(value.get("call") == null ? "" : value.get("call"));
                String tool = call.startsWith("#{") && call.indexOf('(') > 2 ? call.substring(2, call.indexOf('(')) : "";
                String db = String.valueOf(value.get("db") == null ? "" : value.get("db"));
                body.append("<tr><td>").append(escape(String.valueOf(action.getKey()))).append("</td><td>").append(escape(String.valueOf(value.get("type")))).append("</td><td>");
                if (!tool.isEmpty()) body.append("<a href=\"../tools/index.html#").append(anchor(tool)).append("\">").append(escape(tool)).append("</a>");
                else if (!db.isEmpty()) body.append("<a href=\"#dbhelper-").append(anchor(db)).append("\">db.").append(escape(db)).append("</a>");
                else body.append(escape(String.valueOf(value.get("payload") == null ? value.get("assert") : value.get("payload"))));
                body.append("</td></tr>");
            }
            body.append("</table></section>"); search.add(name); search.add(path);
        }
        return page("Templates", body.toString());
    }

    private String toolPage(FrameworkConfig config, List<String> search) {
        StringBuilder body = new StringBuilder("<h1>Tools</h1><p><a href=\"../index.html\">Home</a></p><div class=\"index\"><strong>Index:</strong> ");
        for (ToolConfig tool : config.tools().values()) body.append("<a href=\"#").append(anchor(tool.key())).append("\">").append(escape(tool.key())).append("</a> ");
        body.append("</div>");
        String activeGroup = null;
        for (ToolConfig tool : config.tools().values()) {
            String group = tool.grouped() ? tool.groupId() : "";
            if (!group.equals(activeGroup)) {
                body.append("<h2 class=\"tool-group\">").append(group.isEmpty() ? "Global tools" : "Tool group: " + escape(group)).append("</h2>");
                activeGroup = group;
            }
            body.append("<section class=\"doc-item\" data-search=\"").append(escape((tool.key()+" "+tool.name()+" "+tool.description()).toLowerCase(java.util.Locale.ROOT))).append("\" data-tool=\"").append(escape(tool.key())).append("\" id=\"").append(anchor(tool.key())).append("\"><h3>").append(escape(tool.name())).append(" <code>").append(escape(tool.key())).append("</code></h3><p>").append(escape(tool.description())).append("</p><p>");
            if (tool.callBacked()) {
                body.append("call: <code>").append(escape(tool.call())).append("</code>");
                if (!tool.cache().isEmpty()) body.append("; cache.scope=").append(escape(tool.cache()));
            } else {
                body.append("command argv: <code>[\"").append(escape(joinEscaped(tool.commandArgv()))).append("\"]</code>");
                body.append("; output=").append(escape(tool.output()));
            }
            body.append("</p>");
            if (!tool.groupScriptArgv().isEmpty()) body.append("<p>group script argv: <code>[\"").append(escape(joinEscaped(tool.groupScriptArgv()))).append("\"]</code></p>");
            if (tool.ssh() != null) body.append("<p>SSH: <code>").append(escape(tool.ssh().destination())).append(":").append(tool.ssh().port()).append("</code></p>");
            body.append("<table><tr><th>Key</th><th>Name</th><th>Description</th><th>Required</th><th>argName</th><th>argNameMode</th><th>Delimiter</th></tr>");
            for (ToolArgumentConfig arg : tool.arguments().values()) body.append("<tr><td>").append(escape(arg.key())).append("</td><td>").append(escape(arg.name())).append("</td><td>").append(escape(arg.description())).append("</td><td>").append(arg.required()).append("</td><td>").append(escape(arg.argName())).append("</td><td>").append(escape(arg.namedArgv() && arg.multiValue() ? arg.argNameMode() : "")).append("</td><td>").append(escape(arg.delimit())).append("</td></tr>");
            body.append("</table></section>"); search.add(tool.key()); search.add(tool.name()); search.add(tool.description());
        }
        return page("Tools", body.toString());
    }

    private String dbHelperPage(FrameworkConfig config) {
        StringBuilder body = new StringBuilder("<h1>DB helpers</h1><p>Connection credentials and properties are intentionally omitted.</p><div class=\"index\"><strong>Index:</strong> ");
        for (DbHelperConfig helper : config.dbHelpers().values()) {
            body.append("<a href=\"#dbhelper-").append(anchor(helper.id())).append("\">db.")
                    .append(escape(helper.id())).append("</a> ");
        }
        body.append("</div>");
        for (DbHelperConfig helper : config.dbHelpers().values()) {
            String searchable = (helper.id() + " " + helper.name() + " " + helper.description()).toLowerCase(java.util.Locale.ROOT);
            body.append("<section class=\"doc-item\" data-search=\"").append(escape(searchable))
                    .append("\" data-tool=\"db.").append(escape(helper.id())).append("\" id=\"dbhelper-")
                    .append(anchor(helper.id())).append("\"><h2>").append(escape(helper.name()))
                    .append(" <code>db.").append(escape(helper.id())).append("</code></h2><p>")
                    .append(escape(helper.description())).append("</p><table><tr><th>Read only</th><th>Driver class</th><th>Statement timeout</th><th>Transaction</th><th>Result limits</th></tr><tr><td>")
                    .append(helper.readOnly()).append("</td><td>").append(escape(helper.driverClass().isEmpty() ? "JDBC discovery" : helper.driverClass()))
                    .append("</td><td>").append(helper.timeoutSeconds()).append(" seconds</td><td>")
                    .append(escape(helper.transactionScope())).append(" / ").append(escape(helper.transactionOnEnd()))
                    .append("</td><td>rows=").append(helper.maxRows()).append(", cellBytes=").append(helper.maxCellBytes())
                    .append(", bytes=").append(helper.maxBytes()).append("</td></tr></table></section>");
        }
        return page("DB helpers", body.toString());
    }

    private String builtInPage() {
        String[][] functions = {{"upper","upper(value)","Convert text to upper case"},{"lower","lower(value)","Convert text to lower case"},{"trim","trim(value)","Trim surrounding whitespace"},{"ltrim","ltrim(value)","Trim leading whitespace"},{"rtrim","rtrim(value)","Trim trailing whitespace"},{"string","string(value)","Convert a value to text"},{"number","number(value)","Normalize a number"},{"boolean","boolean(value)","Normalize a boolean"},{"length","length(value)","Return text length"},{"concat","concat(first, ...)","Concatenate values"},{"coalesce","coalesce(first, ...)","Return the first non-blank value"},{"nvl","nvl(value, defaultValue)","Return the default for null or empty text"},{"iif","iif(condition, trueValue, falseValue)","Return one branch for a boolean condition"},{"nchar","nchar(count, value)","Repeat a value count times"},{"substr","substr(value, start[, length])","Extract text using a zero-based index"},{"indexof","indexOf(value, search[, fromIndex])","Return a zero-based text index"},{"contains","contains(value, search)","Test whether text contains a value"},{"startswith","startsWith(value, prefix)","Test a text prefix"},{"endswith","endsWith(value, suffix)","Test a text suffix"},{"replace","replace(value, target, replacement)","Replace literal text"},{"padleft","padLeft(value, length[, pad])","Pad the left side of text"},{"padright","padRight(value, length[, pad])","Pad the right side of text"},{"sysdate","sysdate([format])","Return the optionally formatted system date"},{"systimestamp","systimestamp([format])","Return the optionally formatted system timestamp"},{"formatdate","formatDate(value, pattern[, zoneId])","Format an ISO-8601 date or timestamp"},{"dateadd","dateAdd(value, amount, unit)","Add an amount to an ISO-8601 value"},{"fileexists","fileExists(path)","Test whether a regular file exists"},{"directoryexists","directoryExists(path)","Test whether a directory exists"},{"filesize","fileSize(path)","Return regular-file size in bytes"},{"makedirectories","makeDirectories(path)","Create a directory tree"},{"copyfile","copyFile(source, target[, overwrite])","Copy a regular file"},{"movefile","moveFile(source, target[, overwrite])","Move a regular file"},{"deletefile","deleteFile(path[, missingOk])","Delete a non-directory file"},{"randomchoice","randomChoice(first, ...)","Randomly return one of 1 to 1000 input values"}};
        StringBuilder body = new StringBuilder("<h1>Built-in functions</h1><div class=\"index\"><strong>Index:</strong> ");
        for (String[] fn : functions) body.append("<a href=\"#builtin-").append(anchor(fn[0])).append("\">").append(builtInName(fn[0])).append("</a> ");
        body.append("</div>");
        for (String[] fn : functions) body.append("<section class=\"doc-item\" data-search=\"").append((fn[0]+" "+fn[2]).toLowerCase(java.util.Locale.ROOT)).append("\" data-tool=\"").append(fn[0]).append("\" id=\"builtin-").append(anchor(fn[0])).append("\"><h2>").append(builtInName(fn[0])).append("</h2><p>").append(fn[2]).append("</p><p><code>#{").append(fn[1]).append("}</code></p></section>");
        return page("Built-in functions", body.toString());
    }

    private String builtInName(String key) {
        if ("indexof".equals(key)) return "indexOf";
        if ("startswith".equals(key)) return "startsWith";
        if ("endswith".equals(key)) return "endsWith";
        if ("padleft".equals(key)) return "padLeft";
        if ("padright".equals(key)) return "padRight";
        if ("formatdate".equals(key)) return "formatDate";
        if ("dateadd".equals(key)) return "dateAdd";
        if ("fileexists".equals(key)) return "fileExists";
        if ("directoryexists".equals(key)) return "directoryExists";
        if ("filesize".equals(key)) return "fileSize";
        if ("makedirectories".equals(key)) return "makeDirectories";
        if ("copyfile".equals(key)) return "copyFile";
        if ("movefile".equals(key)) return "moveFile";
        if ("deletefile".equals(key)) return "deleteFile";
        if ("randomchoice".equals(key)) return "randomChoice";
        return key;
    }

    private Map<String, Set<String>> filterValues(Path root, FrameworkConfig global) throws Exception {
        Map<String, Set<String>> values = new LinkedHashMap<String, Set<String>>();
        values.put("workbook", new LinkedHashSet<String>()); values.put("sheet", new LinkedHashSet<String>()); values.put("caseid", new LinkedHashSet<String>()); values.put("tool", new LinkedHashSet<String>());
        for (Path workbook : files(root.resolve(global.testcasesRoot()), ".xlsx")) {
            if (!Files.isRegularFile(workbook.resolveSibling(workbook.getFileName().toString().replaceFirst("(?i)\\.xlsx$", ".yaml")))) continue;
            FrameworkConfig suite = new SuiteConfigResolver(root, global).resolve(workbook);
            for (TestCase test : new ExcelTestSuiteLoader(suite).load(workbook)) {
                values.get("workbook").add(test.workbookId()); values.get("sheet").add(test.sheetName()); values.get("caseid").add(test.caseId());
            }
        }
        values.get("tool").addAll(global.tools().keySet());
        for (String helper : global.dbHelpers().keySet()) values.get("tool").add("db." + helper);
        values.get("tool").addAll(new att.template.DefaultBuiltInProvider().names());
        return values;
    }

    private String select(String id, String label, Set<String> values) {
        StringBuilder html = new StringBuilder("<select id=\"").append(id).append("\"><option value=\"\">").append(label).append("</option>");
        for (String value : values) html.append("<option value=\"").append(escape(value)).append("\">").append(escape(value)).append("</option>");
        return html.append("</select>").toString();
    }

    private Map<String, Object> yaml(Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file)) {
            Object value = att.config.YamlSupport.parser().load(reader); Map<String, Object> result = new LinkedHashMap<String, Object>();
            if (value instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
            return result;
        }
    }
    private List<Path> files(Path root, String suffix) throws Exception { if (!Files.isDirectory(root)) return Collections.emptyList(); List<Path> result = new ArrayList<Path>(); try (Stream<Path> s = Files.walk(root)) { s.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().forEach(result::add); } return result; }
    private String section(String id, String document) {
        int marker = document.indexOf("<body>");
        String content = marker >= 0 ? document.substring(marker + 6, document.lastIndexOf("</body>")) : document;
        content = content.replace("../templates/index.html#", "#").replace("../tools/index.html#", "#").replace("../index.html", "#");
        return "<main><section id=\"" + id + "\"><article>" + content + "</article></section></main>";
    }
    private String page(String title, String body) { return "<!doctype html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + escape(title) + "</title><style>*{box-sizing:border-box}body{margin:0;padding:32px;max-width:1280px;margin:auto;background:radial-gradient(circle at top left,#172554,#070a12 42%);color:#e5e7eb;font:14px/1.55 Inter,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;min-height:100vh}h1{font-size:30px}h2{margin-top:32px;color:#bfdbfe}h3{color:#c7d2fe}a{color:#60a5fa}nav{position:sticky;top:0;z-index:20;display:flex;flex-direction:column;gap:10px;background:#080b16f5;border:1px solid #334155;border-radius:12px;padding:12px;box-shadow:0 12px 30px #0008}.index-links,.filters{display:flex;flex-wrap:wrap;gap:10px;align-items:center}input,select{padding:11px 14px;border-radius:10px;border:1px solid #334155;background:#0f172a;color:white;box-shadow:0 8px 24px #0005}input{width:min(520px,100%)}table{border-collapse:collapse;width:100%;background:#0f172acc;border:1px solid #334155;border-radius:12px;overflow:hidden}th,td{border-bottom:1px solid #26334a;padding:10px;text-align:left;vertical-align:top}th{color:#93c5fd;background:#111c31}.multiline{white-space:pre-wrap}section{margin-bottom:2rem}code{color:#a7f3d0}.index{padding:12px;background:#0f172a;border:1px solid #334155;border-radius:10px}.index a{margin-right:10px}@media(max-width:700px){body{padding:18px}table{font-size:12px}}</style></head><body>" + body + "</body></html>"; }
    private void write(Path path, String value) throws Exception { Files.write(path, value.getBytes(StandardCharsets.UTF_8)); }
    private String anchor(String value) { return HtmlSupport.id(value); }
    private String escape(String value) { return HtmlSupport.escape(value); }
    private String joinEscaped(List<String> values) { StringBuilder out = new StringBuilder(); for (String v : values) { if (out.length() > 0) out.append("\",\""); out.append(v.replace("\\", "\\\\").replace("\"", "\\\"")); } return out.toString(); }
}
