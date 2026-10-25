package att.server.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

/** Bounded, value-free configuration evidence accepted across the Worker boundary. */
public final class ConfigurationSnapshot {
    private ConfigurationSnapshot() { }
    public static Map<String,Object> copy(Map<?,?> source) {
        if(source == null) throw new IllegalArgumentException("Configuration evidence is required");
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        for(String key:Arrays.asList("configurationIdentity","manifestDigest","globalDigest","schemaRegistryDigest",
                "packageRevision","approvedConfigurationIdentity")) {
            Object value=source.get(key);
            if(value != null) {
                if(!(value instanceof String)||!((String)value).matches("[a-f0-9]{64}")) throw invalid();
                result.put(key,value);
            }
        }
        for(String key:Arrays.asList("environment","engineVersion","engineRevision","packageId")) {
            Object value=source.get(key);
            if(value != null) {
                if(!(value instanceof String)||!((String)value).matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")) throw invalid();
                result.put(key,value);
            }
        }
        Object origin=source.get("environmentOrigin");
        if(!origin(origin)) throw invalid();
        result.put("environmentOrigin",origin);
        for(String key:Arrays.asList("origins","authoredPaths")) {
            Object raw=source.get(key);
            if(!(raw instanceof Map)||((Map<?,?>)raw).size()>32) throw invalid();
            Map<String,String> fields=new LinkedHashMap<String,String>();
            for(Map.Entry<?,?> field:((Map<?,?>)raw).entrySet()) {
                if(!(field.getKey() instanceof String)||!((String)field.getKey()).matches("[A-Za-z][A-Za-z0-9.]{0,95}")) throw invalid();
                if("origins".equals(key)?!origin(field.getValue()):!Arrays.asList("","package.yaml","config/config.yaml","request.environment","Context.environment").contains(field.getValue())) throw invalid();
                fields.put((String)field.getKey(),(String)field.getValue());
            }
            result.put(key,Collections.unmodifiableMap(fields));
        }
        for(String required:Arrays.asList("environment","configurationIdentity","manifestDigest","globalDigest","schemaRegistryDigest","engineVersion","engineRevision"))
            if(!result.containsKey(required)) throw invalid();
        return Collections.unmodifiableMap(result);
    }
    private static boolean origin(Object value) {
        return Arrays.asList("runtime-default","global","package","context","request","scenario").contains(value);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Configuration Worker returned invalid evidence"); }
}
