package att.server;

import att.Version;
import att.server.api.ConfigurationInspection;
import att.server.api.DebugDraft;
import att.server.api.DebugForm;
import att.server.api.LoadDraft;
import att.server.api.LoadPolicyForm;
import att.server.api.QuickLoadForm;
import att.server.api.ResourceInspection;
import att.server.api.ServerApi;
import com.fasterxml.jackson.core.JsonProcessingException;
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
            if("/health".equals(path)){json(res,200,Map.of("status","UP","version",Version.PRODUCT,"apiVersion",ServerApi.VERSION,"requestId",requestId));return;}
            if("/version".equals(path)){json(res,200,Map.of("version",Version.PRODUCT,"apiVersion",ServerApi.VERSION,"buildTime",Version.BUILD_TIME,"gitCommit",Version.GIT_COMMIT,"javaMinimum",17,"inlineLoadEnabled",runtime.config.inlineLoad.enabled,"requestId",requestId));return;}
            boolean resourceRequest=resourcePath(path);
            boolean configurationRequest=configurationPath(path);
            boolean loadPolicyRequest=loadPolicyPath(path);
            boolean draftRequest=path.matches("/drafts/[DLA][0-9A-F]{32}");
            boolean inspectionRequest=resourceRequest||configurationRequest||loadPolicyRequest||draftRequest;
            if(inspectionRequest&&req.getUserPrincipal()==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
            String principal=principal(req);if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
            if("/metrics".equals(path)){Map<String,Object> metrics=runtime.counts();metrics.put("requestId",requestId);json(res,200,metrics);return;}
            if(resourceRequest){resourceGet(req,res,path,requestId,req.getUserPrincipal().getName());return;}
            if(configurationRequest){configurationGet(req,res,path,requestId,req.getUserPrincipal().getName());return;}
            if(loadPolicyRequest){loadPolicyGet(req,res,path,requestId,req.getUserPrincipal().getName());return;}
            if(draftRequest){
                String draftId=segment(path,2);
                if(draftId.startsWith("L")){LoadDraft response=ServerRuntime.JSON.convertValue(runtime.getQuickLoadDraft(draftId,req.getUserPrincipal().getName()),LoadDraft.class);response.requestId=requestId;json(res,200,response);}
                else if(draftId.startsWith("A")){LoadDraft response=ServerRuntime.JSON.convertValue(runtime.getAdvancedLoadDraft(draftId,req.getUserPrincipal().getName()),LoadDraft.class);response.requestId=requestId;json(res,200,response);}
                else {DebugDraft response=ServerRuntime.JSON.convertValue(runtime.getDebugDraft(draftId,req.getUserPrincipal().getName()),DebugDraft.class);response.requestId=requestId;json(res,200,response);}
                return;
            }
            if("/packages".equals(path)){json(res,200,Map.of("items",runtime.packages(),"requestId",requestId));return;}
            if(path.startsWith("/packages/")){String id=segment(path,2);json(res,200,runtime.packageView(id));return;}
            if("/jobs".equals(path)){json(res,200,Map.of("items",runtime.store.list(100),"requestId",requestId));return;}
            if(path.matches("/jobs/[^/]+/events")){sse(req,res,segment(path,2));return;}
            if(path.startsWith("/jobs/")&&path.contains("/artifacts/")){String prefix=path.substring(0,path.indexOf("/artifacts/"));String id=segment(prefix,2);String relative=path.substring(path.indexOf("/artifacts/")+11);sendArtifact(res,runtime.artifact(id,relative));return;}
            if(path.matches("/jobs/[^/]+/artifacts")){String id=segment(path,2);json(res,200,Map.of("items",artifacts(id),"requestId",requestId));return;}
            if(path.matches("/jobs/[^/]+/result")){Map<String,Object> result=runtime.resultRecord(segment(path,2));result.put("requestId",requestId);json(res,200,result);return;}
            if(path.matches("/jobs/[^/]+")){Map<String,Object> view=runtime.jobRecord(segment(path,2));view.put("requestId",requestId);json(res,200,view);return;}
            error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);
        } catch(ServerRuntime.InlineLoadDisabledException e){error(res,403,"ATT-SERVER-INLINE-LOAD-DISABLED","Browser-submitted Load is disabled by Server configuration",requestId);}
          catch(ServerRuntime.DraftValidationException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",e.getMessage(),requestId,e.diagnostics);}
          catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
          catch(ServerRuntime.StaleCursorException e){error(res,409,"ATT-RESOURCE-CURSOR-STALE","The package resources changed; refresh the Explorer",requestId);}
          catch(ServerRuntime.StaleDraftException e){error(res,409,"ATT-SERVER-DRAFT-STALE","Package content changed after preview; rebuild the draft",requestId);}
          catch(ServerRuntime.InspectionCapacityException e){error(res,503,"ATT-SERVER-INSPECTION-CAPACITY","Package inspection capacity is full; retry shortly",requestId);}
          catch(ServerRuntime.DraftCapacityException e){error(res,429,"ATT-SERVER-DRAFT-CAPACITY","Draft capacity is full; retry after an existing draft expires",requestId);}
          catch(ServerRuntime.InspectionTimeoutException e){error(res,504,"ATT-SERVER-INSPECTION-TIMEOUT","Package inspection exceeded its time limit",requestId);}
          catch(ServerRuntime.InspectionResponseTooLargeException e){error(res,413,"ATT-SERVER-INSPECTION-RESPONSE-TOO-LARGE","Package inspection response exceeded the configured limit",requestId);}
          catch(IllegalArgumentException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(e),requestId);}
          catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The request could not be completed",requestId);getServletContext().log("ATT Server request failed id="+requestId,e);}
    }
    @Override protected void doPost(HttpServletRequest req,HttpServletResponse res) throws IOException {
        String requestId=requestId(req,res),path=path(req);String principal=principal(req);
        if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
        boolean createDebugDraft="/drafts/debug".equals(path),createQuickLoadDraft="/drafts/quick-load".equals(path),createAdvancedLoadDraft="/drafts/load".equals(path);
        String command=path.startsWith("/jobs/")?path.substring("/jobs/".length()):"";
        if(!createDebugDraft&&!createQuickLoadDraft&&!createAdvancedLoadDraft&&!List.of("run","debug","load","validate").contains(command)){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return;}
        if((createDebugDraft||createQuickLoadDraft||createAdvancedLoadDraft)&&req.getUserPrincipal()==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
        try {
            if(!isJson(req.getContentType())){error(res,415,"ATT-SERVER-UNSUPPORTED-MEDIA-TYPE","Content-Type must be application/json",requestId);return;}
            if(!sameOrigin(req)){error(res,403,"ATT-SERVER-CROSS-ORIGIN-REQUEST","State-changing requests must use the same origin",requestId);return;}
            byte[] body=req.getInputStream().readNBytes(runtime.config.maxRequestBytes+1);
            if(body.length>runtime.config.maxRequestBytes){error(res,413,"ATT-SERVER-REQUEST-TOO-LARGE","Request body exceeds the configured size limit",requestId);return;}
            JsonNode input;
            try{input=ServerRuntime.JSON.readTree(body);}catch(JsonProcessingException malformed){error(res,400,"ATT-SERVER-INVALID-REQUEST","Request body is not valid JSON",requestId);return;}
            if(input==null||!input.isObject())throw new IllegalArgumentException("A JSON object is required");
            if(createDebugDraft){DebugDraft draft=ServerRuntime.JSON.convertValue(runtime.createDebugDraft(input,req.getUserPrincipal().getName()),DebugDraft.class);draft.requestId=requestId;json(res,201,draft);return;}
            if(createQuickLoadDraft){LoadDraft draft=ServerRuntime.JSON.convertValue(runtime.createQuickLoadDraft(input,req.getUserPrincipal().getName()),LoadDraft.class);draft.requestId=requestId;json(res,201,draft);return;}
            if(createAdvancedLoadDraft){LoadDraft draft=ServerRuntime.JSON.convertValue(runtime.createAdvancedLoadDraft(input,req.getUserPrincipal().getName()),LoadDraft.class);draft.requestId=requestId;json(res,201,draft);return;}
            if("debug".equals(command)&&input.has("draftId")){
                if(req.getUserPrincipal()==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
                Map<String,Object> job=runtime.submitDebugDraft(input,req.getUserPrincipal().getName());job.put("requestId",requestId);res.setHeader("Location",req.getContextPath()+"/api/v1/jobs/"+job.get("jobId"));json(res,202,job);return;
            }
            if("load".equals(command)&&input.has("draftId")){
                if(req.getUserPrincipal()==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
                Map<String,Object> job=runtime.submitLoadDraft(input,req.getUserPrincipal().getName());job.put("requestId",requestId);res.setHeader("Location",req.getContextPath()+"/api/v1/jobs/"+job.get("jobId"));json(res,202,job);return;
            }
            Map<String,Object> job=runtime.submit(command,input,principal);job.put("requestId",requestId);res.setHeader("Location",req.getContextPath()+"/api/v1/jobs/"+job.get("jobId"));json(res,202,job);
        } catch(ServerRuntime.InlineLoadDisabledException e){error(res,403,"ATT-SERVER-INLINE-LOAD-DISABLED","Browser-submitted Load is disabled by Server configuration",requestId);}
          catch(ServerRuntime.QueueFullException e){error(res,429,"ATT-SERVER-CAPACITY-EXCEEDED",e.getMessage(),requestId);}
          catch(ServerRuntime.DraftValidationException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",e.getMessage(),requestId,e.diagnostics);}
          catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
          catch(ServerRuntime.StaleDraftException e){error(res,409,"ATT-SERVER-DRAFT-STALE","Package content changed after preview; rebuild the draft",requestId);}
          catch(ServerRuntime.DraftCapacityException e){error(res,429,"ATT-SERVER-DRAFT-CAPACITY","Draft capacity is full; retry after an existing draft expires",requestId);}
          catch(ServerRuntime.InspectionCapacityException e){error(res,503,"ATT-SERVER-INSPECTION-CAPACITY","Package inspection capacity is full; retry shortly",requestId);}
          catch(ServerRuntime.InspectionTimeoutException e){error(res,504,"ATT-SERVER-INSPECTION-TIMEOUT","Package inspection exceeded its time limit",requestId);}
          catch(ServerRuntime.InspectionResponseTooLargeException e){error(res,413,"ATT-SERVER-INSPECTION-RESPONSE-TOO-LARGE","Package inspection response exceeded the configured limit",requestId);}
          catch(IllegalArgumentException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(e),requestId);}
          catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The request could not be completed",requestId);getServletContext().log("ATT Server submission failed id="+requestId,e);}
    }
    @Override protected void doDelete(HttpServletRequest req,HttpServletResponse res) throws IOException {
        String requestId=requestId(req,res),path=path(req),principal=principal(req);
        if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
        if(!path.matches("/jobs/[^/]+")){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return;}
        if(!sameOrigin(req)){error(res,403,"ATT-SERVER-CROSS-ORIGIN-REQUEST","State-changing requests must use the same origin",requestId);return;}
        try{String id=segment(path,2);String status=runtime.cancel(id,principal);json(res,200,Map.of("jobId",id,"status",status,"requestId",requestId));}
        catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
        catch(Exception e){error(res,500,"ATT-SERVER-CANCEL-FAILED","The job could not be cancelled",requestId);}
    }
    @Override protected void doPut(HttpServletRequest req,HttpServletResponse res) throws IOException {error(res,405,"ATT-SERVER-METHOD-NOT-ALLOWED","Package administration is read-only in v1",requestId(req,res));}

    private void sse(HttpServletRequest req,HttpServletResponse res,String jobId) throws IOException {
        String last=req.getHeader("Last-Event-ID");long cursor=0;
        if(last!=null&&!last.isBlank())try{cursor=Long.parseLong(last);}catch(NumberFormatException e){error(res,400,"ATT-SERVER-INVALID-LAST-EVENT-ID","Last-Event-ID must be a non-negative integer",requestId(req,res));return;}
        if(cursor<0){error(res,400,"ATT-SERVER-INVALID-LAST-EVENT-ID","Last-Event-ID must be a non-negative integer",requestId(req,res));return;}
        JobEvents events;Path packageRoot;Path outputDirectory;try{events=runtime.events(jobId);packageRoot=runtime.packageRootForJob(jobId);outputDirectory=runtime.outputDirectoryForJob(jobId);}catch(ServerRuntime.NotFoundException missing){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId(req,res));return;}catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The event journal is unavailable",requestId(req,res));return;}
        if(!runtime.streamSlots.tryAcquire()){error(res,503,"ATT-SERVER-STREAM-CAPACITY","SSE observer capacity is full",requestId(req,res));return;}
        boolean handedOff=false;
        try {
            AsyncContext async=req.startAsync();async.setTimeout(0);final long start=cursor;
            res.setStatus(200);res.setCharacterEncoding("UTF-8");res.setContentType("text/event-stream");res.setHeader("Cache-Control","no-cache, no-transform");res.setHeader("X-Accel-Buffering","no");
            runtime.streams.execute(()->{try{stream(async,jobId,events,start,outputDirectory,packageRoot);}finally{runtime.streamSlots.release();}});handedOff=true;
        } catch(RejectedExecutionException full) {
            if(!res.isCommitted()){res.resetBuffer();error(res,503,"ATT-SERVER-STREAM-CAPACITY","SSE observer capacity is full",requestId(req,res));}
            if(req.isAsyncStarted())req.getAsyncContext().complete();
        } finally {if(!handedOff)runtime.streamSlots.release();}
    }
    private void stream(AsyncContext async,String jobId,JobEvents journal,long cursor,Path outputDirectory,Path packageRoot){
        long lastWrite=System.nanoTime();java.util.concurrent.ArrayBlockingQueue<Boolean> wakeup=new java.util.concurrent.ArrayBlockingQueue<>(1);
        AutoCloseable subscription=journal.listen(event->wakeup.offer(Boolean.TRUE));wakeup.offer(Boolean.TRUE);
        try(PrintWriter out=async.getResponse().getWriter()){
            while(true){
                wakeup.poll(15,TimeUnit.SECONDS);
                List<Map<String,Object>> events=journal.after(cursor,100);
                for(Map<String,Object> event:events){long id=((Number)event.get("id")).longValue();out.print("id: "+id+"\nevent: "+event.get("event")+"\ndata: "+ServerRuntime.JSON.writeValueAsString(runtime.publicEventData(event.get("data"),outputDirectory,packageRoot))+"\n\n");cursor=id;}
                if(!events.isEmpty()){out.flush();if(out.checkError())return;lastWrite=System.nanoTime();}
                if(journal.hasMore(cursor)){wakeup.offer(Boolean.TRUE);continue;}
                if(runtime.terminal(jobId)&&journal.resultDeliveredThrough(cursor)&&!journal.hasMore(cursor))break;
                if(System.nanoTime()-lastWrite>TimeUnit.SECONDS.toNanos(15)){out.print(": keepalive\n\n");out.flush();if(out.checkError())return;lastWrite=System.nanoTime();}
            }
        }catch(Exception ignored){}finally{try{subscription.close();}catch(Exception ignored){}async.complete();}
    }
    private List<Map<String,Object>> artifacts(String jobId) throws Exception {
        runtime.jobRecord(jobId);Path root=runtime.config.dataDir.resolve("jobs").resolve(jobId).resolve("output").toRealPath();List<Map<String,Object>> items=new ArrayList<>();
        try(var paths=Files.walk(root)){paths.filter(Files::isRegularFile).filter(p->!Files.isSymbolicLink(p)).limit(runtime.config.maxArtifacts+1L).forEach(p->{if(items.size()<runtime.config.maxArtifacts){Map<String,Object> m=new LinkedHashMap<>();m.put("path",root.relativize(p).toString().replace('\\','/'));try{m.put("size",Files.size(p));}catch(IOException ignored){}items.add(m);}});}
        return items;
    }
    private void sendArtifact(HttpServletResponse response,Path file)throws IOException {
        String name=file.getFileName().toString().replaceAll("[\r\n\"]","_");response.setContentType("application/octet-stream");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Content-Disposition","attachment; filename=\""+name+"\"");response.setContentLengthLong(Files.size(file));Files.copy(file,response.getOutputStream());
    }
    private void resourceGet(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String principal)throws IOException {
        String[] parts=path.split("/");
        try {
            if(parts.length<4||!"packages".equals(parts[1])||parts[2].isEmpty()||!"resources".equals(parts[3]))throw new ServerRuntime.NotFoundException();
            String packageId=parts[2],action,kind=null,resourceId=null;String query=req.getParameter("query"),cursor=req.getParameter("cursor");int limit=parseLimit(req.getParameter("limit"));
            if(parts.length==4)action="list";
            else if(parts.length==7&&"source".equals(parts[6])){action="source";kind=parts[4];resourceId=parts[5];}
            else if(parts.length==7&&"debug-form".equals(parts[6])){action="debug-form";kind=parts[4];resourceId=parts[5];}
            else if(parts.length==7&&"quick-load-form".equals(parts[6])){action="quick-load-form";kind=parts[4];resourceId=parts[5];}
            else if(parts.length==6){action="detail";kind=parts[4];resourceId=parts[5];}
            else throw new ServerRuntime.NotFoundException();
            if("list".equals(action)){kind=req.getParameter("type");resourceId=null;}
            if(resourceId!=null&&(resourceId.isEmpty()||resourceId.length()>512))throw new IllegalArgumentException("resourceId is invalid");
            Map<String,Object> result="debug-form".equals(action)
                    ?runtime.inspectDebugForm(packageId,kind,resourceId,req.getParameter("environment"),principal)
                    :"quick-load-form".equals(action)?runtime.inspectQuickLoadForm(packageId,kind,resourceId,req.getParameter("model"),req.getParameter("environment"),principal)
                    :runtime.inspectResource(packageId,action,kind,resourceId,query,limit,cursor,principal);
            if("list".equals(action)) {
                ResourceInspection.Page response=ServerRuntime.JSON.convertValue(result,ResourceInspection.Page.class);
                response.requestId=requestId;json(res,200,response);
            } else if("detail".equals(action)) {
                ResourceInspection.Detail response=ServerRuntime.JSON.convertValue(result,ResourceInspection.Detail.class);
                response.requestId=requestId;json(res,200,response);
            } else if("debug-form".equals(action)) {
                DebugForm response=ServerRuntime.JSON.convertValue(result,DebugForm.class);response.requestId=requestId;json(res,200,response);
            } else if("quick-load-form".equals(action)) {
                QuickLoadForm response=ServerRuntime.JSON.convertValue(result,QuickLoadForm.class);response.requestId=requestId;json(res,200,response);
            } else {
                ResourceInspection.Source response=ServerRuntime.JSON.convertValue(result,ResourceInspection.Source.class);
                response.requestId=requestId;json(res,200,response);
            }
        } catch(ServerRuntime.NotFoundException e){throw e;}
          catch(ServerRuntime.StaleCursorException e){throw e;}
          catch(ServerRuntime.InspectionCapacityException e){throw e;}
          catch(ServerRuntime.InspectionTimeoutException e){throw e;}
          catch(ServerRuntime.InspectionResponseTooLargeException e){throw e;}
          catch(ServerRuntime.InlineLoadDisabledException e){throw e;}
          catch(IllegalArgumentException e){throw e;}
          catch(Exception e){throw new IOException(e);}
    }
    private void configurationGet(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String principal)throws IOException {
        String[] parts=path.split("/");
        try {
            if(parts.length<4||!"packages".equals(parts[1])||parts[2].isEmpty()||!"configuration".equals(parts[3]))throw new ServerRuntime.NotFoundException();
            String packageId=parts[2],action;
            if(parts.length==4) {
                String view=req.getParameter("view");
                if(view!=null&&!"declared".equals(view))throw new IllegalArgumentException("view must be declared");
                action="declared";
            } else if(parts.length==5&&"effective".equals(parts[4])) action="effective";
            else if(parts.length==5&&"compare".equals(parts[4])) action="compare";
            else throw new ServerRuntime.NotFoundException();
            String environment="effective".equals(action)?req.getParameter("environment"):"compare".equals(action)?req.getParameter("left"):null;
            String otherEnvironment="compare".equals(action)?req.getParameter("right"):null;
            Map<String,Object> result=runtime.inspectConfiguration(packageId,action,environment,otherEnvironment,principal);
            ConfigurationInspection.Response response=ServerRuntime.JSON.convertValue(result,ConfigurationInspection.Response.class);
            response.requestId=requestId;json(res,200,response);
        } catch(ServerRuntime.NotFoundException e){throw e;}
          catch(ServerRuntime.InspectionCapacityException e){throw e;}
          catch(ServerRuntime.InspectionTimeoutException e){throw e;}
          catch(ServerRuntime.InspectionResponseTooLargeException e){throw e;}
          catch(IllegalArgumentException e){throw e;}
          catch(Exception e){throw new IOException(e);}
    }
    private void loadPolicyGet(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String principal)throws IOException {
        String[] parts=path.split("/");
        try {
            if(parts.length!=4||!"packages".equals(parts[1])||parts[2].isEmpty()||!"load-policy".equals(parts[3]))
                throw new ServerRuntime.NotFoundException();
            Map<String,Object> result=runtime.inspectQuickLoadPolicy(parts[2],req.getParameter("model"),
                    req.getParameter("environment"),principal);
            LoadPolicyForm response=ServerRuntime.JSON.convertValue(result,LoadPolicyForm.class);
            response.requestId=requestId;json(res,200,response);
        } catch(ServerRuntime.NotFoundException e){throw e;}
          catch(ServerRuntime.InspectionCapacityException e){throw e;}
          catch(ServerRuntime.InspectionTimeoutException e){throw e;}
          catch(ServerRuntime.InspectionResponseTooLargeException e){throw e;}
          catch(ServerRuntime.InlineLoadDisabledException e){throw e;}
          catch(IllegalArgumentException e){throw e;}
          catch(Exception e){throw new IOException(e);}
    }
    private static int parseLimit(String raw){if(raw==null||raw.isEmpty())return 0;if(!raw.matches("[0-9]{1,3}"))throw new IllegalArgumentException("limit must be an integer between 1 and 100");int value=Integer.parseInt(raw);if(value<1||value>100)throw new IllegalArgumentException("limit must be between 1 and 100");return value;}
    private static boolean resourcePath(String path){return path.matches("/packages/[^/]+/resources(?:/.*)?");}
    private static boolean configurationPath(String path){return path.matches("/packages/[^/]+/configuration(?:/.*)?");}
    private static boolean loadPolicyPath(String path){return path.matches("/packages/[^/]+/load-policy");}
    private static boolean isJson(String value){if(value==null)return false;String[] parts=value.split(";",2);return "application/json".equalsIgnoreCase(parts[0].trim());}
    private static boolean sameOrigin(HttpServletRequest req){String origin=req.getHeader("Origin");if(origin==null)return true;if("null".equalsIgnoreCase(origin.trim()))return false;try{java.net.URI parsed=java.net.URI.create(origin);if(parsed.getHost()==null||parsed.getUserInfo()!=null||parsed.getRawPath()!=null&&!parsed.getRawPath().isEmpty()||parsed.getRawQuery()!=null||parsed.getFragment()!=null)return false;String scheme=req.getScheme().toLowerCase(java.util.Locale.ROOT),originScheme=parsed.getScheme().toLowerCase(java.util.Locale.ROOT);int requestPort=req.getServerPort(),originPort=parsed.getPort()<0?("https".equals(originScheme)?443:80):parsed.getPort();int effectiveRequest=requestPort<0?("https".equals(scheme)?443:80):requestPort;return scheme.equals(originScheme)&&req.getServerName().equalsIgnoreCase(parsed.getHost())&&effectiveRequest==originPort;}catch(Exception invalid){return false;}}
    private static String path(HttpServletRequest r){String p=r.getPathInfo();return p==null||p.isEmpty()?"/":p;}
    private static String segment(String path,int index){String[] parts=path.split("/");return parts.length>index?parts[index]:"";}
    static String principal(HttpServletRequest req){Principal p=req.getUserPrincipal();if(p==null){Object value=req.getServletContext().getAttribute(ServerBootstrap.RUNTIME);if(value instanceof ServerRuntime runtime&&!runtime.config.authenticationRequired)return "anonymous";return null;}String name=p.getName();if(name==null||name.isBlank()||name.length()>256)return null;for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))return null;return name.trim();}
    private static String requestId(HttpServletRequest req,HttpServletResponse res){String value=req.getHeader("X-Request-ID");if(value==null||!value.matches("[A-Za-z0-9._-]{1,80}"))value=UUID.randomUUID().toString();res.setHeader("X-Request-ID",value);return value;}
    private static void json(HttpServletResponse res,int status,Object body)throws IOException{res.setStatus(status);res.setCharacterEncoding("UTF-8");res.setContentType("application/json");ServerRuntime.JSON.writeValue(res.getOutputStream(),body);}
    private static void error(HttpServletResponse res,int status,String code,String summary,String requestId)throws IOException{Map<String,Object> e=new LinkedHashMap<>();e.put("code",code);e.put("summary",summary);e.put("detail",summary);e.put("requestId",requestId);json(res,status,Map.of("error",e));}
    private static void error(HttpServletResponse res,int status,String code,String summary,String requestId,List<Map<String,Object>> diagnostics)throws IOException{
        Map<String,Object> e=new LinkedHashMap<>();e.put("code",code);e.put("summary",summary);e.put("detail",summary);e.put("requestId",requestId);
        Map<String,Object> response=new LinkedHashMap<>();response.put("error",e);response.put("diagnostics",diagnostics);json(res,status,response);
    }
    private static String safeDetail(IllegalArgumentException e){String m=e.getMessage();return m==null?"Invalid request":m.length()>300?m.substring(0,300):m;}
}

