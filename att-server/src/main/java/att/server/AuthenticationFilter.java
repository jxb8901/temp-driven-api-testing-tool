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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Fails closed for every API method except deployment-policy health/version reads. */
@WebFilter(urlPatterns="/api/v1/*",asyncSupported=true)
public final class AuthenticationFilter implements Filter {
    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) throws IOException,ServletException {
        HttpServletRequest req=(HttpServletRequest)request;HttpServletResponse res=(HttpServletResponse)response;
        String path=req.getPathInfo();boolean publicRead="GET".equals(req.getMethod())&&("/health".equals(path)||"/version".equals(path));
        if(publicRead||ApiServlet.principal(req)!=null){chain.doFilter(request,response);return;}
        Object runtime=req.getServletContext().getAttribute(ServerBootstrap.RUNTIME);
        if(runtime instanceof ServerRuntime server)try{server.store.audit("anonymous","AUTH_REJECT",null,null,"MISSING_PRINCIPAL");}catch(Exception ignored){}
        String id=req.getHeader("X-Request-ID");if(id==null||!id.matches("[A-Za-z0-9._-]{1,80}"))id=UUID.randomUUID().toString();res.setHeader("X-Request-ID",id);res.setStatus(401);res.setCharacterEncoding("UTF-8");res.setContentType("application/json");
        Map<String,Object> error=new LinkedHashMap<>();error.put("code","ATT-SERVER-AUTHENTICATION-REQUIRED");error.put("summary","An authenticated Servlet Principal is required");error.put("detail","An authenticated Servlet Principal is required");error.put("requestId",id);ServerRuntime.JSON.writeValue(res.getOutputStream(),Map.of("error",error));
    }
}
