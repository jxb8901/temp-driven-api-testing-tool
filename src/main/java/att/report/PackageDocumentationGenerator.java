/* Author: Jeffrey + ChatGPT */
package att.report;

import att.config.FrameworkConfig;
import att.config.SuiteConfigResolver;
import att.config.ToolArgumentConfig;
import att.config.ToolConfig;
import att.core.StageCaseData;
import att.core.TestCase;
import att.excel.ExcelTestSuiteLoader;
import att.template.StageTemplateLoader;
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
import java.util.stream.Stream;

/** Generates offline JavaDoc-like testcase/template/tool reference pages. */
public final class PackageDocumentationGenerator {
    public Path generate(Path projectRoot, FrameworkConfig config) throws Exception {
        return generate(projectRoot, config, false);
    }

    /** Generates the normal JavaDoc layout and a portable single-page edition. */
    public Path generate(Path projectRoot, FrameworkConfig config, boolean singlePage) throws Exception {
        Path output = projectRoot.resolve("build/docs");
        Files.createDirectories(output.resolve("testcases"));
        Files.createDirectories(output.resolve("templates"));
        Files.createDirectories(output.resolve("tools"));
        List<String> search = new ArrayList<String>();
        write(output.resolve("testcases/index.html"), testcasePage(projectRoot, config, search));
        write(output.resolve("templates/index.html"), templatePage(projectRoot, config, search));
        write(output.resolve("tools/index.html"), toolPage(config, search));
        String index = page("ATT V2 Package Reference", "<h1>ATT V2 Package Reference</h1><ul><li><a href=\"testcases/index.html\">Testcases</a></li><li><a href=\"templates/index.html\">Templates</a></li><li><a href=\"tools/index.html\">Tools</a></li></ul>");
        write(output.resolve("index.html"), index);
        write(output.resolve("search-index.json"), "[\"" + joinEscaped(search) + "\"]");
        String single = page("ATT V2 Single-page Reference", "<header><h1>ATT V2 Package Reference</h1><p>Testcases · Templates · Tools</p></header><nav><input id=\"search\" placeholder=\"Search English or 中文\"></nav>"
                + section("testcases", testcasePage(projectRoot, config, new ArrayList<String>()))
                + section("templates", templatePage(projectRoot, config, new ArrayList<String>()))
                + section("tools", toolPage(config, new ArrayList<String>()))
                + "<script>const q=document.querySelector('#search');q.oninput=()=>document.querySelectorAll('article').forEach(x=>x.hidden=!x.innerText.toLowerCase().includes(q.value.toLowerCase()));</script>");
        write(output.resolve("single-page.html"), single);
        return output.resolve(singlePage ? "single-page.html" : "index.html");
    }

    private String testcasePage(Path root, FrameworkConfig global, List<String> search) throws Exception {
        StringBuilder body = new StringBuilder("<h1>Testcases</h1><p><a href=\"../index.html\">Home</a></p>");
        StageTemplateLoader templateLoader = new StageTemplateLoader(root, global.templatesRoot());
        for (Path workbook : files(root.resolve("testcase"), ".xlsx")) {
            FrameworkConfig suite = new SuiteConfigResolver(root, global).resolve(workbook);
            List<TestCase> cases = new ExcelTestSuiteLoader(suite).load(workbook);
            body.append("<h2>").append(escape(workbook.getFileName().toString())).append("</h2><table><tr><th>Case ID</th><th>Name</th><th>Tags</th><th>Sheet</th><th>Stages → Templates</th></tr>");
            search.add(workbook.getFileName().toString());
            for (TestCase test : cases) {
                body.append("<tr><td>").append(escape(test.caseId())).append("</td><td>").append(escape(test.caseName())).append("</td><td>").append(escape(String.valueOf(test.tags()))).append("</td><td>").append(escape(test.sheetName())).append("</td><td>");
                for (StageCaseData stage : test.stages().values()) {
                    String canonical = templateLoader.load(stage.templateName()).name();
                    body.append(escape(stage.key())).append(" → <a href=\"../templates/index.html#").append(anchor(canonical)).append("\">").append(escape(stage.templateName())).append("</a><br>");
                }
                body.append("</td></tr>"); search.add(test.caseId()); search.add(test.caseName());
            }
            body.append("</table>");
        }
        return page("Testcases", body.toString());
    }

    @SuppressWarnings("unchecked")
    private String templatePage(Path root, FrameworkConfig config, List<String> search) throws Exception {
        Path templateRoot = config.templatesRoot().isAbsolute() ? config.templatesRoot() : root.resolve(config.templatesRoot());
        StringBuilder body = new StringBuilder("<h1>Templates</h1><p><a href=\"../index.html\">Home</a></p>");
        for (Path file : files(templateRoot, "template.yaml")) {
            Map<String, Object> yaml = yaml(file);
            String path = templateRoot.relativize(file.getParent()).toString().replace('\\', '/');
            String name = String.valueOf(yaml.get("name") == null ? path : yaml.get("name"));
            body.append("<section id=\"").append(anchor(name)).append("\"><h2>").append(escape(name)).append("</h2><p><code>").append(escape(path)).append("</code></p><p>").append(escape(String.valueOf(yaml.get("description") == null ? "" : yaml.get("description")))).append("</p>");
            Object actions = yaml.get("actions");
            body.append("<table><tr><th>Action</th><th>Type</th><th>Tool / Details</th></tr>");
            if (actions instanceof Map) for (Map.Entry<?, ?> action : ((Map<?, ?>) actions).entrySet()) {
                Map<?, ?> value = action.getValue() instanceof Map ? (Map<?, ?>) action.getValue() : Collections.emptyMap();
                String call = String.valueOf(value.get("call") == null ? "" : value.get("call"));
                String tool = call.startsWith("#{") && call.indexOf('(') > 2 ? call.substring(2, call.indexOf('(')) : "";
                body.append("<tr><td>").append(escape(String.valueOf(action.getKey()))).append("</td><td>").append(escape(String.valueOf(value.get("type")))).append("</td><td>");
                if (!tool.isEmpty()) body.append("<a href=\"../tools/index.html#").append(anchor(tool)).append("\">").append(escape(tool)).append("</a>");
                else body.append(escape(String.valueOf(value.get("payload") == null ? value.get("expression") : value.get("payload"))));
                body.append("</td></tr>");
            }
            body.append("</table></section>"); search.add(name); search.add(path);
        }
        return page("Templates", body.toString());
    }

    private String toolPage(FrameworkConfig config, List<String> search) {
        StringBuilder body = new StringBuilder("<h1>Tools</h1><p><a href=\"../index.html\">Home</a></p>");
        for (ToolConfig tool : config.tools().values()) {
            body.append("<section id=\"").append(anchor(tool.key())).append("\"><h2>").append(escape(tool.name())).append("</h2><p>").append(escape(tool.description())).append("</p><p><code>").append(escape(tool.command())).append("</code>; output=").append(escape(tool.output())).append("</p><table><tr><th>Key</th><th>Name</th><th>Description</th><th>Required</th><th>Delimiter</th></tr>");
            for (ToolArgumentConfig arg : tool.arguments().values()) body.append("<tr><td>").append(escape(arg.key())).append("</td><td>").append(escape(arg.name())).append("</td><td>").append(escape(arg.description())).append("</td><td>").append(arg.required()).append("</td><td>").append(escape(arg.delimit())).append("</td></tr>");
            body.append("</table></section>"); search.add(tool.key()); search.add(tool.name()); search.add(tool.description());
        }
        return page("Tools", body.toString());
    }

    private Map<String, Object> yaml(Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file)) {
            Object value = new Yaml().load(reader); Map<String, Object> result = new LinkedHashMap<String, Object>();
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
    private String page(String title, String body) { return "<!doctype html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + escape(title) + "</title><style>*{box-sizing:border-box}body{margin:0;padding:32px;max-width:1280px;margin:auto;background:radial-gradient(circle at top left,#172554,#070a12 42%);color:#e5e7eb;font:14px/1.55 Inter,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;min-height:100vh}h1{font-size:30px}h2{margin-top:32px;color:#bfdbfe}a{color:#60a5fa}nav{position:sticky;top:10px;z-index:5}input{width:min(520px,100%);padding:11px 14px;border-radius:10px;border:1px solid #334155;background:#0f172a;color:white;box-shadow:0 8px 24px #0005}table{border-collapse:collapse;width:100%;background:#0f172acc;border:1px solid #334155;border-radius:12px;overflow:hidden}th,td{border-bottom:1px solid #26334a;padding:10px;text-align:left}th{color:#93c5fd;background:#111c31}section{margin-bottom:2rem}code{color:#a7f3d0}@media(max-width:700px){body{padding:18px}table{font-size:12px}}</style></head><body>" + body + "</body></html>"; }
    private void write(Path path, String value) throws Exception { Files.write(path, value.getBytes(StandardCharsets.UTF_8)); }
    private String anchor(String value) { return HtmlSupport.id(value); }
    private String escape(String value) { return HtmlSupport.escape(value); }
    private String joinEscaped(List<String> values) { StringBuilder out = new StringBuilder(); for (String v : values) { if (out.length() > 0) out.append("\",\""); out.append(v.replace("\\", "\\\\").replace("\"", "\\\"")); } return out.toString(); }
}
