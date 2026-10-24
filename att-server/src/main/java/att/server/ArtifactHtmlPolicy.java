package att.server;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;

/** Finalizes HTML once for identical, isolated online and offline report bytes. */
final class ArtifactHtmlPolicy {
    // Reviewed static search/sort controls from HtmlReportGenerator; dynamic or altered scripts are rejected.
    static final String REPORT_SCRIPT_DIGEST="0e69333dd886c7209f88a299b6bdf88e269fd6e92a51e00af114b0145d7daf28";
    // The Load chart renderer builds only local SVG nodes from bounded summary data.
    static final String LOAD_REPORT_SCRIPT_DIGEST="3ff4a55f4e32de358618fb25bae4420715a6fa523c347b6f8eab52f8610c81ad";
    static final String CONTENT_POLICY="default-src 'none'; script-src 'sha256-DmkzPdiGxyCfiKKZtr34jiaf1ukqUeAK8RSwFF19ryg=' 'sha256-P/SlX04y3jWGGPsluuRCBxWm+lI8NHtvjqtS+GEMga0='; style-src 'unsafe-inline'; img-src data:; form-action 'none'; base-uri 'none'";
    private ArtifactHtmlPolicy() {}

    static byte[] publish(byte[] input,String path,Set<String> approved) throws Exception {
        Document document=Jsoup.parse(new String(input,StandardCharsets.UTF_8),"https://archive.invalid/"+encoded(path));
        document.outputSettings().prettyPrint(false);
        var trustedScripts=new ArrayList<String>();
        var trustedJsonData=new ArrayList<String>();
        for(Element script:document.select("script")) {
            if(!script.hasAttr("src")) {
                if("application/json".equalsIgnoreCase(script.attr("type"))&&"att-load-summary-data".equals(script.id())) {
                    try {
                        var data=ServerRuntime.JSON.readTree(script.data());
                        if(data!=null&&data.isObject())trustedJsonData.add(script.data());
                    } catch(Exception invalidJson) { /* Invalid embedded summary data is omitted. */ }
                } else {
                    String digest=ArtifactArchive.sha256(script.data().getBytes(StandardCharsets.UTF_8));
                    if(REPORT_SCRIPT_DIGEST.equals(digest)||LOAD_REPORT_SCRIPT_DIGEST.equals(digest))trustedScripts.add(script.data());
                }
            }
        }
        document.select("script,base,meta,iframe,object,embed,form,math,link").remove();
        for(Element link:document.select("a[href]")) {
            String replacement=resolve(path,link.attr("href"),approved);
            if(replacement==null) {link.removeAttr("href");link.appendText(" [unavailable in exported archive]");}
            else link.attr("href",replacement);
            link.removeAttr("target");link.removeAttr("download");link.removeAttr("ping");
        }
        for(Element resource:document.select("[src]"))if(!resource.attr("src").matches("(?is)data:image/(png|jpeg|gif);base64,[A-Za-z0-9+/=\\r\\n]+"))resource.removeAttr("src");
        for(Element style:document.select("head > style"))document.body().prependChild(style);
        Safelist allowed=Safelist.relaxed().preserveRelativeLinks(true)
                .addTags("html","head","body","title","style","main","section","header","footer","nav","details","summary","button","input","select","option","label","svg","path","polyline","rect","text")
                .addAttributes(":all","id","class","style","title","role","aria-label","hidden","data-workbook","data-sheet","data-caseid","data-execid","data-name","data-tags","data-expected","data-actual","data-search","data-status","data-duration","data-sort","data-type","data-direction")
                .addAttributes("svg","viewbox","width","height")
                .addAttributes("path","d","fill","stroke","stroke-width","stroke-dasharray")
                .addAttributes("polyline","points","fill","stroke","stroke-width")
                .addAttributes("rect","x","y","width","height","fill")
                .addAttributes("text","x","y","fill","font-size","text-anchor")
                .removeAttributes("svg","style").removeAttributes("path","style").removeAttributes("polyline","style")
                .removeAttributes("rect","style").removeAttributes("text","style")
                .addAttributes("input","type","value","placeholder").addAttributes("option","value","selected")
                .addAttributes("button","type").addAttributes("label","for");
        Document clean=new Cleaner(allowed).clean(document);clean.outputSettings().prettyPrint(false);clean.title(document.title());
        clean.head().prependElement("meta").attr("charset","UTF-8");
        clean.head().appendElement("meta").attr("http-equiv","Content-Security-Policy").attr("content",CONTENT_POLICY);
        clean.head().appendElement("meta").attr("name","referrer").attr("content","no-referrer");
        for(String data:trustedJsonData)clean.body().appendElement("script").attr("type","application/json")
                .attr("id","att-load-summary-data").appendChild(new org.jsoup.nodes.DataNode(data));
        for(String script:trustedScripts)clean.body().appendElement("script").appendChild(new org.jsoup.nodes.DataNode(script));
        return clean.outerHtml().getBytes(StandardCharsets.UTF_8);
    }

    private static String resolve(String source,String href,Set<String> approved) {
        try {
            URI uri=new URI(href.replace(" ","%20"));
            if(uri.isAbsolute()||uri.getRawAuthority()!=null||uri.getRawQuery()!=null||href.startsWith("/")||href.indexOf('\\')>=0)return null;
            String requested=uri.getPath();
            if(requested==null)return null;
            Path parent=Path.of(source).getParent();if(parent==null)parent=Path.of("");
            Path target=requested.isEmpty()?Path.of(source):parent.resolve(requested).normalize();
            String logical=target.toString().replace('\\','/');
            if(!ArtifactArchive.safePath(logical)||!approved.contains(logical))return null;
            String relative=requested.isEmpty()?"":encoded(parent.relativize(target).toString().replace('\\','/'));
            return relative+(uri.getRawFragment()==null?"":"#"+uri.getRawFragment());
        } catch(Exception invalid){return null;}
    }

    private static String encoded(String path) {
        return java.util.Arrays.stream(path.split("/",-1)).map(part->java.net.URLEncoder.encode(part,StandardCharsets.UTF_8).replace("+","%20")).collect(java.util.stream.Collectors.joining("/"));
    }
}
