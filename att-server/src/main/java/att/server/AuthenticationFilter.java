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

/** Consumes container-authenticated Principals and supports explicit anonymous mode. */
@WebFilter(urlPatterns="/api/v1/*",asyncSupported=true)
public final class AuthenticationFilter implements Filter {
    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) throws IOException,ServletException {
        HttpServletRequest req=(HttpServletRequest)request;HttpServletResponse res=(HttpServletResponse)response;
        String path=req.getPathInfo();boolean publicRead="GET".equals(req.getMethod())&&("/health".equals(path)||"/version".equals(path));
        Object runtimeValue=req.getServletContext().getAttribute(ServerBootstrap.RUNTIME);ServerRuntime runtime=runtimeValue instanceof ServerRuntime?(ServerRuntime)runtimeValue:null;
        if(publicRead||runtime!=null&&!runtime.config.authenticationRequired){chain.doFilter(request,response);return;}
        Principal principal=req.getUserPrincipal();
        if(principal==null)try{req.authenticate(res);principal=req.getUserPrincipal();}catch(Exception rejected){principal=null;}
        if(principal!=null){chain.doFilter(request,response);return;}
        if(runtime!=null)try{runtime.store.audit("anonymous","AUTH_REJECT",null,null,"MISSING_PRINCIPAL");}catch(Exception ignored){}
        // A failed authenticate() call leaves the response to the container. It may
        // already contain a BASIC challenge, FORM redirect, or SSO response.
    }
}
