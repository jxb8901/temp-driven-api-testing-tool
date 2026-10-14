package att.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;

/** Resolves the configured container or ATT-local identity before protected operations. */
@WebFilter(urlPatterns={"/api/v1/*","/ui/*"},asyncSupported=true)
public final class AuthenticationFilter implements Filter {
    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) throws IOException,ServletException {
        HttpServletRequest req=(HttpServletRequest)request;HttpServletResponse res=(HttpServletResponse)response;
        String path=req.getPathInfo();boolean publicRead="GET".equals(req.getMethod())&&("/health".equals(path)||"/version".equals(path));
        String contextPath=req.getContextPath()==null?"":req.getContextPath();String requestUri=req.getRequestURI();
        String uiPath=requestUri==null?"":requestUri.substring(Math.min(contextPath.length(),requestUri.length()));
        Object runtimeValue=req.getServletContext().getAttribute(ServerBootstrap.RUNTIME);ServerRuntime runtime=runtimeValue instanceof ServerRuntime?(ServerRuntime)runtimeValue:null;
        if(runtime!=null&&runtime.auth.local(runtime.config)) {
            res.setHeader("Cache-Control","no-store");
            boolean publicAuthRoute="/auth/login".equals(path)||"/auth/bootstrap".equals(path)||"/auth/bootstrap/status".equals(path);
            boolean loginAsset=uiPath.equals("/ui/login.html")||uiPath.equals("/ui/login.js")||uiPath.equals("/ui/login.css");
            if(publicRead||publicAuthRoute||loginAsset){chain.doFilter(request,response);return;}
            java.util.Map<String,String> session;
            try {session=runtime.auth.session(req);}catch(Exception failure){throw new ServletException("Unable to resolve ATT session",failure);}
            if(session==null&&req.getHeader("Authorization")!=null) {
                if(!req.isSecure()){jsonAuthError(res,400,"ATT-AUTH-HTTPS-REQUIRED","Basic credentials require HTTPS");return;}
                java.util.Map<String,Object> user;
                try {
                    user=runtime.auth.verifyCredentials(basicUsername(req.getHeader("Authorization")),basicPassword(req.getHeader("Authorization")),req.getRemoteAddr());
                } catch(Exception rejected) {
                    String code=rejected instanceof AuthService.AuthFailure failure?failure.code:"ATT-AUTH-INVALID-CREDENTIALS";
                    try{runtime.store.audit("anonymous","BASIC_AUTH",null,null,code);}catch(Exception ignored){}
                    jsonAuthError(res,rejected instanceof AuthService.AuthFailure failure?failure.status:401,code,"Authentication request could not be completed");return;
                }
                String csrf=(String)user.get("csrfToken");
                if(!"GET".equalsIgnoreCase(req.getMethod())&& !"HEAD".equalsIgnoreCase(req.getMethod())&& !"OPTIONS".equalsIgnoreCase(req.getMethod())&&req.getHeader("Origin")!=null) {
                    String supplied=req.getHeader("X-ATT-CSRF");
                    if(!sameOrigin(req)||supplied==null||!java.security.MessageDigest.isEqual(csrf.getBytes(java.nio.charset.StandardCharsets.US_ASCII),supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII))){jsonForbidden(res);return;}
                }
                req.setAttribute(AuthService.IDENTITY,user.get("loginId"));req.setAttribute(AuthService.BASIC_CSRF,csrf);res.setHeader("X-ATT-CSRF",csrf);
                try{runtime.store.audit((String)user.get("loginId"),"BASIC_AUTH",null,null,"SUCCESS");}catch(Exception ignored){}
                chain.doFilter(request,response);return;
            }
            if(session==null) {
                if(uiPath.startsWith("/ui/")) {String target=uiPath+(req.getQueryString()==null?"":"?"+req.getQueryString());res.sendRedirect(contextPath+"/ui/login.html?returnTo="+java.net.URLEncoder.encode(target,java.nio.charset.StandardCharsets.UTF_8));return;}
                jsonUnauthorized(res);return;
            }
            String csrf=session.get("csrfToken");
            if(!"GET".equalsIgnoreCase(req.getMethod())&&!"HEAD".equalsIgnoreCase(req.getMethod())&&!"OPTIONS".equalsIgnoreCase(req.getMethod())) {
                String supplied=req.getHeader("X-ATT-CSRF");
                if(req.getHeader("Origin")!=null&&!sameOrigin(req)||supplied==null||!java.security.MessageDigest.isEqual(csrf.getBytes(java.nio.charset.StandardCharsets.US_ASCII),supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII))){jsonForbidden(res);return;}
            }
            req.setAttribute(AuthService.IDENTITY,session.get("loginId"));req.setAttribute(AuthService.SESSION,session);res.setHeader("X-ATT-CSRF",csrf);
            chain.doFilter(request,response);return;
        }
        if(publicRead||runtime!=null&&!runtime.config.authenticationRequired){chain.doFilter(request,response);return;}
        Principal principal=req.getUserPrincipal();
        if(principal==null){req.authenticate(res);principal=req.getUserPrincipal();}
        if(principal!=null){chain.doFilter(request,response);return;}
        if(runtime!=null)try{runtime.store.audit("anonymous","AUTH_REJECT",null,null,"MISSING_PRINCIPAL");}catch(Exception ignored){}
        // A failed authenticate() call leaves the response to the container. It may
        // already contain a BASIC challenge, FORM redirect, or SSO response.
    }
    private static String basicUsername(String header)throws Exception {String[] parts=basicParts(header);int split=parts[0].indexOf(':');if(split<0)throw new IllegalArgumentException("Invalid credentials");return parts[0].substring(0,split);}
    private static String basicPassword(String header)throws Exception {String[] parts=basicParts(header);int split=parts[0].indexOf(':');if(split<0)throw new IllegalArgumentException("Invalid credentials");return parts[0].substring(split+1);}
    private static String[] basicParts(String header)throws Exception {if(header==null||!header.regionMatches(true,0,"Basic ",0,6))throw new IllegalArgumentException("Invalid credentials");byte[] decoded=java.util.Base64.getDecoder().decode(header.substring(6).trim());String value=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(decoded)).toString();if(value.indexOf('\n')>=0||value.indexOf('\r')>=0||value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid credentials");return new String[]{value};}
    private static boolean sameOrigin(HttpServletRequest req){String origin=req.getHeader("Origin");if(origin==null)return true;if("null".equalsIgnoreCase(origin.trim()))return false;try{java.net.URI parsed=java.net.URI.create(origin);if(parsed.getHost()==null||parsed.getUserInfo()!=null||parsed.getRawPath()!=null&&!parsed.getRawPath().isEmpty()||parsed.getRawQuery()!=null||parsed.getFragment()!=null)return false;String scheme=req.getScheme().toLowerCase(java.util.Locale.ROOT),originScheme=parsed.getScheme().toLowerCase(java.util.Locale.ROOT);int requestPort=req.getServerPort(),originPort=parsed.getPort()<0?("https".equals(originScheme)?443:80):parsed.getPort();int effectiveRequest=requestPort<0?("https".equals(scheme)?443:80):requestPort;return scheme.equals(originScheme)&&req.getServerName().equalsIgnoreCase(parsed.getHost())&&effectiveRequest==originPort;}catch(Exception invalid){return false;}}
    private static void jsonAuthError(HttpServletResponse res,int status,String code,String summary)throws IOException {String id=java.util.UUID.randomUUID().toString();res.setHeader("X-Request-ID",id);res.setStatus(status);res.setContentType("application/json");res.setCharacterEncoding("UTF-8");res.getWriter().write("{\"error\":{\"code\":\""+code+"\",\"summary\":\""+summary+"\",\"detail\":\""+summary+"\",\"requestId\":\""+id+"\"}}");}
    private static void jsonUnauthorized(HttpServletResponse res)throws IOException {String id=java.util.UUID.randomUUID().toString();res.setHeader("X-Request-ID",id);res.setStatus(401);res.setContentType("application/json");res.setCharacterEncoding("UTF-8");res.getWriter().write("{\"error\":{\"code\":\"ATT-SERVER-AUTHENTICATION-REQUIRED\",\"summary\":\"Authentication is required\",\"detail\":\"Authentication is required\",\"requestId\":\""+id+"\"}}");}
    private static void jsonForbidden(HttpServletResponse res)throws IOException {String id=java.util.UUID.randomUUID().toString();res.setHeader("X-Request-ID",id);res.setStatus(403);res.setContentType("application/json");res.setCharacterEncoding("UTF-8");res.getWriter().write("{\"error\":{\"code\":\"ATT-AUTH-CSRF-INVALID\",\"summary\":\"Request verification failed\",\"detail\":\"Request verification failed\",\"requestId\":\""+id+"\"}}");}
}
