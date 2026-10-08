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
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Uses the Servlet container Realm for HTTP Basic authentication and supports explicit anonymous mode. */
@WebFilter(urlPatterns="/api/v1/*",asyncSupported=true)
public final class AuthenticationFilter implements Filter {
    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) throws IOException,ServletException {
        HttpServletRequest req=(HttpServletRequest)request;HttpServletResponse res=(HttpServletResponse)response;
        String path=req.getPathInfo();boolean publicRead="GET".equals(req.getMethod())&&("/health".equals(path)||"/version".equals(path));
        Object runtimeValue=req.getServletContext().getAttribute(ServerBootstrap.RUNTIME);ServerRuntime runtime=runtimeValue instanceof ServerRuntime?(ServerRuntime)runtimeValue:null;
        if(publicRead||runtime!=null&&!runtime.config.authenticationRequired){chain.doFilter(request,response);return;}
        Principal principal=req.getUserPrincipal();
        if(principal==null&&loginBasic(req)) { try {String[] credentials=credentials(req.getHeader("Authorization"));req.login(credentials[0],credentials[1]);principal=req.getUserPrincipal();}catch(Exception rejected){principal=null;} }
        if(principal!=null&&req.isUserInRole("ATT_USER")){chain.doFilter(request,response);return;}
        if(runtime!=null)try{runtime.store.audit("anonymous","AUTH_REJECT",null,null,principal==null?"MISSING_PRINCIPAL":"ROLE_REQUIRED");}catch(Exception ignored){}
        String id=req.getHeader("X-Request-ID");if(id==null||!id.matches("[A-Za-z0-9._-]{1,80}"))id=UUID.randomUUID().toString();res.setHeader("X-Request-ID",id);res.setStatus(principal==null?401:403);if(principal==null)res.setHeader("WWW-Authenticate","Basic realm=\"ATT Server\", charset=\"UTF-8\"");res.setCharacterEncoding("UTF-8");res.setContentType("application/json");
        Map<String,Object> error=new LinkedHashMap<>();error.put("code",principal==null?"ATT-SERVER-AUTHENTICATION-REQUIRED":"ATT-SERVER-ROLE-REQUIRED");error.put("summary",principal==null?"A valid HTTP Basic credential is required":"The authenticated principal must have the ATT_USER role");error.put("detail",error.get("summary"));error.put("requestId",id);ServerRuntime.JSON.writeValue(res.getOutputStream(),Map.of("error",error));
    }
    private static boolean loginBasic(HttpServletRequest req){String value=req.getHeader("Authorization");return value!=null&&value.regionMatches(true,0,"Basic ",0,6);}
    private static String[] credentials(String authorization){if(authorization==null||!authorization.regionMatches(true,0,"Basic ",0,6))throw new IllegalArgumentException("Basic credentials are required");byte[] decoded=Base64.getDecoder().decode(authorization.substring(6).trim());String pair=new String(decoded,StandardCharsets.UTF_8);int colon=pair.indexOf(':');if(colon<1)throw new IllegalArgumentException("Invalid Basic credentials");return new String[]{pair.substring(0,colon),pair.substring(colon+1)};}
}
