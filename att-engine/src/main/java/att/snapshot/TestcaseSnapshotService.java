/* Author: Jeffrey + ChatGPT */
package att.snapshot;

import att.config.DataColumnConfig;
import att.config.FrameworkConfig;
import att.config.SheetGroupConfig;
import att.config.StageConfig;
import att.core.StageCaseData;
import att.core.TestCase;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds, writes, and verifies the canonical V2.4 semantic testcase XML snapshot. */
public final class TestcaseSnapshotService {
    public static final String SCHEMA_VERSION = att.Version.TESTCASE_SNAPSHOT_SCHEMA;

    public Path snapshotPath(Path workbook) {
        String name = workbook.getFileName().toString();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("Snapshot source must be an .xlsx workbook: " + workbook);
        return workbook.resolveSibling(name.substring(0, name.length() - 5) + ".xml");
    }

    public Map<String, Object> build(FrameworkConfig config, List<TestCase> cases) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("workbookId", config.workbookId());
        List<Object> groups = new ArrayList<Object>();
        for (SheetGroupConfig groupConfig : config.sheetGroups()) {
            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("id", groupConfig.id());
            group.put("sheet", groupConfig.sheetName());
            List<Object> groupCases = new ArrayList<Object>();
            for (TestCase testCase : cases) {
                if (!groupConfig.id().equals(testCase.groupId())) continue;
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("rowCaseId", testCase.rowCaseId());
                item.put("tags", canonicalValue(testCase.tags(), "tags"));
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                for (DataColumnConfig column : config.dataColumns()) data.put(column.key(), canonicalValue(testCase.caseData().get(column.key()), "data." + column.key()));
                item.put("data", data);
                Map<String, Object> stages = new LinkedHashMap<String, Object>();
                for (StageConfig stageConfig : config.stages()) {
                    StageCaseData stage = testCase.stage(stageConfig.key());
                    if (stage != null) stages.put(stageConfig.key(), canonicalValue(stage.values(), "stages." + stageConfig.key()));
                }
                item.put("stages", stages);
                groupCases.add(item);
            }
            group.put("cases", groupCases);
            groups.add(group);
        }
        root.put("groups", groups);
        return root;
    }

    @SuppressWarnings("unchecked")
    public String serialize(Map<String, Object> snapshot) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<testcases schemaVersion=\"").append(attribute(String.valueOf(snapshot.get("schemaVersion"))))
                .append("\" workbookId=\"").append(attribute(String.valueOf(snapshot.get("workbookId")))).append("\">\n");
        for (Map<String, Object> group : (List<Map<String, Object>>) (List<?>) snapshot.get("groups")) {
            spaces(out, 2).append("<group id=\"").append(attribute(String.valueOf(group.get("id"))))
                    .append("\" sheet=\"").append(attribute(String.valueOf(group.get("sheet")))).append("\">\n");
            for (Map<String, Object> testCase : (List<Map<String, Object>>) (List<?>) group.get("cases")) {
                spaces(out, 4).append("<case rowCaseId=\"").append(attribute(String.valueOf(testCase.get("rowCaseId")))).append("\">\n");
                spaces(out, 6).append("<tags>\n");
                for (Object tag : (List<Object>) testCase.get("tags")) spaces(out, 8).append("<tag>").append(content(String.valueOf(tag))).append("</tag>\n");
                spaces(out, 6).append("</tags>\n");
                spaces(out, 6).append("<data>\n");
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) testCase.get("data")).entrySet()) writeValue(out, "field", "name", entry.getKey(), entry.getValue(), 8);
                spaces(out, 6).append("</data>\n");
                spaces(out, 6).append("<stages>\n");
                for (Map.Entry<String, Object> stage : ((Map<String, Object>) testCase.get("stages")).entrySet()) {
                    spaces(out, 8).append("<stage key=\"").append(attribute(stage.getKey())).append("\">\n");
                    for (Map.Entry<String, Object> value : ((Map<String, Object>) stage.getValue()).entrySet()) writeValue(out, "field", "name", value.getKey(), value.getValue(), 10);
                    spaces(out, 8).append("</stage>\n");
                }
                spaces(out, 6).append("</stages>\n");
                spaces(out, 4).append("</case>\n");
            }
            spaces(out, 2).append("</group>\n");
        }
        return out.append("</testcases>\n").toString();
    }

    public Path write(Path workbook, FrameworkConfig config, List<TestCase> cases) throws Exception {
        return write(workbook, build(config, cases));
    }

    public Path write(Path workbook, Map<String, Object> snapshot) throws Exception {
        Path target = snapshotPath(workbook);
        byte[] content = serialize(snapshot).getBytes(StandardCharsets.UTF_8);
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IllegalArgumentException("Snapshot directory does not exist: " + target);
        Path temporary = Files.createTempFile(parent, "." + target.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temporary, content);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return target;
    }

    public void verify(Path workbook, FrameworkConfig config, List<TestCase> cases) {
        Path snapshot = snapshotPath(workbook);
        if (!Files.isRegularFile(snapshot) || Files.isSymbolicLink(snapshot)) throw failure("Testcase snapshot is missing", "Expected " + snapshot.getFileName(), snapshot, null);
        try {
            String source = decodeUtf8(Files.readAllBytes(snapshot));
            Map<String, Object> tracked = parse(source);
            if (!serialize(tracked).equals(source)) throw failure("Testcase snapshot is not canonical", "The file contains non-canonical formatting, element ordering, or line endings.", snapshot, null);
            Map<String, Object> current = build(config, cases);
            if (!Objects.equals(tracked, current)) throw failure("Testcase snapshot is stale", String.join("; ", differences(tracked, current)), snapshot, null);
        } catch (DiagnosticException e) { throw e; }
        catch (Exception e) { throw failure("Testcase snapshot is invalid", message(e), snapshot, e); }
    }

    public Map<String, Object> parse(String source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("External XML entities are disabled"); });
        builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
            public void warning(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
        });
        Document document = builder.parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        Element rootElement = document.getDocumentElement();
        requireName(rootElement, "testcases");
        requireAttributes(rootElement, "schemaVersion", "workbookId");
        if (!SCHEMA_VERSION.equals(rootElement.getAttribute("schemaVersion"))) throw new IllegalArgumentException("snapshot schemaVersion must be exactly " + SCHEMA_VERSION);
        String workbookId = requiredAttribute(rootElement, "workbookId");
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("workbookId", workbookId);
        List<Object> groups = new ArrayList<Object>();
        Set<String> groupIds = new LinkedHashSet<String>();
        for (Element groupElement : children(rootElement)) {
            requireName(groupElement, "group"); requireAttributes(groupElement, "id", "sheet");
            String groupId = requiredAttribute(groupElement, "id");
            if (!groupIds.add(groupId)) throw new IllegalArgumentException("Duplicate snapshot group id: " + groupId);
            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("id", groupId); group.put("sheet", requiredAttribute(groupElement, "sheet"));
            List<Object> cases = new ArrayList<Object>(); Set<String> caseIds = new LinkedHashSet<String>();
            for (Element caseElement : children(groupElement)) {
                requireName(caseElement, "case"); requireAttributes(caseElement, "rowCaseId");
                String rowCaseId = requiredAttribute(caseElement, "rowCaseId");
                if (!caseIds.add(rowCaseId)) throw new IllegalArgumentException("Duplicate snapshot Case ID in group " + groupId + ": " + rowCaseId);
                List<Element> sections = children(caseElement);
                if (sections.size() != 3) throw new IllegalArgumentException("Case " + groupId + "." + rowCaseId + " requires tags, data, and stages");
                requireName(sections.get(0), "tags"); requireAttributes(sections.get(0));
                requireName(sections.get(1), "data"); requireAttributes(sections.get(1));
                requireName(sections.get(2), "stages"); requireAttributes(sections.get(2));
                Map<String, Object> item = new LinkedHashMap<String, Object>(); item.put("rowCaseId", rowCaseId);
                List<Object> tags = new ArrayList<Object>();
                for (Element tag : children(sections.get(0))) { requireName(tag, "tag"); requireAttributes(tag); requireNoElementChildren(tag); tags.add(tag.getTextContent()); }
                item.put("tags", tags);
                item.put("data", parseFields(sections.get(1), "field", "name"));
                Map<String, Object> stages = new LinkedHashMap<String, Object>();
                for (Element stage : children(sections.get(2))) {
                    requireName(stage, "stage"); requireAttributes(stage, "key");
                    String key = requiredAttribute(stage, "key");
                    if (stages.put(key, parseFields(stage, "field", "name")) != null) throw new IllegalArgumentException("Duplicate stage key: " + key);
                }
                item.put("stages", stages); cases.add(item);
            }
            group.put("cases", cases); groups.add(group);
        }
        root.put("groups", groups);
        return root;
    }

    private Map<String, Object> parseFields(Element parent, String elementName, String nameAttribute) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Element field : children(parent)) {
            requireName(field, elementName); requireAttributes(field, nameAttribute, "type");
            String name = requiredAttribute(field, nameAttribute);
            if (result.put(name, parseValue(field)) != null) throw new IllegalArgumentException("Duplicate field/entry name: " + name);
        }
        return result;
    }

    private Object parseValue(Element element) {
        String type = requiredAttribute(element, "type");
        if ("map".equals(type)) return parseFields(element, "entry", "name");
        if ("list".equals(type)) {
            List<Object> result = new ArrayList<Object>();
            for (Element item : children(element)) { requireName(item, "item"); requireAttributes(item, "type"); result.add(parseValue(item)); }
            return result;
        }
        requireNoElementChildren(element);
        String text = element.getTextContent();
        if ("string".equals(type)) return text;
        if ("integer".equals(type)) return new BigInteger(text);
        if ("decimal".equals(type)) return new BigDecimal(text);
        if ("boolean".equals(type) && ("true".equals(text) || "false".equals(text))) return Boolean.valueOf(text);
        if ("null".equals(type) && text.isEmpty()) return null;
        throw new IllegalArgumentException("Invalid snapshot value type/content: " + type);
    }

    @SuppressWarnings("unchecked")
    private void writeValue(StringBuilder out, String tag, String nameAttribute, String name, Object value, int indent) {
        String type = type(value);
        spaces(out, indent).append('<').append(tag);
        if (nameAttribute != null) out.append(' ').append(nameAttribute).append("=\"").append(attribute(name)).append('"');
        out.append(" type=\"").append(type).append('"');
        if (value == null) { out.append("/>\n"); return; }
        if (value instanceof Map) {
            out.append(">\n");
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) writeValue(out, "entry", "name", entry.getKey(), entry.getValue(), indent + 2);
            spaces(out, indent).append("</").append(tag).append(">\n"); return;
        }
        if (value instanceof List) {
            out.append(">\n");
            for (Object item : (List<Object>) value) writeValue(out, "item", null, null, item, indent + 2);
            spaces(out, indent).append("</").append(tag).append(">\n"); return;
        }
        out.append('>').append(value instanceof String ? content((String) value) : text(scalar(value))).append("</").append(tag).append(">\n");
    }

    private Object canonicalValue(Object value, String owner) {
        if (value == null || value instanceof Boolean || value instanceof BigInteger || value instanceof BigDecimal) return value;
        if (value instanceof String) return ((String) value).replace("\r\n", "\n").replace('\r', '\n');
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return new BigInteger(value.toString());
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) throw new IllegalArgumentException(owner + " contains a non-finite number");
            return BigDecimal.valueOf(number);
        }
        if (value instanceof Date) return java.time.Instant.ofEpochMilli(((Date) value).getTime()).toString();
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) { String key = String.valueOf(entry.getKey()); result.put(key, canonicalValue(entry.getValue(), owner + "." + key)); }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>(); int index = 0;
            for (Object item : (Iterable<?>) value) result.add(canonicalValue(item, owner + "[" + index++ + "]"));
            return result;
        }
        throw new IllegalArgumentException(owner + " contains unsupported YAML value type " + value.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private List<String> differences(Map<String, Object> tracked, Map<String, Object> current) {
        List<String> result = new ArrayList<String>();
        if (!Objects.equals(tracked.get("workbookId"), current.get("workbookId"))) result.add("workbookId changed");
        List<Map<String, Object>> oldGroups = (List<Map<String, Object>>) (List<?>) tracked.get("groups"), newGroups = (List<Map<String, Object>>) (List<?>) current.get("groups");
        if (!ids(oldGroups, "id").equals(ids(newGroups, "id"))) result.add("group order/selection changed from " + ids(oldGroups, "id") + " to " + ids(newGroups, "id"));
        Map<String, Map<String, Object>> oldByGroup = index(oldGroups, "id"), newByGroup = index(newGroups, "id");
        for (String id : union(oldByGroup.keySet(), newByGroup.keySet())) {
            Map<String, Object> oldGroup = oldByGroup.get(id), newGroup = newByGroup.get(id);
            if (oldGroup == null) { add(result, "group " + id + " added"); continue; }
            if (newGroup == null) { add(result, "group " + id + " removed"); continue; }
            if (!Objects.equals(oldGroup.get("sheet"), newGroup.get("sheet"))) add(result, "group " + id + ".sheet changed");
            List<Map<String, Object>> oldCases = (List<Map<String, Object>>) (List<?>) oldGroup.get("cases"), newCases = (List<Map<String, Object>>) (List<?>) newGroup.get("cases");
            if (!ids(oldCases, "rowCaseId").equals(ids(newCases, "rowCaseId"))) add(result, "group " + id + " Case order/selection changed from " + ids(oldCases, "rowCaseId") + " to " + ids(newCases, "rowCaseId"));
            Map<String, Map<String, Object>> oldByCase = index(oldCases, "rowCaseId"), newByCase = index(newCases, "rowCaseId");
            for (String caseId : union(oldByCase.keySet(), newByCase.keySet())) {
                Map<String, Object> oldCase = oldByCase.get(caseId), newCase = newByCase.get(caseId); String path = id + "." + caseId;
                if (oldCase == null) { add(result, path + " added"); continue; }
                if (newCase == null) { add(result, path + " removed"); continue; }
                compareValue(oldCase.get("tags"), newCase.get("tags"), path + ".tags", result);
                compareValue(oldCase.get("data"), newCase.get("data"), path + ".data", result);
                compareValue(oldCase.get("stages"), newCase.get("stages"), path + ".stages", result);
            }
        }
        if (result.isEmpty()) result.add("snapshot content differs"); return result;
    }

    @SuppressWarnings("unchecked")
    private void compareValue(Object oldValue, Object newValue, String path, List<String> result) {
        if (Objects.equals(oldValue, newValue) || result.size() >= 20) return;
        if (oldValue instanceof Map && newValue instanceof Map) {
            Map<String, Object> left = (Map<String, Object>) oldValue, right = (Map<String, Object>) newValue;
            for (String key : union(left.keySet(), right.keySet())) {
                if (!left.containsKey(key)) add(result, path + "." + key + " added");
                else if (!right.containsKey(key)) add(result, path + "." + key + " removed");
                else compareValue(left.get(key), right.get(key), path + "." + key, result);
            }
        } else add(result, path + " changed");
    }

    private String type(Object value) {
        if (value == null) return "null"; if (value instanceof String) return "string"; if (value instanceof Boolean) return "boolean";
        if (value instanceof BigInteger) return "integer"; if (value instanceof BigDecimal) return "decimal"; if (value instanceof Map) return "map"; if (value instanceof List) return "list";
        throw new IllegalArgumentException("Unsupported snapshot value: " + value.getClass().getName());
    }
    private String scalar(Object value) { return value instanceof BigDecimal ? ((BigDecimal) value).toPlainString() : String.valueOf(value); }
    private List<Element> children(Element parent) { List<Element> result = new ArrayList<Element>(); for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element) result.add((Element) node); return result; }
    private void requireNoElementChildren(Element element) { if (!children(element).isEmpty()) throw new IllegalArgumentException(element.getTagName() + " must contain scalar text only"); }
    private void requireName(Element element, String expected) { if (!expected.equals(element.getTagName()) || element.getNamespaceURI() != null) throw new IllegalArgumentException("Expected <" + expected + "> but found <" + element.getTagName() + ">"); }
    private void requireAttributes(Element element, String... names) { Set<String> expected = new LinkedHashSet<String>(Arrays.asList(names)); if (element.getAttributes().getLength() != expected.size()) throw new IllegalArgumentException("Unexpected attributes on " + element.getTagName()); for (String name : expected) if (!element.hasAttribute(name)) throw new IllegalArgumentException(element.getTagName() + " requires attribute " + name); }
    private String requiredAttribute(Element element, String name) { String value = element.getAttribute(name); if (value.isEmpty()) throw new IllegalArgumentException(element.getTagName() + "." + name + " must be non-blank"); return value; }
    private List<String> ids(List<Map<String, Object>> items, String key) { List<String> result = new ArrayList<String>(); for (Map<String, Object> item : items) result.add(String.valueOf(item.get(key))); return result; }
    private Map<String, Map<String, Object>> index(List<Map<String, Object>> items, String key) { Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>(); for (Map<String, Object> item : items) result.put(String.valueOf(item.get(key)), item); return result; }
    private Set<String> union(Set<String> left, Set<String> right) { Set<String> result = new LinkedHashSet<String>(left); result.addAll(right); return result; }
    private void add(List<String> result, String value) { if (result.size() < 20) result.add(value); }
    private String text(String value) { validateXml(value); return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private String content(String value) {
        validateXml(value);
        if (value.indexOf('\n') < 0 && value.indexOf('&') < 0 && value.indexOf('<') < 0 && value.indexOf('>') < 0) return text(value);
        StringBuilder output = new StringBuilder(), chunk = new StringBuilder();
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t') {
                int end = i;
                while (end < value.length() && (value.charAt(end) == ' ' || value.charAt(end) == '\t')) end++;
                if (end < value.length() && value.charAt(end) == '\n') {
                    appendCdata(output, chunk);
                    for (int whitespace = i; whitespace < end; whitespace++) output.append(value.charAt(whitespace) == ' ' ? "&#32;" : "&#9;");
                    output.append('\n');
                    i = end + 1;
                    continue;
                }
            }
            chunk.append(c); i++;
        }
        appendCdata(output, chunk);
        return output.toString();
    }
    private void appendCdata(StringBuilder output, StringBuilder chunk) { if (chunk.length() > 0) { output.append("<![CDATA[").append(chunk.toString().replace("]]>", "]]]]><![CDATA[>")).append("]]>"); chunk.setLength(0); } }
    private String attribute(String value) { return text(value).replace("\"", "&quot;").replace("'", "&apos;").replace("\t", "&#9;").replace("\n", "&#10;").replace("\r", "&#13;"); }
    private void validateXml(String value) { for (int i = 0; i < value.length();) { int c = value.codePointAt(i); if (!(c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0x10FFFF))) throw new IllegalArgumentException("Snapshot string contains a character forbidden by XML 1.0"); i += Character.charCount(c); } }
    private StringBuilder spaces(StringBuilder output, int count) { for (int i = 0; i < count; i++) output.append(' '); return output; }
    private String decodeUtf8(byte[] bytes) throws CharacterCodingException { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
    private DiagnosticException failure(String summary, String detail, Path snapshot, Throwable cause) { String workbook = snapshot.getFileName().toString().replaceFirst("(?i)\\.xml$", ".xlsx"); return new DiagnosticException(DiagnosticCodes.TESTCASE_INVALID, summary, detail, snapshot.toString(), "snapshot", null, null, null, null, null, "Run './att.sh snapshot --suite " + workbook + "' and review the generated XML diff.", cause); }
    private String message(Exception e) { String value = e.getMessage(); return value == null || value.trim().isEmpty() ? e.getClass().getSimpleName() : value; }
}
