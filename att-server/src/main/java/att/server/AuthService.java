package att.server;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Local account, bootstrap, password and server-side session boundary. */
final class AuthService {
    static final String IDENTITY = "att.server.localIdentity";
    static final String SESSION = "att.server.localSession";
    static final String BASIC_CSRF = "att.server.basicCsrf";
    static final String COOKIE = "ATTSESSION";
    static final long IDLE_MILLIS = 30L * 60 * 1000, ABSOLUTE_MILLIS = 12L * 60 * 60 * 1000;
    private static final int ITERATIONS = 310_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_HASH;
    static { try { DUMMY_HASH=hash("att-dummy-authentication-value"); } catch(Exception failure) { throw new ExceptionInInitializerError(failure); } }
    private final JobStore store;
    private final Path bootstrapFile;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    AuthService(ServerConfig config, JobStore store) throws Exception {
        this.store = store;
        bootstrapFile = config.dataDir.resolve("bootstrap-admin.token");
        if (store.authUserCount() == 0 && !Files.exists(bootstrapFile)) {
            byte[] token = new byte[32]; RANDOM.nextBytes(token);
            Files.createFile(bootstrapFile);
            secureFile(bootstrapFile);
            Files.writeString(bootstrapFile,Base64.getUrlEncoder().withoutPadding().encodeToString(token),StandardCharsets.US_ASCII,java.nio.file.StandardOpenOption.WRITE);
        }
    }

    boolean local(ServerConfig config) { return "local".equals(config.authMode); }
    boolean bootstrapRequired() throws Exception { return store.authUserCount() == 0 && Files.isRegularFile(bootstrapFile); }
    Map<String,Object> bootstrap(String token,String login,String display,String password) throws Exception {
        if (!bootstrapRequired()) throw new AuthFailure(409, "ATT-AUTH-BOOTSTRAP-CLOSED");
        String expected=Files.readString(bootstrapFile,StandardCharsets.US_ASCII).strip();
        if(token==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),token.getBytes(StandardCharsets.US_ASCII)))throw new AuthFailure(401,"ATT-AUTH-INVALID-CREDENTIALS");
        createUser(login,display,password,"ADMIN");Files.deleteIfExists(bootstrapFile);return safeUser(store.authUser(normalize(login)));
    }
    Map<String,Object> login(String login,String password,String client) throws Exception {
        Map<String,Object> user=verifyCredentials(login,password,client);String normalized=(String)user.get("loginId");long now=System.currentTimeMillis();
        String session=randomToken(),csrf=randomToken();long expiry=now+ABSOLUTE_MILLIS;store.saveAuthSession(sha256(session),normalized,csrf,now,expiry);
        return Map.of("token",session,"csrfToken",csrf,"user",safeUser(user));
    }
    Map<String,Object> verifyCredentials(String login,String password,String client) throws Exception {
        String normalized=normalize(login);String key=normalized+"\u0000"+(client==null?"":client);long now=System.currentTimeMillis();
        if(attempts.size()>=10_000&&!attempts.containsKey(key)){attempts.entrySet().removeIf(entry->now-entry.getValue().window>600_000);if(attempts.size()>=10_000)throw new AuthFailure(429,"ATT-AUTH-RATE-LIMITED");}
        Attempt a=attempts.compute(key,(k,v)->v==null||now-v.window>600_000?new Attempt(now,0):v);
        if(a.failures>=8)throw new AuthFailure(429,"ATT-AUTH-RATE-LIMITED");
        Map<String,Object> user=store.authUser(normalized);
        String storedHash=user==null?DUMMY_HASH:(String)user.get("passwordHash");boolean matches=verify(password,storedHash);
        boolean valid=user!=null&&Boolean.TRUE.equals(user.get("active"))&&matches;
        if(!valid){attempts.compute(key,(k,v)->new Attempt(v.window,v.failures+1));throw new AuthFailure(401,"ATT-AUTH-INVALID-CREDENTIALS");}
        attempts.remove(key);
        if(needsUpgrade(storedHash)){String newCsrf=randomToken();store.updateAuthPassword(normalized,hash(password),newCsrf);user.put("csrfToken",newCsrf);}
        return user;
    }
    Map<String,String> session(HttpServletRequest req) throws Exception {
        jakarta.servlet.http.Cookie[] cookies=req.getCookies();if(cookies==null)return null;
        for(var cookie:cookies)if(COOKIE.equals(cookie.getName())&&cookie.getValue()!=null&&cookie.getValue().length()<=128){String tokenHash=sha256(cookie.getValue());Map<String,String> session=store.authSession(tokenHash,System.currentTimeMillis(),IDLE_MILLIS);if(session!=null){session.put("tokenHash",tokenHash);return session;}}
        return null;
    }
    boolean sessionStillValid(String tokenHash) throws Exception {return store.authSession(tokenHash,System.currentTimeMillis(),IDLE_MILLIS)!=null;}
    void logout(HttpServletRequest req) throws Exception {for(var c:req.getCookies()==null?new jakarta.servlet.http.Cookie[0]:req.getCookies())if(COOKIE.equals(c.getName()))store.deleteAuthSession(sha256(c.getValue()));}
    void password(String login,String password) throws Exception {store.updateAuthPassword(normalize(login),hash(password),randomToken());}
    void changePassword(String login,String currentPassword,String nextPassword) throws Exception {Map<String,Object> user=store.authUser(normalize(login));if(user==null||!Boolean.TRUE.equals(user.get("active"))||!verify(currentPassword,(String)user.get("passwordHash")))throw new AuthFailure(401,"ATT-AUTH-INVALID-CREDENTIALS");store.updateAuthPassword((String)user.get("loginId"),hash(nextPassword),randomToken());}
    void disable(String login) throws Exception {Map<String,Object> user=store.authUser(normalize(login));if(user==null)throw new IllegalArgumentException("Unknown user");if(Boolean.TRUE.equals(user.get("active"))&&"ADMIN".equals(user.get("role"))&&store.activeAdminCount()<=1)throw new AuthFailure(409,"ATT-AUTH-LAST-ADMIN");store.setAuthActive((String)user.get("loginId"),false);}
    void enable(String login) throws Exception {store.setAuthActive(normalize(login),true);}
    void createUser(String login,String display,String password,String role) throws Exception {
        String normalized=normalize(login);String safeDisplay=display==null?normalized:Normalizer.normalize(display.strip(),Normalizer.Form.NFC);
        if(safeDisplay.isBlank()||safeDisplay.length()>120||safeDisplay.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("displayName must be 1-120 printable characters");
        if(!"ADMIN".equals(role)&&!"USER".equals(role))throw new IllegalArgumentException("role must be ADMIN or USER");
        if(store.authUser(normalized)!=null)throw new AuthFailure(409,"ATT-AUTH-LOGIN-ID-TAKEN");
        store.createAuthUser(normalized,safeDisplay,hash(password),role,randomToken());
    }
    static String normalize(String value) {
        if(value==null)throw new IllegalArgumentException("loginId is required");String v=Normalizer.normalize(value.strip(),Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
        if(!v.matches("[a-z0-9][a-z0-9._-]{2,119}"))throw new IllegalArgumentException("loginId must be 3-120 lowercase letters, numbers, dots, underscores, or hyphens");return v;
    }
    static Map<String,Object> safeUser(Map<String,Object> user) {if(user==null)return Map.of();return Map.of("loginId",user.get("loginId"),"displayName",user.get("displayName"),"role",user.get("role"));}
    private static String hash(String password) throws Exception {
        validatePassword(password);byte[] salt=new byte[16];RANDOM.nextBytes(salt);PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,ITERATIONS,256);
        try {byte[] derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return "pbkdf2-sha256$"+ITERATIONS+"$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derived);}finally{spec.clearPassword();}
    }
    private static boolean verify(String password,String encoded) throws Exception {
        if(password==null||encoded==null)return false;String[] parts=encoded.split("\\$",-1);if(parts.length!=4||!"pbkdf2-sha256".equals(parts[0]))return false;
        int rounds=Integer.parseInt(parts[1]);if(rounds<100_000||rounds>1_000_000)return false;byte[] salt=Base64.getDecoder().decode(parts[2]),expected=Base64.getDecoder().decode(parts[3]);PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,rounds,expected.length*8);
        try{return MessageDigest.isEqual(expected,SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}finally{spec.clearPassword();}
    }
    private static boolean needsUpgrade(String encoded){String[] parts=encoded.split("\\$",-1);return parts.length==4&&"pbkdf2-sha256".equals(parts[0])&&Integer.parseInt(parts[1])<ITERATIONS;}
    private static void validatePassword(String password) {if(password==null||password.length()<12||password.length()>1024||password.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("password must be 12-1024 characters without control characters");}
    private static String randomToken(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String sha256(String token) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}
    private static void secureFile(Path file){try{Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException unsupported){try{var view=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class);if(view==null)throw new IllegalStateException("Filesystem does not support private bootstrap-token permissions");var owner=Files.getOwner(file);var entry=java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner).setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build();view.setAcl(java.util.List.of(entry));}catch(Exception e){throw new IllegalStateException("Unable to secure local authentication bootstrap token",e);}}catch(Exception e){throw new IllegalStateException("Unable to secure local authentication bootstrap token",e);}}
    private record Attempt(long window,int failures){}
    static final class AuthFailure extends RuntimeException {final int status;final String code;AuthFailure(int status,String code){super("Authentication request could not be completed");this.status=status;this.code=code;}}
}
