package att.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationFilterTest {
    @Test void preservesCommittedContainerAuthenticationResponse() throws Exception {
        ByteArrayOutputStream body=new ByteArrayOutputStream();AtomicBoolean committed=new AtomicBoolean();int[] status={200};boolean[] chained={false};
        ServletOutputStream output=new ServletOutputStream(){@Override public void write(int value){body.write(value);committed.set(true);}@Override public boolean isReady(){return true;}@Override public void setWriteListener(WriteListener listener){}};
        HttpServletResponse response=(HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpServletResponse.class},(proxy,method,args)->switch(method.getName()){
            case "setStatus"->{status[0]=(Integer)args[0];yield null;}case "getStatus"->status[0];case "setHeader","setCharacterEncoding","setContentType"->null;case "getOutputStream"->output;case "isCommitted"->committed.get();case "toString"->"response";default->defaultValue(method.getReturnType());});
        ServletContext context=(ServletContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ServletContext.class},(proxy,method,args)->switch(method.getName()){case "getAttribute"->null;case "toString"->"context";default->defaultValue(method.getReturnType());});
        HttpServletRequest request=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpServletRequest.class},(proxy,method,args)->switch(method.getName()){
            case "getPathInfo"->"/packages";case "getMethod"->"GET";case "getServletContext"->context;case "getUserPrincipal"->(Principal)null;
            case "authenticate"->{response.setStatus(302);response.setHeader("Location","/login");response.getOutputStream().write("container login response".getBytes(StandardCharsets.UTF_8));yield false;}
            case "toString"->"request";default->defaultValue(method.getReturnType());});
        FilterChain chain=(req,res)->chained[0]=true;
        new AuthenticationFilter().doFilter(request,response,chain);
        assertEquals(302,status[0]);assertEquals("container login response",body.toString(StandardCharsets.UTF_8));assertFalse(chained[0]);
    }
    private static Object defaultValue(Class<?> type){if(!type.isPrimitive())return null;if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
}
