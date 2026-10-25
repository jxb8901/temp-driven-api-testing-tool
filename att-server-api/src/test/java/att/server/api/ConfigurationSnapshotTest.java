package att.server.api;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationSnapshotTest {
    private Map<String,Object> valid() {
        Map<String,Object> value=new LinkedHashMap<String,Object>();
        value.put("environment","UAT");value.put("environmentOrigin","package");value.put("packageId","registered-id");
        value.put("engineVersion","4.2.4");value.put("engineRevision","unknown");
        for(String key:Arrays.asList("configurationIdentity","manifestDigest","globalDigest","schemaRegistryDigest"))
            value.put(key,String.join("",java.util.Collections.nCopies(64,"a")));
        value.put("origins",java.util.Collections.singletonMap("environment","package"));
        value.put("authoredPaths",java.util.Collections.singletonMap("environment","package.yaml"));return value;
    }
    @Test void retainsOnlySafeEvidenceAndDetachesNestedMaps() {
        Map<String,Object> value=valid();value.put("password","private");value.put("packageRoot","/private/host");
        Map<String,Object> safe=ConfigurationSnapshot.copy(value);
        assertFalse(safe.containsKey("password"));assertFalse(safe.containsKey("packageRoot"));
        value.put("environment","SIT");assertEquals("UAT",safe.get("environment"));
        assertThrows(UnsupportedOperationException.class,()->safe.put("environment","SIT"));
        assertThrows(UnsupportedOperationException.class,()->((Map<String,Object>)safe.get("origins")).put("environment","request"));
    }
    @Test void rejectsMissingIdentityUnboundedValuesAndHostAuthoredPaths() {
        Map<String,Object> value=valid();value.remove("globalDigest");final Map<String,Object> incomplete=value;assertThrows(IllegalArgumentException.class,()->ConfigurationSnapshot.copy(incomplete));
        value=valid();value.put("authoredPaths",java.util.Collections.singletonMap("environment","/private/config.yaml"));
        final Map<String,Object> unsafePath=value;assertThrows(IllegalArgumentException.class,()->ConfigurationSnapshot.copy(unsafePath));
        value=valid();value.put("environmentOrigin","folder");final Map<String,Object> inferred=value;
        assertThrows(IllegalArgumentException.class,()->ConfigurationSnapshot.copy(inferred));
    }
}
