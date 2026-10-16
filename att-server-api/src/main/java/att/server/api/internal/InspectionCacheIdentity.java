package att.server.api.internal;

import att.server.api.WorkerRequest;
import att.metrics.InspectionCacheKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Shared Server/Worker identity for trusted parsed state. Safe projections are never retained here. */
public final class InspectionCacheIdentity {
    private static final ObjectMapper JSON=new ObjectMapper();
    private InspectionCacheIdentity() { }
    public static String context(Path root,WorkerRequest request) throws Exception {
        List<Object> inputs=new ArrayList<Object>();
        Collections.addAll(inputs,root.toRealPath().toString(),request.config==null?"config/config.yaml":request.config,
                request.maxSourceBytes==null?65536:request.maxSourceBytes,
                request.maxResponseBytes==null?262144:request.maxResponseBytes,
                Boolean.TRUE.equals(request.sourceBrowsingEnabled),request.sourceBrowsingPolicy,Boolean.TRUE.equals(request.redactionEnabled));
        for(List<String> values:Arrays.asList(request.safeTextSources,request.externalTextSources,request.externalSourceRoots,
                request.sourceDenyGlobs,request.sensitivePaths,request.sensitiveFieldPatterns)) {
            List<String> sorted=new ArrayList<String>(values==null?Collections.<String>emptyList():values);Collections.sort(sorted);inputs.add(sorted);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(inputs)));
    }
    public static String resource(Path root,WorkerRequest request)throws Exception {
        return InspectionCacheKey.environment(context(root,request),request.environment,"resource");
    }
    public static String requested(Path root,WorkerRequest request)throws Exception {
        if("configuration".equals(request.inspectionType)&&!"source".equals(request.inspectionAction)) {
            if("files".equals(request.inspectionAction))return "";
            String environment="declared".equals(request.inspectionAction)?null:request.inspectionEnvironment;
            return InspectionCacheKey.environment(context(root,request),environment,"configuration");
        }
        return resource(root,request);
    }
}
