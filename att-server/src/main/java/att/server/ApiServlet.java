package att.server;

import att.Version;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@WebServlet(urlPatterns="/api/v1/*",asyncSupported=true)
public final class ApiServlet extends HttpServlet {
    private ServerRuntime runtime;
    @Override public void init() throws ServletException {Object r=getServletContext().getAttribute(ServerBootstrap.RUNTIME);if(!(r instanceof ServerRuntime))throw new ServletException("ATT Server runtime did not initialize");runtime=(ServerRuntime)r;}
    @Override protected void doGet(HttpServletRequest req,HttpServletResponse res) throws IOException {
        String path=path(req);String requestId=requestId(req,res);
        try {
            if("/health".equals(path)){json(res,200,Map.of("status","UP","version",Version.PRODUCT,"requestId",requestId));return;}
            if("/version".equals(path)){json(res,200,Map.of("version",Version.PRODUCT,"buildTime",Version.BUILD_TIME,"gitCommit",Version.GIT_COMMIT,"javaMinimum",17,"requestId",requestId));return;}
            String principal=principal(req);if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
            if("/metrics".equals(path)){Map<String,Object> metrics=runtime.counts();metrics.put("requestId",requestId);json(res,200,metrics);return;}
            if("/packages".equals(path)){json(res,200,Map.of("items",runtime.packages(),"requestId",requestId));return;}
            if(path.startsWith("/packages/")){String id=segment(path,2);json(res,200,runtime.packageView(id));return;}
            if("/jobs".equals(path)){json(res,200,Map.of("items",runtime.store.list(100),"requestId",requestId));return;}
            if(path.matches("/jobs/[^/]+/events")){sse(req,res,segment(path,2));return;}
            if(path.startsWith("/jobs/")&&path.contains("/artifacts/")){String prefix=path.substring(0,path.indexOf("/artifacts/"));String id=segment(prefix,2);String relative=path.substring(path.indexOf("/artifacts/")+11);sendArtifact(res,runtime.artifact(id,relative));return;}
            if(path.matches("/jobs/[^/]+/artifacts")){String id=segment(path,2);json(res,200,Map.of("items",artifacts(id),"requestId",requestId));return;}
            if(path.matches("/jobs/[^/]+/result")){Map<String,Object> result=runtime.resultRecord(segment(path,2));result.put("requestId",requestId);json(res,200,result);return;}
            if(path.matches("/jobs/[^/]+")){Map<String,Object> view=runtime.jobRecord(segment(path,2));view.put("requestId",requestId);json(res,200,view);return;}
            error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);
        } catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
          catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The request could not be completed",requestId);getServletContext().log("ATT Server request failed id="+requestId,e);}
    }
    @Override protected void doPost(HttpServletRequest req,HttpServletResponse res) throws IOException {
        String requestId=requestId(req,res),path=path(req);String principal=principal(req);
        if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
        String command=path.startsWith("/jobs/")?path.substring("/jobs/".length()):"";
        if(!List.of("run","debug","load","validate").contains(command)){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return;}
        try {
            byte[] body=req.getInputStream().readNBytes(runtime.config.maxRequestBytes+1);
            if(body.length>runtime.config.maxRequestBytes){error(res,413,"ATT-SERVER-REQUEST-TOO-LARGE","Request body exceeds the configured size limit",requestId);return;}
            JsonNode input=ServerRuntime.JSON.readTree(body);
            if(input==null||!input.isObject())throw new IllegalArgumentException("A JSON object is required");
            Map<String,Object> job=runtime.submit(command,input,principal);job.put("requestId",requestId);res.setHeader("Location",req.getContextPath()+"/api/v1/jobs/"+job.get("jobId"));json(res,202,job);
        } catch(ServerRuntime.QueueFullException e){error(res,429,"ATT-SERVER-CAPACITY-EXCEEDED","Worker capacity is full; retry after a job completes",requestId);}
          catch(IllegalArgumentException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(e),requestId);}
          catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The request could not be completed",requestId);getServletContext().log("ATT Server submission failed id="+requestId,e);}
    }
    @Override protected void doDelete(HttpServletRequest req,HttpServletResponse res) throws IOException {
        String requestId=requestId(req,res),path=path(req),principal=principal(req);
        if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
        if(!path.matches("/jobs/[^/]+")){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return;}
        try{String id=segment(path,2);runtime.cancel(id,principal);json(res,200,Map.of("jobId",id,"status",runtime.job(id).status,"requestId",requestId));}
        catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
        catch(Exception e){error(res,500,"ATT-SERVER-CANCEL-FAILED","The job could not be cancelled",requestId);}
    }
    @Override protected void doPut(HttpServletRequest req,HttpServletResponse res) throws IOException {error(res,405,"ATT-SERVER-METHOD-NOT-ALLOWED","Package administration is read-only in v1",requestId(req,res));}

    private void sse(HttpServletRequest req,HttpServletResponse res,String jobId) throws IOException {
        String last=req.getHeader("Last-Event-ID");long cursor=0;
        if(last!=null&&!last.isBlank())try{cursor=Long.parseLong(last);}catch(NumberFormatException e){error(res,400,"ATT-SERVER-INVALID-LAST-EVENT-ID","Last-Event-ID must be a non-negative integer",requestId(req,res));return;}
        if(cursor<0){error(res,400,"ATT-SERVER-INVALID-LAST-EVENT-ID","Last-Event-ID must be a non-negative integer",requestId(req,res));return;}
        if(runtime.streams.getQueue().remainingCapacity()==0){error(res,503,"ATT-SERVER-STREAM-CAPACITY","SSE observer capacity is full",requestId(req,res));return;}
        final long start=cursor;res.setStatus(200);res.setCharacterEncoding("UTF-8");res.setContentType("text/event-stream");res.setHeader("Cache-Control","no-cache, no-transform");res.setHeader("X-Accel-Buffering","no");
        JobEvents events;try{events=runtime.events(jobId);}catch(ServerRuntime.NotFoundException missing){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId(req,res));return;}catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The event journal is unavailable",requestId(req,res));return;}
        AsyncContext async=req.startAsync();async.setTimeout(0);
        try{runtime.streams.execute(()->stream(async,jobId,events,start));}catch(RejectedExecutionException full){async.complete();}
    }
    private void stream(AsyncContext async,String jobId,JobEvents journal,long cursor){
        long lastWrite=System.nanoTime();java.util.concurrent.ArrayBlockingQueue<Boolean> wakeup=new java.util.concurrent.ArrayBlockingQueue<>(1);
        AutoCloseable subscription=journal.listen(event->wakeup.offer(Boolean.TRUE));wakeup.offer(Boolean.TRUE);
        try(PrintWriter out=async.getResponse().getWriter()){
            while(true){
                wakeup.poll(15,TimeUnit.SECONDS);
                List<Map<String,Object>> events=journal.after(cursor,100);
                for(Map<String,Object> event:events){long id=((Number)event.get("id")).longValue();out.print("id: "+id+"\nevent: "+event.get("event")+"\ndata: "+ServerRuntime.JSON.writeValueAsString(event.get("data"))+"\n\n");out.flush();if(out.checkError())return;cursor=id;lastWrite=System.nanoTime();}
                if(runtime.terminal(jobId)&&!journal.hasMore(cursor))break;
                if(journal.hasMore(cursor))wakeup.offer(Boolean.TRUE);
                if(System.nanoTime()-lastWrite>TimeUnit.SECONDS.toNanos(20)){out.print(": keepalive\n\n");out.flush();if(out.checkError())return;lastWrite=System.nanoTime();}
            }
        }catch(Exception ignored){}finally{try{subscription.close();}catch(Exception ignored){}async.complete();}
    }
    private List<Map<String,Object>> artifacts(String jobId) throws Exception {
        runtime.jobRecord(jobId);Path root=runtime.config.dataDir.resolve("jobs").resolve(jobId).resolve("output").toRealPath();List<Map<String,Object>> items=new ArrayList<>();
        try(var paths=Files.walk(root)){paths.filter(Files::isRegularFile).filter(p->!Files.isSymbolicLink(p)).limit(runtime.config.maxArtifacts+1L).forEach(p->{if(items.size()<runtime.config.maxArtifacts){Map<String,Object> m=new LinkedHashMap<>();m.put("path",root.relativize(p).toString().replace('\\','/'));try{m.put("size",Files.size(p));}catch(IOException ignored){}items.add(m);}});}
        return items;
    }
    private void sendArtifact(HttpServletResponse response,Path file)throws IOException {
        String name=file.getFileName().toString().replaceAll("[\r\n\"]","_");response.setContentType(getServletContext().getMimeType(name)==null?"application/octet-stream":getServletContext().getMimeType(name));response.setHeader("Content-Disposition","attachment; filename=\""+name+"\"");response.setContentLengthLong(Files.size(file));Files.copy(file,response.getOutputStream());
    }
    private static String path(HttpServletRequest r){String p=r.getPathInfo();return p==null||p.isEmpty()?"/":p;}
    private static String segment(String path,int index){String[] parts=path.split("/");return parts.length>index?parts[index]:"";}
    static String principal(HttpServletRequest req){Principal p=req.getUserPrincipal();if(p==null)return null;String name=p.getName();if(name==null||name.isBlank()||name.length()>256)return null;for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))return null;return name.trim();}
    private static String requestId(HttpServletRequest req,HttpServletResponse res){String value=req.getHeader("X-Request-ID");if(value==null||!value.matches("[A-Za-z0-9._-]{1,80}"))value=UUID.randomUUID().toString();res.setHeader("X-Request-ID",value);return value;}
    private static void json(HttpServletResponse res,int status,Object body)throws IOException{res.setStatus(status);res.setCharacterEncoding("UTF-8");res.setContentType("application/json");ServerRuntime.JSON.writeValue(res.getOutputStream(),body);}
    private static void error(HttpServletResponse res,int status,String code,String summary,String requestId)throws IOException{Map<String,Object> e=new LinkedHashMap<>();e.put("code",code);e.put("summary",summary);e.put("detail",summary);e.put("requestId",requestId);json(res,status,Map.of("error",e));}
    private static String safeDetail(IllegalArgumentException e){String m=e.getMessage();return m==null?"Invalid request":m.length()>300?m.substring(0,300):m;}
}
