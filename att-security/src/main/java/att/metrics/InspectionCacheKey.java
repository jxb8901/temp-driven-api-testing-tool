package att.metrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Opaque, deterministic trusted-cache identity; contains no response or principal data. */
public final class InspectionCacheKey {
    private InspectionCacheKey() { }
    public static String environment(String context,String environment,String kind) {
        try {
            String value=kind+"\n"+context+"\n"+(environment==null?"":environment);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
