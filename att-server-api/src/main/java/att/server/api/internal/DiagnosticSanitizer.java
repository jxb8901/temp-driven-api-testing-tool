package att.server.api.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Secret-safe projection for diagnostic text crossing the Worker protocol. */
public final class DiagnosticSanitizer {
    private static final String REDACTED="[REDACTED_SECRET]";
    private static final Pattern SENSITIVE_HEADER=Pattern.compile(
            "(?i)(\\b(?:proxy[-_]?authorization|authorization|set-cookie|cookie)\\b\\s*[:=]\\s*)[^\\r\\n]*");
    private static final Pattern SENSITIVE_ASSIGNMENT=Pattern.compile(
            "(?i)(\\b(?:password|passwd|token|secret|api[-_]?key|authorization|credential|private[-_]?key|access[-_]?key|identityfile|truststorepassword)\\b\\s*[=:]\\s*)(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&]+)");
    private static final Pattern URL=Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\\\"]+");
    private static final Pattern QUOTED_ABSOLUTE_PATH=Pattern.compile("(['\\\"])((?:/[^\\r\\n'\\\"]+)|(?:[A-Za-z]:[\\\\/][^\\r\\n'\\\"]+)|(?:\\\\\\\\[^\\r\\n'\\\"]+))\\1");
    private static final Pattern ABSOLUTE_PATH_TOKEN=Pattern.compile("(?<![A-Za-z0-9_/:])(?:[A-Za-z]:[\\\\/]|\\\\\\\\|/)[^\\s'\\\"<>|,;]*");

    private DiagnosticSanitizer() { }

    public static Map<String,Object> sanitize(Map<?,?> source) {
        Map<String,Object> safe=new LinkedHashMap<String,Object>();
        for(Map.Entry<?,?> entry:source.entrySet()) {
            String key=String.valueOf(entry.getKey());
            safe.put(key,sensitiveName(key)?REDACTED:sanitizeValue(entry.getValue()));
        }
        return safe;
    }

    public static String redactText(String value) {
        if(value==null)return "";
        if(value.indexOf(':')<0&&value.indexOf('=')<0)return value;
        Matcher headerMatcher=SENSITIVE_HEADER.matcher(value);StringBuffer headerSafe=new StringBuffer();
        while(headerMatcher.find())headerMatcher.appendReplacement(headerSafe,Matcher.quoteReplacement(headerMatcher.group(1)+REDACTED));
        headerMatcher.appendTail(headerSafe);
        Matcher matcher=SENSITIVE_ASSIGNMENT.matcher(headerSafe.toString());StringBuffer safe=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(safe,Matcher.quoteReplacement(matcher.group(1)+REDACTED));
        matcher.appendTail(safe);return safe.toString();
    }

    /** Hides host-local absolute paths while preserving URLs and the useful final path segment. */
    public static String redactAbsolutePaths(String value) {
        return redactAbsolutePaths(value, null);
    }

    /** Keeps package-relative locations actionable while hiding absolute host paths. */
    public static String redactAbsolutePaths(String value, Path packageRoot) {
        if(value==null||value.isEmpty())return value;
        Path safeRoot=canonicalRoot(packageRoot);
        List<String> urls=new ArrayList<>();Matcher urlMatcher=URL.matcher(value);StringBuffer protectedValue=new StringBuffer();
        while(urlMatcher.find()) {
            String token="\u0001ATT_URL_"+urls.size()+"\u0002";urls.add(urlMatcher.group());
            urlMatcher.appendReplacement(protectedValue,Matcher.quoteReplacement(token));
        }
        urlMatcher.appendTail(protectedValue);
        String redacted=replacePaths(protectedValue.toString(),QUOTED_ABSOLUTE_PATH,true,safeRoot);
        redacted=replacePaths(redacted,ABSOLUTE_PATH_TOKEN,false,safeRoot);
        for(int i=0;i<urls.size();i++)redacted=redacted.replace("\u0001ATT_URL_"+i+"\u0002",urls.get(i));
        return redacted;
    }

    private static String replacePaths(String value,Pattern pattern,boolean quoted,Path packageRoot) {
        Matcher matcher=pattern.matcher(value);StringBuffer result=new StringBuffer();
        while(matcher.find()) {
            String path=quoted?matcher.group(2):matcher.group();
            String replacement=(quoted?matcher.group(1):"")+safePathLabel(path,packageRoot)+(quoted?matcher.group(1):"");
            matcher.appendReplacement(result,Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);return result.toString();
    }

    private static String safePathLabel(String value,Path packageRoot) {
        if(packageRoot==null)return externalBasename(value);
        try {
            Path candidate=Paths.get(value);
            if(!candidate.isAbsolute())return externalBasename(value);
            Path root=packageRoot;
            if(root==null)return externalBasename(value);
            Path absolute=candidate.toAbsolutePath().normalize();
            List<Path> suffix=new ArrayList<>();
            Path existing=absolute;
            while(existing!=null&&!Files.exists(existing)) {
                Path name=existing.getFileName();
                if(name!=null)suffix.add(0,name);
                existing=existing.getParent();
            }
            if(existing==null)return externalBasename(value);
            Path resolved=existing.toRealPath();
            for(Path part:suffix)resolved=resolved.resolve(part);
            resolved=resolved.normalize();
            if(!resolved.startsWith(root))return externalBasename(value);
            Path relative=root.relativize(resolved);
            return relative.toString().isEmpty()?"$ATT_PACKAGE_ROOT":relative.toString().replace('\\','/');
        } catch(IOException|IllegalArgumentException failure) {
            return externalBasename(value);
        }
    }

    private static Path canonicalRoot(Path root) {
        if(root==null)return null;
        try { return root.toRealPath(); }
        catch(IOException failure) { return root.toAbsolutePath().normalize(); }
    }

    private static String externalBasename(String value) {
        if(value==null||value.isEmpty())return "$EXTERNAL";
        int end=value.length();while(end>0&&(value.charAt(end-1)=='/'||value.charAt(end-1)=='\\'))end--;
        int separator=Math.max(value.lastIndexOf('/',end-1),value.lastIndexOf('\\',end-1));
        String name=value.substring(separator+1,end);
        return name.isEmpty()?"$EXTERNAL":"$EXTERNAL/"+name;
    }

    public static Object sanitizeValue(Object value) {
        if(value instanceof Map<?,?>)return sanitize((Map<?,?>)value);
        if(value instanceof Iterable<?>) {
            List<Object> safe=new ArrayList<Object>();for(Object item:(Iterable<?>)value)safe.add(sanitizeValue(item));return safe;
        }
        if(value instanceof String)return redactText((String)value);
        return value;
    }

    private static boolean sensitiveName(String value) {
        String lower=value.toLowerCase(Locale.ROOT);StringBuilder normalized=new StringBuilder(lower.length());
        for(int i=0;i<lower.length();i++){char c=lower.charAt(i);if(c>='a'&&c<='z'||c>='0'&&c<='9')normalized.append(c);}
        String key=normalized.toString();
        return key.contains("password")||key.contains("passwd")||key.contains("token")||key.contains("secret")
                ||key.contains("credential")||key.contains("apikey")||key.contains("privatekey")||key.contains("accesskey")
                ||key.contains("authorization")||key.contains("cookie");
    }
}
