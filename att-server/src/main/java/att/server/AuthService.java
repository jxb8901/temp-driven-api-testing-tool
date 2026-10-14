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
import java.util.concurrent.Semaphore;
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
    private final ConcurrentHashMap<String, Attempt> clientAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Attempt> bootstrapAttempts = new ConcurrentHashMap<>();
    private final Semaphore passwordChecks = new Semaphore(Math.max(2,Math.min(8,Runtime.getRuntime().availableProcessors())),true);

    AuthService(ServerConfig config, JobStore store) throws Exception {
        this.store = store;
        bootstrapFile = config.dataDir.resolve("bootstrap-admin.token");
        initializeBootstrapToken();
    }

    boolean local(ServerConfig config) { return "local".equals(config.authMode); }
    boolean bootstrapRequired() throws Exception { return store.authUserCount() == 0 && !store.bootstrapClaimed() && validBootstrapTokenFile(); }
    Map<String,Object> bootstrap(String token,String login,String display,String password,String client) throws Exception {
        String clientKey=clientKey(client);long now=System.currentTimeMillis();Attempt attempt=attempt(bootstrapAttempts,clientKey,now,10_000);
        if(attempt.failures>=8)throw new AuthFailure(429,"ATT-AUTH-RATE-LIMITED");
        if (!bootstrapRequired()) throw new AuthFailure(409, "ATT-AUTH-BOOTSTRAP-CLOSED");
        String expected;try{expected=Files.readString(bootstrapFile,StandardCharsets.US_ASCII);}catch(java.nio.file.NoSuchFileException consumed){throw new AuthFailure(409,"ATT-AUTH-BOOTSTRAP-CLOSED");}
        boolean tokenMatches=token!=null&&token.length()==43&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),token.getBytes(StandardCharsets.US_ASCII));
        if(!tokenMatches){increment(bootstrapAttempts,clientKey,now);throw new AuthFailure(401,"ATT-AUTH-INVALID-CREDENTIALS");}
        String normalized=normalize(login),safeDisplay=displayName(normalized,display),passwordHash=hash(password);
        store.createBootstrapAdmin(normalized,safeDisplay,passwordHash,randomToken());
        Files.deleteIfExists(bootstrapFile);bootstrapAttempts.remove(clientKey);return safeUser(store.authUser(normalized));
    }
    Map<String,Object> login(String login,String password,String client) throws Exception {
        Map<String,Object> user=verifyCredentials(login,password,client);String normalized=(String)user.get("loginId");long now=System.currentTimeMillis();
        String session=randomToken(),csrf=randomToken();long expiry=now+ABSOLUTE_MILLIS;store.saveAuthSession(sha256(session),normalized,csrf,now,expiry);
        return Map.of("token",session,"csrfToken",csrf,"user",safeUser(user));
    }
    Map<String,Object> verifyCredentials(String login,String password,String client) throws Exception {
        Map<String,Object> user=verifyCredentialsBounded(login,password,client);
        String storedHash=(String)user.get("passwordHash");
        if(needsUpgrade(storedHash)){String normalized=(String)user.get("loginId");String newCsrf=randomToken();store.updateAuthPassword(normalized,hash(password),newCsrf);user.put("csrfToken",newCsrf);}
        return user;
    }
    private Map<String,Object> verifyCredentialsBounded(String login,String password,String client) throws Exception {
        String normalized=normalize(login),clientKey=clientKey(client),key=normalized+"\u0000"+clientKey;long now=System.currentTimeMillis();
        Attempt userAttempt=attempt(attempts,key,now,10_000),ipAttempt=attempt(clientAttempts,clientKey,now,10_000);
        if(userAttempt.failures>=8||ipAttempt.failures>=32)throw new AuthFailure(429,"ATT-AUTH-RATE-LIMITED");
        Map<String,Object> user=store.authUser(normalized);
        String storedHash=user==null?DUMMY_HASH:(String)user.get("passwordHash");boolean supportedPassword=validPasswordInput(password),matches;
        if(!passwordChecks.tryAcquire())throw new AuthFailure(429,"ATT-AUTH-RATE-LIMITED");
        try {matches=supportedPassword?verify(password,storedHash):verify("invalid-password-input",DUMMY_HASH);} finally {passwordChecks.release();}
        boolean valid=user!=null&&Boolean.TRUE.equals(user.get("active"))&&matches;
        if(!valid){increment(attempts,key,now);increment(clientAttempts,clientKey,now);throw new AuthFailure(401,"ATT-AUTH-INVALID-CREDENTIALS");}
        attempts.remove(key);
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
    void changePassword(String login,String currentPassword,String nextPassword,String client) throws Exception {
        Map<String,Object> user=verifyCredentialsBounded(login,currentPassword,client);
        store.updateAuthPassword((String)user.get("loginId"),hash(nextPassword),randomToken());
    }
    void disable(String login) throws Exception {store.setAuthActive(normalize(login),false);}
    void enable(String login) throws Exception {store.setAuthActive(normalize(login),true);}
    void createUser(String login,String display,String password,String role) throws Exception {
        String normalized=normalize(login),safeDisplay=displayName(normalized,display);
        if(!"ADMIN".equals(role)&&!"USER".equals(role))throw new IllegalArgumentException("role must be ADMIN or USER");
        if(store.authUser(normalized)!=null)throw new AuthFailure(409,"ATT-AUTH-LOGIN-ID-TAKEN");
        store.createAuthUser(normalized,safeDisplay,hash(password),role,randomToken());
    }
    static String normalize(String value) {
        if(value==null)throw new IllegalArgumentException("loginId is required");if(value.length()>120)throw new IllegalArgumentException("loginId must be 3-120 lowercase letters, numbers, dots, underscores, or hyphens");String v=Normalizer.normalize(value.strip(),Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
        if(!v.matches("[a-z0-9][a-z0-9._-]{2,119}"))throw new IllegalArgumentException("loginId must be 3-120 lowercase letters, numbers, dots, underscores, or hyphens");return v;
    }
    private static String displayName(String login,String display){String value=display==null?login:Normalizer.normalize(display.strip(),Normalizer.Form.NFC);if(value.isBlank()||value.length()>120||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("displayName must be 1-120 printable characters");return value;}
    private static String clientKey(String client){return client==null||client.isBlank()?"unknown":client;}
    private static synchronized Attempt attempt(ConcurrentHashMap<String,Attempt> map,String key,long now,int maximum){if(map.size()>=maximum&&!map.containsKey(key)){map.entrySet().removeIf(entry->now-entry.getValue().window>600_000);if(map.size()>=maximum)throw new AuthFailure(429,"ATT-AUTH-RATE-LIMITED");}return map.compute(key,(k,v)->v==null||now-v.window>600_000?new Attempt(now,0):v);}
    private static void increment(ConcurrentHashMap<String,Attempt> map,String key,long now){map.compute(key,(k,v)->v==null||now-v.window>600_000?new Attempt(now,1):new Attempt(v.window,v.failures+1));}
    static Map<String,Object> safeUser(Map<String,Object> user) {if(user==null)return Map.of();return Map.of("loginId",user.get("loginId"),"displayName",user.get("displayName"),"role",user.get("role"));}
    private static String hash(String password) throws Exception {
        validatePassword(password);return hash(password.toCharArray());
    }
    static String hash(char[] password) throws Exception {
        validatePassword(password);byte[] salt=new byte[16];RANDOM.nextBytes(salt);PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);
        try {byte[] derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return "pbkdf2-sha256$"+ITERATIONS+"$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derived);}finally{spec.clearPassword();}
    }
    private static boolean verify(String password,String encoded) throws Exception {
        if(password==null||encoded==null)return false;String[] parts=encoded.split("\\$",-1);if(parts.length!=4||!"pbkdf2-sha256".equals(parts[0]))return false;
        int rounds=Integer.parseInt(parts[1]);if(rounds<100_000||rounds>1_000_000)return false;byte[] salt=Base64.getDecoder().decode(parts[2]),expected=Base64.getDecoder().decode(parts[3]);PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,rounds,expected.length*8);
        try{return MessageDigest.isEqual(expected,SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}finally{spec.clearPassword();}
    }
    private static boolean needsUpgrade(String encoded){String[] parts=encoded.split("\\$",-1);return parts.length==4&&"pbkdf2-sha256".equals(parts[0])&&Integer.parseInt(parts[1])<ITERATIONS;}
    private static boolean validPasswordInput(String password){return password!=null&&password.length()>=12&&password.length()<=1024&&password.codePoints().noneMatch(Character::isISOControl);}
    private static void validatePassword(String password) {if(!validPasswordInput(password))throw new IllegalArgumentException("password must be 12-1024 characters without control characters");}
    private static void validatePassword(char[] password) {if(password==null||password.length<12||password.length>1024)throw new IllegalArgumentException("password must be 12-1024 characters without control characters");for(char value:password)if(Character.isISOControl(value))throw new IllegalArgumentException("password must be 12-1024 characters without control characters");}
    private void initializeBootstrapToken() throws Exception {
        if (store.authUserCount() != 0 || store.bootstrapClaimed() || validBootstrapTokenFile()) return;
        byte[] token = new byte[32]; RANDOM.nextBytes(token);
        Path temporary = Files.createTempFile(bootstrapFile.getParent(), ".bootstrap-admin-token-", ".tmp");
        try {
            secureFile(temporary);
            Files.writeString(temporary,Base64.getUrlEncoder().withoutPadding().encodeToString(token),StandardCharsets.US_ASCII,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE);
            try {
                Files.move(temporary,bootstrapFile,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary,bootstrapFile,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
    private boolean validBootstrapTokenFile() throws Exception {
        if (!Files.isRegularFile(bootstrapFile,java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(bootstrapFile) != 43) return false;
        String token = Files.readString(bootstrapFile,StandardCharsets.US_ASCII);
        if (!token.matches("[A-Za-z0-9_-]{43}")) return false;
        try { return Base64.getUrlDecoder().decode(token).length == 32; }
        catch (IllegalArgumentException malformed) { return false; }
    }
    static String recoveryCsrfToken(){return randomToken();}
    private static String randomToken(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String sha256(String token) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}
    private static void secureFile(Path file){try{Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException unsupported){try{var view=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class);if(view==null)throw new IllegalStateException("Filesystem does not support private bootstrap-token permissions");var owner=Files.getOwner(file);var entry=java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner).setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build();view.setAcl(java.util.List.of(entry));}catch(Exception e){throw new IllegalStateException("Unable to secure local authentication bootstrap token",e);}}catch(Exception e){throw new IllegalStateException("Unable to secure local authentication bootstrap token",e);}}
    private record Attempt(long window,int failures){}
    static final class AuthFailure extends RuntimeException {final int status;final String code;AuthFailure(int status,String code){super("Authentication request could not be completed");this.status=status;this.code=code;}}
}
