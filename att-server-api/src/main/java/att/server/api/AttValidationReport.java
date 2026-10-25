package att.server.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable, hierarchical read-only validation report shared by Local and Server validation. */
public final class AttValidationReport {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private final String scope, backend;
    private final List<Section> sections=new ArrayList<Section>();
    private final Map<String,Object> context=new LinkedHashMap<String,Object>();
    private boolean complete=true;
    private Integer forcedExitCode;

    public AttValidationReport(String scope,String backend) {
        this.scope=required(scope,"scope");this.backend=required(backend,"backend");
    }
    public Section addSection(String name,String source,String status) {
        Section section=new Section(name,source,status);sections.add(section);return section;
    }
    public void context(String name,Object value) {
        if(name==null||name.trim().isEmpty())throw new IllegalArgumentException("context name is required");
        if(value!=null)context.put(name,value);
    }
    /** Keeps aggregate CLI reports bounded and makes any omitted section explicit. */
    public void limitOutputBytes(int maximum) {
        int limit=Math.max(768,maximum);
        if(jsonBytes()<=limit)return;
        complete=false;forcedExitCode=Integer.valueOf(3);context.put("reportTruncated",Boolean.TRUE);
        while(!sections.isEmpty()&&jsonBytes()>limit-512)sections.remove(sections.size()-1);
        Section marker=addSection("Report Output Limit",null,"WARN");
        Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();diagnostic.put("code","ATT-VALIDATION-OUTPUT-LIMIT");
        diagnostic.put("severity","WARNING");diagnostic.put("field","report");
        diagnostic.put("message","Report details were truncated; validation is incomplete");
        marker.diagnostic(diagnostic);
        while(jsonBytes()>limit&&sections.size()>1)sections.remove(sections.size()-2);
        if(jsonBytes()>limit) {
            sections.clear();context.clear();context.put("reportTruncated",Boolean.TRUE);
            marker=addSection("Report Output Limit",null,"WARN");marker.diagnostic(diagnostic);
        }
    }
    public void appendSections(AttValidationReport other) {
        if(other==null)return;
        for(Section section:other.sections)addCopied(sections,section.toMap());
        if(other.exitCode()==2)exitCode(2);else if(other.exitCode()==3)incomplete();
    }
    public void incomplete() { complete=false; forcedExitCode=Integer.valueOf(3); }
    public void exitCode(int code) { if(code<0||code>4)throw new IllegalArgumentException("Unsupported validation exit code");forcedExitCode=code; }
    public List<Section> sections() { return Collections.unmodifiableList(sections); }
    public int exitCode() { if(hasFailures())return 2;return forcedExitCode==null?0:forcedExitCode.intValue(); }
    public boolean hasFailures() { for(Section section:sections)if(section.hasFailures())return true;return false; }
    public String overallStatus() { return hasFailures()?"INVALID":complete?"PASS":"PARTIAL"; }
    public Map<String,Object> toMap() {
        Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("schemaVersion","att-validation/v1");out.put("scope",scope);out.put("backend",backend);
        List<Map<String,Object>> values=new ArrayList<Map<String,Object>>();for(Section section:sections)values.add(section.toMap());out.put("sections",values);
        if(!context.isEmpty())out.put("context",new LinkedHashMap<String,Object>(context));
        List<Map<String,Object>> diagnostics=new ArrayList<Map<String,Object>>();for(Section section:sections)section.collectDiagnostics(diagnostics);out.put("diagnostics",diagnostics);
        Map<String,Object> counts=new LinkedHashMap<String,Object>();int pass=0,fail=0,warn=0,skip=0,errors=0,warnings=0;
        for(Section section:sections){pass+=section.count("PASS");fail+=section.count("FAIL");warn+=section.count("WARN");skip+=section.count("SKIP");}
        for(Map<String,Object> diagnostic:diagnostics) {
            Object severity=diagnostic.get("severity");
            if("WARNING".equals(severity)||"WARN".equals(severity))warnings++;
            else if(!"INFO".equals(severity))errors++;
        }
        counts.put("pass",pass);counts.put("fail",fail);counts.put("warn",warn);counts.put("skip",skip);counts.put("errors",errors);counts.put("warnings",warnings);
        out.put("counts",counts);out.put("overallStatus",overallStatus());out.put("complete",Boolean.valueOf(complete));out.put("exitCode",exitCode());return out;
    }
    private static String required(String value,String name){if(value==null||value.trim().isEmpty())throw new IllegalArgumentException(name+" is required");return value;}
    private int jsonBytes(){try{return JSON.writeValueAsBytes(toMap()).length;}catch(java.io.IOException failure){return Integer.MAX_VALUE;}}
    @SuppressWarnings("unchecked") private static void addCopied(List<Section> target,Map<String,Object> source) {
        Section section=new Section(String.valueOf(source.get("name")),(String)source.get("source"),String.valueOf(source.get("status")));
        Object diagnostics=source.get("diagnostics");if(diagnostics instanceof List)for(Object item:(List<?>)diagnostics)if(item instanceof Map)section.diagnostic((Map<String,Object>)item);
        Object children=source.get("sections");if(children instanceof List)for(Object child:(List<?>)children)if(child instanceof Map)addCopied(section.children,(Map<String,Object>)child);
        target.add(section);
    }

    public static final class Section {
        private final String name,source;
        private String status;
        private final List<Map<String,Object>> diagnostics=new ArrayList<Map<String,Object>>();
        private final List<Section> children=new ArrayList<Section>();
        Section(String name,String source,String status){this.name=required(name,"section name");this.source=source;this.status=checkedStatus(status);}
        public Section diagnostic(Map<String,Object> diagnostic) { if(diagnostic!=null)diagnostics.add(Collections.unmodifiableMap(new LinkedHashMap<String,Object>(diagnostic)));return this; }
        public Section child(String childName,String childSource,String childStatus) { Section child=new Section(childName,childSource,childStatus);children.add(child);return child; }
        public String status(){return status;}
        public Section status(String value){status=checkedStatus(value);return this;}
        public List<Map<String,Object>> diagnosticsView(){return Collections.unmodifiableList(diagnostics);}
        public List<Map<String,Object>> toMapDiagnostics(){return new ArrayList<Map<String,Object>>(diagnostics);}
        private boolean hasFailures(){if("FAIL".equals(status))return true;for(Section child:children)if(child.hasFailures())return true;return false;}
        private int count(String expected){int result=expected.equals(status)?1:0;for(Section child:children)result+=child.count(expected);return result;}
        private void collectDiagnostics(List<Map<String,Object>> output){output.addAll(diagnostics);for(Section child:children)child.collectDiagnostics(output);}
        private Map<String,Object> toMap(){Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("name",name);if(source!=null)out.put("source",source);out.put("status",status);if(!diagnostics.isEmpty())out.put("diagnostics",new ArrayList<Map<String,Object>>(diagnostics));if(!children.isEmpty()){List<Map<String,Object>> values=new ArrayList<Map<String,Object>>();for(Section child:children)values.add(child.toMap());out.put("sections",values);}return out;}
        private static String checkedStatus(String value){if(!"PASS".equals(value)&&!"FAIL".equals(value)&&!"WARN".equals(value)&&!"SKIP".equals(value))throw new IllegalArgumentException("Unsupported validation section status");return value;}
    }
}
