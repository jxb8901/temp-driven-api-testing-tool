package att.validation;

import att.server.api.AttValidationReport;
import java.util.List;
import java.util.Map;

/** Plain terminal presentation for the stable att-validation/v1 report. */
public final class ValidationReportRenderer {
    private ValidationReportRenderer() { }

    @SuppressWarnings("unchecked")
    public static String human(AttValidationReport report) {
        Map<String,Object> value=report.toMap();
        StringBuilder out=new StringBuilder("ATT Validation Report\n");
        out.append("Backend       : ").append(value.get("backend")).append('\n');
        out.append("Scope         : ").append(value.get("scope")).append('\n');
        Object context=value.get("context");
        if(context instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)context).entrySet())
            out.append(label(String.valueOf(entry.getKey()))).append(" : ").append(entry.getValue()).append('\n');
        Object raw=value.get("sections");
        if(raw instanceof List) for(Object section:(List<?>)raw) renderSection(out,(Map<String,Object>)section,0);
        Map<String,Object> counts=(Map<String,Object>)value.get("counts");
        out.append("Summary       : ").append(counts.get("pass")).append(" pass, ")
                .append(counts.get("fail")).append(" fail, ").append(counts.get("warnings"))
                .append(" warning(s), ").append(counts.get("skip")).append(" skipped\n");
        out.append("Overall Status: ").append(value.get("overallStatus")).append('\n');
        out.append("Exit Code     : ").append(value.get("exitCode"));
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void renderSection(StringBuilder out,Map<String,Object> section,int depth) {
        indent(out,depth).append('[').append(section.get("status")).append("] ").append(section.get("name"));
        if(section.get("source")!=null)out.append("  ").append(section.get("source"));
        out.append('\n');
        Object diagnostics=section.get("diagnostics");
        if(diagnostics instanceof List) for(Object item:(List<?>)diagnostics) {
            Map<String,Object> d=(Map<String,Object>)item;
            indent(out,depth+1).append(d.get("severity")).append(' ').append(d.get("code"));
            if(d.get("source")!=null)out.append(" | ").append(d.get("source"));
            if(d.get("field")!=null)out.append(" | field: ").append(d.get("field"));
            if(d.get("message")!=null)out.append(" | ").append(d.get("message"));
            if(d.get("suggestion")!=null)out.append(" | hint: ").append(d.get("suggestion"));
            out.append('\n');
        }
        Object children=section.get("sections");
        if(children instanceof List)for(Object child:(List<?>)children)renderSection(out,(Map<String,Object>)child,depth+1);
    }
    private static StringBuilder indent(StringBuilder out,int depth){for(int i=0;i<depth;i++)out.append("  ");return out;}
    private static String label(String value){StringBuilder out=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(i>0&&Character.isUpperCase(c))out.append(' ');out.append(i==0?Character.toUpperCase(c):c);}return String.format("%-14s",out.toString());}
}
