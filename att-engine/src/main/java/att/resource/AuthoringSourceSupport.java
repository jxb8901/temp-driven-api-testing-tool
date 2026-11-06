package att.resource;

import att.security.SecretProtection;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Lossless source response shared by narrowly allowlisted authoring inspectors. */
final class AuthoringSourceSupport {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?im)(?<![A-Za-z0-9_.-])[\"']?([A-Za-z_][A-Za-z0-9_.-]*(?:\\[[0-9]+\\])?)[\"']?\\s*[:=]\\s*(\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\r\\n,;#]+)");
    private static final Pattern YAML_MAPPING = Pattern.compile(
            "(?im)(?<![A-Za-z0-9_.-])[\"']?[A-Za-z_][A-Za-z0-9_.-]*(?:\\[[0-9]+\\])?[\"']?\\s*:");
    private static final Pattern XML_REFERENCE = Pattern.compile("&\\{[^{}\\r\\n]{1,1024}\\}");
    private static final ObjectMapper JSON = new ObjectMapper(jsonFactory())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private AuthoringSourceSupport() { }

    static Map<String,Object> read(Path trustedRoot, Path authoredPath, String logicalPath, int maxBytes) throws Exception {
        return read(trustedRoot,authoredPath,logicalPath,maxBytes,
                Collections.<String>emptyList(),Collections.<String>emptyList());
    }

    static Map<String,Object> read(Path trustedRoot, Path authoredPath, String logicalPath, int maxBytes,
                                   List<String> sensitivePaths,List<String> sensitiveFieldPatterns) throws Exception {
        if(protectedSourcePath(logicalPath))throw new PackageResourceInspector.ResourceNotFoundException();
        Path root=trustedRoot.toRealPath();
        Path lexicalRoot=trustedRoot.toAbsolutePath().normalize();
        Path path=authoredPath.toAbsolutePath().normalize();
        if(!path.startsWith(lexicalRoot)||containsSymlink(lexicalRoot,path)
                ||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().startsWith(root))
            throw new PackageResourceInspector.ResourceNotFoundException();
        long size=Files.size(path);
        if(size>maxBytes)throw new PackageResourceInspector.ResponseTooLargeException();
        byte[] bytes=readBounded(path,maxBytes);
        String text=decodeUtf8(bytes);
        if(text.indexOf('\0')>=0||!SecretProtection.text(text).equals(text)
                ||containsProtectedContent(text,logicalPath,safeList(sensitivePaths),safeList(sensitiveFieldPatterns)))
            throw new PackageResourceInspector.ResourceNotFoundException();
        return result(logicalPath,bytes,text,true);
    }

    static Map<String,Object> absent(String logicalPath) throws Exception {
        if(protectedSourcePath(logicalPath))throw new PackageResourceInspector.ResourceNotFoundException();
        return result(logicalPath,new byte[0],"",false);
    }

    private static Map<String,Object> result(String logicalPath,byte[] bytes,String text,boolean present) throws Exception {
        String revision=hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String etag="\"sha256:"+revision+"\"";
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("available",Boolean.TRUE);result.put("present",Boolean.valueOf(present));
        result.put("logicalPath",logicalPath);result.put("format",format(logicalPath));result.put("text",text);
        result.put("sourceEtag",etag);result.put("revision",revision);
        return result;
    }

    private static byte[] readBounded(Path path,int maxBytes) throws Exception {
        try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int total=0,count;
            while((count=input.read(buffer))>=0) {
                total+=count;if(total>maxBytes)throw new PackageResourceInspector.ResponseTooLargeException();
                output.write(buffer,0,count);
            }
            return output.toByteArray();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder(bytes.length*2);
        for(byte value:bytes)result.append(String.format(Locale.ROOT,"%02x",value&0xff));
        return result.toString();
    }

    private static String decodeUtf8(byte[] bytes) throws Exception {
        try {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid) {throw new PackageResourceInspector.ResourceNotFoundException();}
    }

    private static boolean containsSymlink(Path root,Path path) {
        Path current=root;
        for(Path part:root.relativize(path)) {
            current=current.resolve(part);
            if(Files.isSymbolicLink(current))return true;
        }
        return false;
    }

    private static String format(String path) {
        String lower=path.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".yaml")||lower.endsWith(".yml"))return "yaml";
        if(lower.endsWith(".json"))return "json";
        if(lower.endsWith(".xml"))return "xml";
        if(lower.endsWith(".sql"))return "sql";
        return "text";
    }

    private static boolean containsProtectedContent(String text,String logicalPath,List<String> sensitivePaths,
                                                    List<String> sensitiveFieldPatterns) {
        if(matchesConfiguredPath(logicalPath,sensitivePaths))return true;
        String lower=logicalPath.toLowerCase(Locale.ROOT);
        try {
            if(lower.endsWith(".yaml")||lower.endsWith(".yml"))return protectedYaml(text,sensitivePaths,sensitiveFieldPatterns);
            if(lower.endsWith(".json")) {
                Object parsed=JSON.readValue(text,Object.class);
                return protectedValue(parsed,"",sensitivePaths,sensitiveFieldPatterns,new IdentityHashMap<Object,Boolean>(),0);
            }
            if(lower.endsWith(".xml"))return protectedXml(text,sensitivePaths,sensitiveFieldPatterns,0);
            if(lower.endsWith(".csv"))return protectedCsv(text,sensitivePaths,sensitiveFieldPatterns,0);
            return protectedText(text,sensitivePaths,sensitiveFieldPatterns);
        } catch(Exception malformedOrUnknown) {
            return true;
        }
    }

    private static boolean protectedYaml(String text,List<String> sensitivePaths,List<String> sensitiveFieldPatterns) {
        LoaderOptions options=new LoaderOptions();
        options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(50);options.setCodePointLimit(3_000_000);
        Iterable<Object> documents=new Yaml(new SafeConstructor(options)).loadAll(new StringReader(text));
        int count=0;
        for(Object document:documents) {
            if(++count>64||protectedValue(document,"",sensitivePaths,sensitiveFieldPatterns,
                    new IdentityHashMap<Object,Boolean>(),0))return true;
        }
        return false;
    }

    private static boolean protectedValue(Object value,String path,List<String> sensitivePaths,
                                          List<String> sensitiveFieldPatterns,IdentityHashMap<Object,Boolean> active,
                                          int depth) {
        if(depth>64)return true;
        if(value instanceof CharSequence)return protectedScalar(value.toString(),sensitivePaths,sensitiveFieldPatterns,depth);
        if(value instanceof Map<?,?>) {
            if(active.put(value,Boolean.TRUE)!=null)return true;
            try {
                Map<?,?> map=(Map<?,?>)value;
                for(Map.Entry<?,?> entry:map.entrySet()) {
                    if(!(entry.getKey() instanceof String))return true;
                    String key=(String)entry.getKey();Object child=entry.getValue();
                    if("classification".equalsIgnoreCase(key)&&protectedClassification(child))return true;
                    if("secret".equalsIgnoreCase(key)&&Boolean.TRUE.equals(child))return true;
                }
                for(Map.Entry<?,?> entry:map.entrySet()) {
                    String key=(String)entry.getKey();Object child=entry.getValue();
                    String childPath=appendPath(path,key);
                    if((SecretProtection.sensitiveName(key)||matchesConfiguredField(key,sensitiveFieldPatterns)
                            ||matchesConfiguredPath(childPath,sensitivePaths))&&hasValue(child))return true;
                    if(protectedValue(child,childPath,sensitivePaths,sensitiveFieldPatterns,active,depth+1))return true;
                }
                return false;
            } finally {active.remove(value);}
        }
        if(value instanceof Iterable<?>) {
            int index=0;
            for(Object child:(Iterable<?>)value) {
                if(protectedValue(child,path+"["+(index++)+"]",sensitivePaths,sensitiveFieldPatterns,active,depth+1))return true;
            }
        }
        if(value!=null&&value.getClass().isArray()) {
            int length=java.lang.reflect.Array.getLength(value);
            for(int index=0;index<length;index++)if(protectedValue(java.lang.reflect.Array.get(value,index),
                    path+"["+index+"]",sensitivePaths,sensitiveFieldPatterns,active,depth+1))return true;
        }
        return false;
    }

    private static boolean protectedScalar(String text,List<String> sensitivePaths,List<String> sensitiveFieldPatterns,
                                           int depth) {
        if(depth>64)return true;
        if(protectedAssignments(text,sensitivePaths,sensitiveFieldPatterns))return true;
        String candidate=text==null?"":text.trim();
        if(candidate.isEmpty())return false;
        String unescaped=candidate.replace("\\\"", "\"");
        if(!unescaped.equals(candidate)&&looksLikeEmbeddedStructure(unescaped)
                &&protectedScalar(unescaped,sensitivePaths,sensitiveFieldPatterns,depth+1))return true;
        if(!looksLikeEmbeddedStructure(candidate))return false;
        try {
            if(looksLikeXml(candidate))return protectedXml(candidate,sensitivePaths,sensitiveFieldPatterns,depth+1);
            if(looksLikeCsv(candidate)&&protectedCsv(candidate,sensitivePaths,sensitiveFieldPatterns,depth+1))return true;
            LoaderOptions options=new LoaderOptions();
            options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(50);options.setCodePointLimit(3_000_000);
            Iterable<Object> documents=new Yaml(new SafeConstructor(options)).loadAll(new StringReader(candidate));
            int count=0;
            for(Object document:documents) {
                if(++count>64)return true;
                if(document instanceof String) {
                    String nested=(String)document;
                    if(!nested.equals(candidate)&&protectedScalar(nested,sensitivePaths,sensitiveFieldPatterns,depth+1))return true;
                } else if(protectedValue(document,"",sensitivePaths,sensitiveFieldPatterns,
                        new IdentityHashMap<Object,Boolean>(),depth+1))return true;
            }
            return false;
        } catch(Exception malformedOrUnknown) {
            return true;
        }
    }

    private static boolean protectedAssignments(String text,List<String> sensitivePaths,List<String> sensitiveFieldPatterns) {
        Matcher matcher=ASSIGNMENT.matcher(text==null?"":text);
        while(matcher.find()) {
            String key=matcher.group(1);String value=unquote(matcher.group(2));
            if("classification".equalsIgnoreCase(key)&&protectedClassification(value))return true;
            if("secret".equalsIgnoreCase(key)&&"true".equalsIgnoreCase(value.trim()))return true;
            if((SecretProtection.sensitiveName(key)||matchesConfiguredField(key,sensitiveFieldPatterns)
                    ||matchesConfiguredPath(key,sensitivePaths))&&hasText(value))return true;
        }
        return false;
    }

    private static boolean looksLikeEmbeddedStructure(String text) {
        String candidate=text==null?"":text.trim();
        if(candidate.isEmpty())return false;
        if(candidate.charAt(0)=='{'||candidate.charAt(0)=='['||candidate.charAt(0)=='<')return true;
        if(candidate.charAt(0)=='\''||candidate.charAt(0)=='"')return true;
        return YAML_MAPPING.matcher(candidate).find();
    }

    private static boolean looksLikeXml(String text) {
        String candidate=text.trim();
        return candidate.startsWith("<")&&candidate.length()>1
                &&(candidate.charAt(1)=='?'||candidate.charAt(1)=='!'||Character.isLetter(candidate.charAt(1)));
    }

    private static boolean looksLikeCsv(String text) {
        return text.indexOf(',')>=0&&(text.indexOf('\n')>=0||text.indexOf('\r')>=0);
    }

    private static boolean protectedXml(String text,List<String> sensitivePaths,List<String> sensitiveFieldPatterns,
                                        int depth) throws Exception {
        if(depth>64)return true;
        String parseable=XML_REFERENCE.matcher(text).replaceAll("ATT_REFERENCE");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        javax.xml.parsers.DocumentBuilder builder=factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(SAXParseException failure) throws SAXException {throw failure;}
            @Override public void fatalError(SAXParseException failure) throws SAXException {throw failure;}
        });
        Document document=builder.parse(new InputSource(new StringReader(parseable)));
        return protectedXmlNode(document.getDocumentElement(),"",sensitivePaths,sensitiveFieldPatterns,depth);
    }

    private static boolean protectedXmlNode(Node node,String parentPath,List<String> sensitivePaths,
                                            List<String> sensitiveFieldPatterns,int depth) {
        if(depth>64)return true;
        String name=node.getLocalName()==null?node.getNodeName():node.getLocalName();
        if(name==null||name.isEmpty())return true;
        String path=appendPath(parentPath,name);
        if("classification".equalsIgnoreCase(name)&&protectedClassification(node.getTextContent()))return true;
        if((SecretProtection.sensitiveName(name)||matchesConfiguredField(name,sensitiveFieldPatterns)
                ||matchesConfiguredPath(path,sensitivePaths))&&hasText(node.getTextContent()))return true;
        if(protectedScalar(node.getTextContent(),sensitivePaths,sensitiveFieldPatterns,depth+1))return true;
        NamedNodeMap attributes=node.getAttributes();
        if(attributes!=null)for(int index=0;index<attributes.getLength();index++) {
            Node attribute=attributes.item(index);String key=attribute.getLocalName()==null?attribute.getNodeName():attribute.getLocalName();
            String value=attribute.getNodeValue();
            if("classification".equalsIgnoreCase(key)&&protectedClassification(value))return true;
            if("secret".equalsIgnoreCase(key)&&"true".equalsIgnoreCase(value==null?"":value.trim()))return true;
            if((SecretProtection.sensitiveName(key)||matchesConfiguredField(key,sensitiveFieldPatterns)
                    ||matchesConfiguredPath(appendPath(path,"@"+key),sensitivePaths))&&hasText(value))return true;
            if(protectedScalar(value,sensitivePaths,sensitiveFieldPatterns,depth+1))return true;
        }
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling()) {
            if(child.getNodeType()==Node.ELEMENT_NODE
                    &&protectedXmlNode(child,path,sensitivePaths,sensitiveFieldPatterns,depth+1))return true;
            if((child.getNodeType()==Node.TEXT_NODE||child.getNodeType()==Node.CDATA_SECTION_NODE)
                    &&protectedScalar(child.getNodeValue(),sensitivePaths,sensitiveFieldPatterns,depth+1))return true;
        }
        return false;
    }

    private static boolean protectedCsv(String text,List<String> sensitivePaths,List<String> sensitiveFieldPatterns,int depth) {
        if(depth>64)return true;
        List<List<String>> rows=parseCsv(text);
        if(rows.isEmpty())return false;
        List<String> header=rows.get(0);
        for(int column=0;column<header.size();column++) {
            String key=header.get(column).trim();
            boolean sensitive="classification".equalsIgnoreCase(key)||SecretProtection.sensitiveName(key)
                    ||matchesConfiguredField(key,sensitiveFieldPatterns)||matchesConfiguredPath(key,sensitivePaths);
            if(!sensitive)continue;
            for(int row=1;row<rows.size();row++)if(column<rows.get(row).size()) {
                String value=rows.get(row).get(column);
                if("classification".equalsIgnoreCase(key)?protectedClassification(value):hasText(value))return true;
            }
        }
        for(int row=1;row<rows.size();row++)for(String value:rows.get(row))
            if(protectedScalar(value,sensitivePaths,sensitiveFieldPatterns,depth+1))return true;
        return false;
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows=new ArrayList<List<String>>();List<String> row=new ArrayList<String>();
        StringBuilder field=new StringBuilder();boolean quoted=false;
        for(int index=0;index<text.length();index++) {
            char value=text.charAt(index);
            if(value=='"') {
                if(quoted&&index+1<text.length()&&text.charAt(index+1)=='"'){field.append('"');index++;}
                else quoted=!quoted;
            } else if(!quoted&&(value==','||value=='\r'||value=='\n')) {
                row.add(field.toString());field.setLength(0);
                if(value!=','){rows.add(row);row=new ArrayList<String>();if(value=='\r'&&index+1<text.length()&&text.charAt(index+1)=='\n')index++;}
            } else field.append(value);
        }
        if(quoted)throw new IllegalArgumentException("Unclosed CSV field");
        if(field.length()>0||!row.isEmpty()){row.add(field.toString());rows.add(row);}
        return rows;
    }

    private static boolean protectedText(String text,List<String> sensitivePaths,List<String> sensitiveFieldPatterns) {
        return protectedScalar(text,sensitivePaths,sensitiveFieldPatterns,0);
    }

    private static boolean protectedClassification(Object value) {
        if(!(value instanceof CharSequence))return false;
        String label=value.toString().trim().toLowerCase(Locale.ROOT).replace('_','-');
        String[] protectedLabels={"secret","credential","credentials","private-key","private","confidential","restricted"};
        for(String protectedLabel:protectedLabels) {
            if(protectedLabel.equals(label))return true;
            if(label.startsWith(protectedLabel)&&label.length()>protectedLabel.length()) {
                char boundary=label.charAt(protectedLabel.length());
                if(Character.isWhitespace(boundary)||boundary==':'||boundary==','||boundary==';')return true;
            }
        }
        return false;
    }

    private static boolean matchesConfiguredField(String key,List<String> expressions) {
        for(String expression:expressions)try {
            if(expression==null)return true;
            if(Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(key).matches())return true;
        } catch(RuntimeException invalidPolicy) {return true;}
        return false;
    }

    private static boolean matchesConfiguredPath(String path,List<String> configuredPaths) {
        String normalized=path.toLowerCase(Locale.ROOT);
        for(String configured:configuredPaths) {
            if(configured==null)return true;
            String candidate=configured.toLowerCase(Locale.ROOT);
            if(candidate.equals(normalized)||candidate.endsWith("."+normalized))return true;
            if(candidate.indexOf('*')>=0||candidate.indexOf('?')>=0) {
                int suffixStart=0;
                while(suffixStart<candidate.length()) {
                    if(pathGlobMatches(candidate.substring(suffixStart),normalized))return true;
                    int separator=candidate.indexOf('.',suffixStart);
                    if(separator<0)break;
                    suffixStart=separator+1;
                }
            }
        }
        return false;
    }

    private static boolean pathGlobMatches(String glob,String path) {
        StringBuilder regex=new StringBuilder("^");
        for(int index=0;index<glob.length();index++) {
            char value=glob.charAt(index);
            if(value=='*')regex.append(".*");
            else if(value=='?')regex.append('.');
            else {
                if(".(){}+$^|[]\\".indexOf(value)>=0)regex.append('\\');
                regex.append(value);
            }
        }
        return Pattern.compile(regex.append('$').toString()).matcher(path).matches();
    }

    private static boolean hasValue(Object value) {
        if(value==null)return false;
        if(value instanceof CharSequence)return hasText(value.toString());
        if(value instanceof Boolean)return ((Boolean)value).booleanValue();
        return true;
    }

    private static boolean hasText(String value) {return value!=null&&!value.trim().isEmpty();}
    private static String appendPath(String parent,String child) {return parent==null||parent.isEmpty()?child:parent+"."+child;}
    private static String unquote(String value) {
        if(value!=null&&value.length()>=2&&((value.charAt(0)=='\"'&&value.charAt(value.length()-1)=='\"')
                ||(value.charAt(0)=='\''&&value.charAt(value.length()-1)=='\'')))return value.substring(1,value.length()-1);
        return value==null?"":value.trim();
    }
    private static List<String> safeList(List<String> value) {return value==null?Collections.<String>emptyList():value;}
    private static JsonFactory jsonFactory() {
        JsonFactory factory=new JsonFactory();factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);return factory;
    }

    private static boolean protectedSourcePath(String logicalPath) {
        if(logicalPath==null)return true;
        String normalized=logicalPath.replace('\\','/').toLowerCase(Locale.ROOT);
        for(String segment:normalized.split("/")) {
            if(segment.startsWith("att-server")||segment.startsWith(".env")||segment.contains("secret")
                    ||segment.contains("credential")||segment.contains("password")||segment.contains("token")
                    ||segment.endsWith(".pem")||segment.endsWith(".key")||segment.endsWith(".p12")
                    ||segment.endsWith(".pfx")||segment.endsWith(".jks")||segment.endsWith(".keystore"))return true;
        }
        return false;
    }
}
