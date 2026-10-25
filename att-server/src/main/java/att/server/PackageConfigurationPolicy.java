package att.server;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Trusted deployment policy; never read from a Package Manifest. */
final class PackageConfigurationPolicy {
    final boolean sourceEditingEnabled;
    final int maxDrafts, maxDraftsPerUser, draftTtlSeconds, maxHistory;
    final Set<String> allowedEnvironments, requireExplicitEnvironments;

    private PackageConfigurationPolicy(boolean enabled, int drafts, int perUser, int ttl, int history,
                                       Set<String> allowed, Set<String> explicit) {
        sourceEditingEnabled=enabled;maxDrafts=drafts;maxDraftsPerUser=perUser;draftTtlSeconds=ttl;maxHistory=history;
        allowedEnvironments=allowed;requireExplicitEnvironments=explicit;
    }
    static PackageConfigurationPolicy load(Object raw) {
        if(raw!=null&&!(raw instanceof Map))throw new IllegalArgumentException("server.packageConfiguration must be a mapping");
        Map<?,?> values=raw==null?Map.of():(Map<?,?>)raw;
        for(Object key:values.keySet())if(!Set.of("sourceEditingEnabled","maxDrafts","maxDraftsPerUser","draftTtlSeconds","maxHistory","allowedEnvironments","requireExplicitEnvironments").contains(key))
            throw new IllegalArgumentException("Unknown server.packageConfiguration field");
        Object enabled=values.get("sourceEditingEnabled");
        if(enabled!=null&&!(enabled instanceof Boolean))throw new IllegalArgumentException("sourceEditingEnabled must be boolean");
        int drafts=number(values,"maxDrafts",128,1,1000), perUser=number(values,"maxDraftsPerUser",Math.min(8,drafts),1,drafts);
        return new PackageConfigurationPolicy(Boolean.TRUE.equals(enabled),drafts,perUser,
                number(values,"draftTtlSeconds",900,60,3600),number(values,"maxHistory",50,2,1000),
                names(values.get("allowedEnvironments")),names(values.get("requireExplicitEnvironments")));
    }
    void authorizeEnvironment(String selected, boolean explicit) {
        String canonical=selected.toLowerCase(Locale.ROOT);
        if(!allowedEnvironments.isEmpty()&&!allowedEnvironments.contains(canonical))
            throw new EnvironmentDeniedException("The resolved Environment is not allowed by Server policy");
        if(!explicit&&requireExplicitEnvironments.contains(canonical))
            throw new EnvironmentDeniedException("This Environment requires an explicit environment selection before validation or execution");
    }
    private static int number(Map<?,?> values,String key,int fallback,int min,int max) {
        Object raw=values.get(key);if(raw==null)return fallback;
        if(!(raw instanceof Number))throw new IllegalArgumentException("server.packageConfiguration."+key+" must be an integer");
        try {int n=new java.math.BigDecimal(raw.toString()).intValueExact();if(n<min||n>max)throw new ArithmeticException();return n;}
        catch(ArithmeticException invalid){throw new IllegalArgumentException("server.packageConfiguration."+key+" is outside its limits");}
    }
    private static Set<String> names(Object raw) {
        if(raw==null)return Set.of();if(!(raw instanceof List<?> list)||list.size()>100)throw new IllegalArgumentException("Environment policy must be a bounded list");
        Set<String> result=new LinkedHashSet<>();
        for(Object name:list)if(!(name instanceof String s)||!s.matches("[A-Za-z][A-Za-z0-9_-]*")||!result.add(s.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Environment policy contains an invalid or duplicate name");
        return Collections.unmodifiableSet(result);
    }
    static final class EnvironmentDeniedException extends IllegalArgumentException { EnvironmentDeniedException(String message){super(message);} }
}
