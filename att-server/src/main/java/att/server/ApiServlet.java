package att.server;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@WebServlet(urlPatterns="/api/v1/*",asyncSupported=true)
public final class ApiServlet extends HttpServlet {
    private ServerRuntime runtime;
    @Override public void init() throws ServletException {Object r=getServletContext().getAttribute(ServerBootstrap.RUNTIME);if(!(r instanceof ServerRuntime))throw new ServletException("ATT Server runtime did not initialize");runtime=(ServerRuntime)r;}
    @Override protected void service(HttpServletRequest req,HttpServletResponse res) throws ServletException,IOException {
        long inspectionStarted=System.nanoTime();
        boolean inspection=resourcePath(path(req))||configurationPath(path(req))||path(req).matches("/packages/[^/]+/load-catalog(?:/(?:source|validate|detail))?");
        try { serviceRequest(req,res); }
        finally { if(inspection)runtime.inspectionMetrics.record(att.metrics.InspectionMetrics.Stage.HTTP,System.nanoTime()-inspectionStarted); }
    }
    private void serviceRequest(HttpServletRequest req,HttpServletResponse res) throws ServletException,IOException {
        if(path(req).startsWith("/auth/")){super.service(req,res);return;}
        if("PATCH".equalsIgnoreCase(req.getMethod())){schedulePatch(req,res);return;}
        super.service(req,res);
    }
    @Override protected void doGet(HttpServletRequest req,HttpServletResponse res) throws IOException {
        String path=path(req);String requestId=requestId(req,res);
        try {
            if(path.startsWith("/auth/")){authGet(req,res,path,requestId);return;}
            if("/health".equals(path)){json(res,200,Map.of("status","UP","version",ServerBuildInfo.PRODUCT,"apiVersion",ServerApi.VERSION,"requestId",requestId));return;}
            if("/version".equals(path)){Map<String,Object> version=new LinkedHashMap<>();version.put("version",ServerBuildInfo.PRODUCT);version.put("apiVersion",ServerApi.VERSION);version.put("buildTime",ServerBuildInfo.BUILD_TIME);version.put("gitCommit",ServerBuildInfo.GIT_COMMIT);version.put("javaMinimum",17);version.put("inlineLoadEnabled",runtime.config.inlineLoad.enabled);version.put("loadSchedulingEnabled",runtime.config.scheduling.enabled);version.put("unifiedRunVersion",RunDefinition.VERSION);version.put("authMode",runtime.config.authMode);version.put("requestId",requestId);json(res,200,version);return;}
            boolean resourceRequest=resourcePath(path);
            boolean loadCatalogRequest=path.matches("/packages/[^/]+/load-catalog(?:/(?:source|validate|detail))?");
            boolean configurationRequest=configurationPath(path);
            boolean loadPolicyRequest=loadPolicyPath(path);
            if(loadSchedulePath(path)){scheduleGet(req,res,path,requestId);return;}
            boolean draftRequest=path.matches("/drafts/[DLAR][0-9A-F]{32}");
            String principal=principal(req);if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
            if("/metrics".equals(path)){if(!requireSystemAdmin(req,res,requestId))return;Map<String,Object> metrics=runtime.counts();metrics.put("requestId",requestId);json(res,200,metrics);return;}
            if(!requireOperator(req,res,requestId))return;
            if(path.matches("/packages/[^/]+/load-templates")) {
                Map<String,Object> response=runtime.inspectLoadTemplates(segment(path,2),req.getParameter("query"),
                        parseOffset(req.getParameter("offset")),parseLimit(req.getParameter("limit")),principal);
                response.put("requestId",requestId);res.setHeader("Cache-Control","no-store");json(res,200,response);return;
            }
            if(resourceRequest){resourceGet(req,res,path,requestId,principal);return;}
            if(loadCatalogRequest){loadCatalogGet(req,res,path,requestId,principal);return;}
            if(path.matches("/packages/[^/]+/package-configuration(?:/(?:declared|effective|compare|source|history))?")) {
                manifestGet(req,res,path,requestId,principal);return;
            }
            if(configurationRequest){configurationGet(req,res,path,requestId,principal);return;}
            if(loadPolicyRequest){loadPolicyGet(req,res,path,requestId,principal);return;}
            if(draftRequest){
                String draftId=segment(path,2);
                if(draftId.startsWith("R")){Map<String,Object> response=runtime.getRunDraft(draftId,principal);response.put("requestId",requestId);json(res,200,response);}
                else if(draftId.startsWith("L")){LoadDraft response=ServerRuntime.JSON.convertValue(runtime.getQuickLoadDraft(draftId,principal),LoadDraft.class);response.requestId=requestId;json(res,200,response);}
                else if(draftId.startsWith("A")){LoadDraft response=ServerRuntime.JSON.convertValue(runtime.getAdvancedLoadDraft(draftId,principal),LoadDraft.class);response.requestId=requestId;json(res,200,response);}
                else if(draftId.startsWith("C")){LoadDraft response=ServerRuntime.JSON.convertValue(runtime.getCatalogLoadDraft(draftId,principal),LoadDraft.class);response.requestId=requestId;json(res,200,response);}
                else {DebugDraft response=ServerRuntime.JSON.convertValue(runtime.getDebugDraft(draftId,principal),DebugDraft.class);response.requestId=requestId;json(res,200,response);}
                return;
            }
            if("/packages".equals(path)){json(res,200,Map.of("items",runtime.packages(principal),"requestId",requestId));return;}
            if(path.startsWith("/packages/")){String id=segment(path,2);Map<String,Object> view=runtime.packageView(id,principal);view.put("requestId",requestId);json(res,200,view);return;}
            if("/jobs".equals(path)){
                Map<String,String> parameters=new LinkedHashMap<>();
                for(String key:List.of("offset","limit","principal","principalMode","packageId","executionType","status","from","to","search","sort","deletion","pinnedOnly","groupBy")) {
                    String value=req.getParameter(key);if(value!=null)parameters.put(key,value);
                }
                Map<String,Object> response=runtime.store.queryJobs(JobQuery.parse(parameters,principal));response.put("requestId",requestId);
                res.setHeader("Cache-Control","no-store");json(res,200,response);return;
            }
            if(path.startsWith("/jobs/"))runtime.requireJobOwner(segment(path,2),principal);
            if(path.matches("/jobs/[^/]+/replay")){Map<String,Object> replay=runtime.jobReplay(segment(path,2),principal);replay.put("requestId",requestId);json(res,200,replay);return;}
            if(path.matches("/jobs/[^/]+/events")){sse(req,res,segment(path,2));return;}
            if(path.matches("/jobs/[^/]+/artifacts/archive\\.zip")){
                String id=segment(path,2);
                try(ServerRuntime.ArtifactRead read=runtime.openArtifactArchive(id,true)) {
                    long size=read.length();
                    res.setContentType("application/zip");res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Cache-Control","private, no-store");
                    res.setHeader("Content-Disposition","attachment; filename=\""+id+"-artifacts.zip\"");res.setContentLengthLong(size);
                    read.writeTo(res.getOutputStream());
                }
                return;
            }
            if(path.startsWith("/jobs/")&&path.contains("/artifacts/view/")) {
                int boundary=path.indexOf("/artifacts/view/");
                String id=segment(path.substring(0,boundary),2),relative=path.substring(boundary+"/artifacts/view/".length());
                byte[] bytes=runtime.archiveEntry(id,relative);
                String ext=relative.substring(relative.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
                boolean html="html".equals(ext)||"htm".equals(ext);
                String mime=html?"text/html":"css".equals(ext)?"text/css":"text/plain";
                res.setHeader("X-Frame-Options","SAMEORIGIN");res.setHeader("Referrer-Policy","no-referrer");
                res.setContentType(mime+"; charset=UTF-8");res.setHeader("X-Content-Type-Options","nosniff");
                res.setHeader("Cache-Control","private, no-store");res.setHeader("Content-Disposition","inline");
                // Text destinations must remain navigable inside the report iframe, not inherit API frame denial.
                String policy=html?"sandbox allow-scripts; "+ArtifactHtmlPolicy.CONTENT_POLICY
                        :"sandbox; default-src 'none'; base-uri 'none'; form-action 'none'";
                res.setHeader("Content-Security-Policy",policy+"; frame-ancestors 'self'");
                res.setContentLengthLong(bytes.length);res.getOutputStream().write(bytes);return;
            }
            if(path.startsWith("/jobs/")&&path.contains("/artifacts/"))throw new ServerRuntime.NotFoundException();
            if(path.matches("/jobs/[^/]+/artifacts")){String id=segment(path,2);Map<String,Object> listing=runtime.artifactIndex(id,parseOffset(req.getParameter("offset")),parseConfigurationLimit(req.getParameter("limit")));listing.put("requestId",requestId);json(res,200,listing);return;}
            if(path.matches("/jobs/[^/]+/result")){Map<String,Object> result=runtime.resultRecord(segment(path,2));result.put("requestId",requestId);json(res,200,result);return;}
            if(path.matches("/jobs/[^/]+")){Map<String,Object> view=runtime.jobRecord(segment(path,2));view.put("requestId",requestId);json(res,200,view);return;}
            error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);
        } catch(JobStore.TargetSearchPendingException e){res.setHeader("Retry-After","2");error(res,503,"ATT-SERVER-TARGET-SEARCH-PENDING","Historical target search is being prepared. No partial results were returned. Use job: followed by the full Job ID for exact lookup, or retry Refresh jobs shortly.",requestId);}
          catch(ServerRuntime.ArchiveUnavailableException e){error(res,409,"ATT-SERVER-ARCHIVE-UNAVAILABLE","Artifact archive is pending, failed, or unavailable; refresh the archive status",requestId);}
          catch(ServerRuntime.ArtifactCapacityException e){error(res,503,"ATT-SERVER-ARTIFACT-CAPACITY","Artifact preview capacity is full; retry shortly",requestId);}
          catch(ServerRuntime.InlineLoadDisabledException e){error(res,403,"ATT-SERVER-INLINE-LOAD-DISABLED","Browser-submitted Load is disabled by Server configuration",requestId);}
          catch(ServerRuntime.InlineLoadLimitException e){error(res,400,"ATT-SERVER-INLINE-LOAD-LIMIT",safeDetail(e),requestId);}
          catch(ServerRuntime.DraftValidationException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",e.getMessage(),requestId,e.diagnostics);}
          catch(ServerRuntime.InspectionConfigurationException e){error(res,422,"ATT-CFG-001",e.summary(),requestId,e.diagnostics);}
          catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
          catch(ServerRuntime.StaleCursorException e){error(res,409,"ATT-RESOURCE-CURSOR-STALE","The package resources changed; refresh the Explorer",requestId);}
          catch(ServerRuntime.StaleDraftException e){error(res,409,"ATT-SERVER-DRAFT-STALE","Package content changed after preview; rebuild the draft",requestId);}
          catch(ServerRuntime.InspectionCapacityException e){error(res,503,"ATT-SERVER-INSPECTION-CAPACITY","Package inspection capacity is full; retry shortly",requestId);}
          catch(ServerRuntime.DraftCapacityException e){error(res,429,"ATT-SERVER-DRAFT-CAPACITY","Draft capacity is full; retry after an existing draft expires",requestId);}
          catch(ServerRuntime.InspectionTimeoutException e){error(res,504,"ATT-SERVER-INSPECTION-TIMEOUT","Package inspection exceeded its time limit",requestId);}
          catch(ServerRuntime.InspectionResponseTooLargeException e){error(res,413,"ATT-SERVER-INSPECTION-RESPONSE-TOO-LARGE","Package inspection response exceeded the configured limit",requestId);}
          catch(PackageConfigurationPolicy.EnvironmentDeniedException e){error(res,403,"ATT-PACKAGE-ENVIRONMENT-DENIED",safeDetail(e),requestId);}
          catch(IllegalArgumentException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(e),requestId);}
          catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The request could not be completed",requestId);getServletContext().log("ATT Server request failed id="+requestId,e);}
    }
    @Override protected void doPost(HttpServletRequest req,HttpServletResponse res) throws IOException {
        String requestId=requestId(req,res),path=path(req);String authorizationPrincipal=principal(req);
        if(path.startsWith("/auth/")){authPost(req,res,path,requestId);return;}
        if(authorizationPrincipal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
        if(loadSchedulePath(path)){schedulePost(req,res,path,requestId,authorizationPrincipal);return;}
        if(!requireOperator(req,res,requestId))return;
        if(path.matches("/packages/[^/]+/package-configuration/(?:drafts(?:/[^/]+/(?:validate|review|publish|discard))?|rollback)")) {
            manifestPost(req,res,path,requestId,authorizationPrincipal);return;
        }
        if(path.matches("/jobs/[^/]+/artifacts/retry")){if(!checkOrigin(req,res,requestId,false))return;try{String id=segment(path,2);runtime.requireJobOwner(id,authorizationPrincipal);runtime.retryArtifactArchive(id);json(res,202,Map.of("archiveStatus","PENDING","requestId",requestId));return;}catch(ServerRuntime.NotFoundException missing){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return;}catch(IllegalArgumentException invalid){error(res,409,"ATT-SERVER-ARCHIVE-NOT-READY",safeDetail(invalid),requestId);return;}catch(Exception failure){error(res,500,"ATT-SERVER-REQUEST-FAILED","The archive retry could not be scheduled",requestId);return;}}
        if("/jobs/delete".equals(path)||"/jobs/pin".equals(path)||path.matches("/jobs/[^/]+/(pin|delete)")) {
            if(!checkOrigin(req,res,requestId,false))return;
            try {
                if(!isJson(req.getContentType())){error(res,415,"ATT-SERVER-UNSUPPORTED-MEDIA-TYPE","Content-Type must be application/json",requestId);return;}
                byte[] body=req.getInputStream().readNBytes(Math.min(runtime.config.maxRequestBytes,16384)+1);
                if(body.length>Math.min(runtime.config.maxRequestBytes,16384)){error(res,413,"ATT-SERVER-REQUEST-TOO-LARGE","Job action body exceeds size limit",requestId);return;}
                JsonNode input=ServerRuntime.JSON.readTree(body);
                if(input==null||!input.isObject())throw new IllegalArgumentException("A JSON object is required");
                if(path.endsWith("/pin")) {
                    if(!input.path("pinned").isBoolean())throw new IllegalArgumentException("pinned must be a boolean");
                    String reason=input.path("pinReason").isMissingNode()||input.path("pinReason").isNull()?null:input.path("pinReason").isTextual()?input.path("pinReason").asText():null;
                    if(input.has("pinReason")&&!input.path("pinReason").isNull()&&!input.path("pinReason").isTextual())throw new IllegalArgumentException("pinReason must be a string");
                    if("/jobs/pin".equals(path)) {
                        JsonNode ids=input.get("jobIds");if(ids==null||!ids.isArray()||ids.size()<1||ids.size()>100)throw new IllegalArgumentException("Select 1 to 100 Job IDs");
                        List<String> selected=new java.util.ArrayList<>();for(JsonNode id:ids){if(!id.isTextual())throw new IllegalArgumentException("Job IDs must be strings");selected.add(id.asText());}
                        json(res,200,Map.of("items",runtime.pinJobs(selected,input.get("pinned").booleanValue(),reason,authorizationPrincipal),"requestId",requestId));
                    } else json(res,200,runtime.pinJob(segment(path,2),input.get("pinned").booleanValue(),reason,authorizationPrincipal));
                } else {
                    if(input.has("requested")?!input.path("requested").isBoolean():!input.path("confirm").isBoolean()||!input.path("confirm").booleanValue())throw new IllegalArgumentException("requested must be a boolean (legacy confirm:true is accepted)");
                    boolean requested=!input.has("requested")||input.path("requested").booleanValue();
                    if("/jobs/delete".equals(path)) {
                        JsonNode ids=input.get("jobIds");if(ids==null||!ids.isArray()||ids.size()<1||ids.size()>100)throw new IllegalArgumentException("Select 1 to 100 Job IDs");
                        List<String> selected=new java.util.ArrayList<>();
                        for(JsonNode id:ids){if(!id.isTextual())throw new IllegalArgumentException("Job IDs must be strings");selected.add(id.asText());}
                        json(res,200,Map.of("items",runtime.setDeletionRequested(selected,requested,authorizationPrincipal),"requestId",requestId));
                    } else {Map<String,Object> outcome=runtime.setDeletionRequested(segment(path,2),requested,authorizationPrincipal);String value=String.valueOf(outcome.get("outcome"));
                        int status=List.of("MARKED","UNMARKED","ALREADY_IN_STATE","ALREADY_UNAVAILABLE").contains(value)?200:409;json(res,status,outcome);}
                }
            } catch(ServerRuntime.NotFoundException missing){error(res,404,"ATT-SERVER-NOT-FOUND","Job was not found or is unavailable",requestId);}
              catch(ServerRuntime.JobConflictException conflict){error(res,409,conflict.code,safeDetail(new IllegalArgumentException(conflict.getMessage())),requestId);}
              catch(IllegalArgumentException invalid){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(invalid),requestId);}
              catch(Exception failed){error(res,500,"ATT-SERVER-JOB-ACTION-FAILED","Job action could not be completed",requestId);}
            return;
        }
        boolean createReplayDraft=path.matches("/jobs/[^/]+/replay-draft"),rerunJob=path.matches("/jobs/[^/]+/rerun");
        boolean createRunDraft="/drafts/run".equals(path),createDebugDraft="/drafts/debug".equals(path),createQuickLoadDraft="/drafts/quick-load".equals(path),createAdvancedLoadDraft="/drafts/load".equals(path);
        String command=path.startsWith("/jobs/")?path.substring("/jobs/".length()):"";
        if(!createReplayDraft&&!rerunJob&&!createRunDraft&&!createDebugDraft&&!createQuickLoadDraft&&!createAdvancedLoadDraft&&!List.of("run","debug","load","validate").contains(command)){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return;}
        try {
            if(!isJson(req.getContentType())){error(res,415,"ATT-SERVER-UNSUPPORTED-MEDIA-TYPE","Content-Type must be application/json",requestId);return;}
            if(!checkOrigin(req,res,requestId,false))return;
            byte[] body=req.getInputStream().readNBytes(runtime.config.maxRequestBytes+1);
            if(body.length>runtime.config.maxRequestBytes){error(res,413,"ATT-SERVER-REQUEST-TOO-LARGE","Request body exceeds the configured size limit",requestId);return;}
            JsonNode input;
            try{input=ServerRuntime.JSON.readTree(body);}catch(JsonProcessingException malformed){error(res,400,"ATT-SERVER-INVALID-REQUEST","Request body is not valid JSON",requestId);return;}
            if(input==null||!input.isObject())throw new IllegalArgumentException("A JSON object is required");
            RequestIdentity identity=RequestIdentity.resolve(runtime.config.authenticationRequired,authorizationPrincipal,input);
            if(createReplayDraft){if(input.size()!=0)throw new IllegalArgumentException("Replay review accepts an empty object");Map<String,Object> draft=runtime.createJobReplayDraft(segment(path,2),identity.authorizationPrincipal());draft.put("requestId",requestId);json(res,201,draft);return;}
            if(rerunJob){Map<String,Object> job=runtime.rerunJob(segment(path,2),input,identity.authorizationPrincipal());job.put("requestId",requestId);res.setHeader("Location",req.getContextPath()+"/api/v1/jobs/"+job.get("jobId"));json(res,202,job);return;}
            if(createRunDraft){Map<String,Object> draft=runtime.createRunDraft(input,identity.authorizationPrincipal());draft.put("requestId",requestId);json(res,201,draft);return;}
            if(createDebugDraft){DebugDraft draft=ServerRuntime.JSON.convertValue(runtime.createDebugDraft(input,identity.authorizationPrincipal()),DebugDraft.class);draft.requestId=requestId;json(res,201,draft);return;}
            if(createQuickLoadDraft){LoadDraft draft=ServerRuntime.JSON.convertValue(runtime.createQuickLoadDraft(input,identity.authorizationPrincipal()),LoadDraft.class);draft.requestId=requestId;json(res,201,draft);return;}
            if(createAdvancedLoadDraft){LoadDraft draft=ServerRuntime.JSON.convertValue(runtime.createAdvancedLoadDraft(input,identity.authorizationPrincipal()),LoadDraft.class);draft.requestId=requestId;json(res,201,draft);return;}
            JsonNode workerInput=RequestIdentity.withoutSubmittedPrincipal(input);
            if("run".equals(command)&&input.has("draftId")){
                Map<String,Object> job=runtime.submitRunDraft(input,identity.authorizationPrincipal(),identity.persistedPrincipal());job.put("requestId",requestId);res.setHeader("Location",req.getContextPath()+"/api/v1/jobs/"+job.get("jobId"));json(res,202,job);return;
            }
            if("debug".equals(command)&&input.has("draftId")){
                Map<String,Object> job=runtime.submitDebugDraft(input,identity.authorizationPrincipal(),identity.persistedPrincipal());job.put("requestId",requestId);res.setHeader("Location",req.getContextPath()+"/api/v1/jobs/"+job.get("jobId"));json(res,202,job);return;
            }
            if("load".equals(command)&&input.has("draftId")){
                Map<String,Object> job=runtime.submitLoadDraft(input,identity.authorizationPrincipal(),identity.persistedPrincipal());job.put("requestId",requestId);res.setHeader("Location",req.getContextPath()+"/api/v1/jobs/"+job.get("jobId"));json(res,202,job);return;
            }
            Map<String,Object> job=runtime.submit(command,workerInput,identity.persistedPrincipal());job.put("requestId",requestId);res.setHeader("Location",req.getContextPath()+"/api/v1/jobs/"+job.get("jobId"));json(res,202,job);
        } catch(ServerRuntime.InlineLoadDisabledException e){error(res,403,"ATT-SERVER-INLINE-LOAD-DISABLED","Browser-submitted Load is disabled by Server configuration",requestId);}
          catch(ServerRuntime.InlineLoadLimitException e){error(res,400,"ATT-SERVER-INLINE-LOAD-LIMIT",safeDetail(e),requestId);}
          catch(ServerRuntime.QueueFullException e){error(res,429,"ATT-SERVER-CAPACITY-EXCEEDED",e.getMessage(),requestId);}
          catch(ServerRuntime.DraftValidationException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",e.getMessage(),requestId,e.diagnostics);}
          catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
          catch(ServerRuntime.StaleDraftException e){error(res,409,"ATT-SERVER-DRAFT-STALE","Package content changed after preview; rebuild the draft",requestId);}
          catch(ServerRuntime.DraftCapacityException e){error(res,429,"ATT-SERVER-DRAFT-CAPACITY","Draft capacity is full; retry after an existing draft expires",requestId);}
          catch(ServerRuntime.InspectionCapacityException e){error(res,503,"ATT-SERVER-INSPECTION-CAPACITY","Package inspection capacity is full; retry shortly",requestId);}
          catch(ServerRuntime.InspectionTimeoutException e){error(res,504,"ATT-SERVER-INSPECTION-TIMEOUT","Package inspection exceeded its time limit",requestId);}
          catch(ServerRuntime.InspectionResponseTooLargeException e){error(res,413,"ATT-SERVER-INSPECTION-RESPONSE-TOO-LARGE","Package inspection response exceeded the configured limit",requestId);}
          catch(ServerRuntime.InspectionConfigurationException e){error(res,422,"ATT-CFG-001",e.summary(),requestId,e.diagnostics);}
          catch(PackageConfigurationPolicy.EnvironmentDeniedException e){error(res,403,"ATT-PACKAGE-ENVIRONMENT-DENIED",safeDetail(e),requestId);}
          catch(IllegalArgumentException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(e),requestId);}
          catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The request could not be completed",requestId);getServletContext().log("ATT Server submission failed id="+requestId,e);}
    }
    @Override protected void doDelete(HttpServletRequest req,HttpServletResponse res) throws IOException {
        String requestId=requestId(req,res),path=path(req),principal=principal(req);
        if(path.startsWith("/auth/")){authDelete(req,res,path,requestId);return;}
        if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
        if(loadSchedulePath(path)){scheduleDelete(req,res,path,requestId,principal);return;}
        if(!requireOperator(req,res,requestId))return;
        if(!path.matches("/jobs/[^/]+")){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return;}
        if(!checkOrigin(req,res,requestId,false))return;
        try{String id=segment(path,2);String status=runtime.cancel(id,principal);json(res,200,Map.of("jobId",id,"status",status,"requestId",requestId));}
        catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
        catch(Exception e){error(res,500,"ATT-SERVER-CANCEL-FAILED","The job could not be cancelled",requestId);}
    }
    @Override protected void doPut(HttpServletRequest req,HttpServletResponse res) throws IOException {error(res,405,"ATT-SERVER-METHOD-NOT-ALLOWED","Package administration is read-only in v1",requestId(req,res));}

    private void authGet(HttpServletRequest req,HttpServletResponse res,String path,String requestId)throws IOException {
        try {
            if("/auth/capabilities".equals(path)) {
                String login=principal(req);
                if(login==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","Authentication is required",requestId);return;}
                boolean accountManage=systemAdminCapability(req,login);
                boolean operate=operatorCapability(req,login);
                boolean configurationAdmin;
                if(runtime.auth.local(runtime.config)) {
                    Map<String,Object> user=runtime.store.authUser(login);
                    configurationAdmin=user!=null&&"ADMIN".equals(user.get("role"));
                } else configurationAdmin=req.isUserInRole(runtime.config.inspection.configurationAdminRole);
                res.setHeader("Cache-Control","no-store");
                @SuppressWarnings("unchecked") Map<String,String> session=(Map<String,String>)req.getAttribute(AuthService.SESSION);
                String sessionKey=session==null?(req.getSession(false)==null?"container":req.getSession(false).getId()):session.getOrDefault("tokenHash","");
                String cacheContext=runtime.inspectionCacheToken(java.util.Arrays.asList(login,configurationAdmin,operate,accountManage,sessionKey,runtime.config.inspection));
                Map<String,Object> capabilities=new LinkedHashMap<>();capabilities.put("operate",operate);capabilities.put("accountManage",accountManage);capabilities.put("systemSettingsWrite",accountManage);capabilities.put("configurationAdmin",configurationAdmin);
                json(res,200,Map.of("capabilities",capabilities,"configurationAdmin",configurationAdmin,"inspectionCacheContext",cacheContext,"requestId",requestId));return;
            }
            if(!runtime.auth.local(runtime.config)){error(res,404,"ATT-SERVER-NOT-FOUND","Authentication resource was not found",requestId);return;}
            if("/auth/bootstrap/status".equals(path)){json(res,200,Map.of("required",runtime.auth.bootstrapRequired(),"requestId",requestId));return;}
            if("/auth/users".equals(path)){if(!requireAdmin(req,res,requestId))return;json(res,200,Map.of("items",runtime.store.authUsers(),"requestId",requestId));return;}
            if("/auth/me".equals(path)) {String principal=principal(req);if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","Authentication is required",requestId);return;}
                @SuppressWarnings("unchecked") Map<String,String> session=(Map<String,String>)req.getAttribute(AuthService.SESSION);
                Map<String,Object> profile=runtime.auth.safeUser(runtime.store.authUser(principal));Map<String,Object> result=new LinkedHashMap<>(profile);Object csrf=session==null?req.getAttribute(AuthService.BASIC_CSRF):session.get("csrfToken");if(csrf instanceof String)result.put("csrfToken",csrf);result.put("requestId",requestId);json(res,200,result);return;}
            error(res,404,"ATT-SERVER-NOT-FOUND","Authentication resource was not found",requestId);
        }catch(Exception failure){error(res,500,"ATT-SERVER-REQUEST-FAILED","The authentication request could not be completed",requestId);}
    }
    private void authPost(HttpServletRequest req,HttpServletResponse res,String path,String requestId)throws IOException {
        try {
            if(!runtime.auth.local(runtime.config)){error(res,404,"ATT-SERVER-NOT-FOUND","Authentication resource was not found",requestId);return;}
            if(containsPasswordSubmission(path)&&!secureLocalAuthTransport(req)){error(res,403,"ATT-AUTH-HTTPS-REQUIRED","Local credentials require HTTPS outside loopback development",requestId);return;}
            if(!isJson(req.getContentType())){error(res,415,"ATT-SERVER-UNSUPPORTED-MEDIA-TYPE","Content-Type must be application/json",requestId);return;}
            if(!checkOrigin(req,res,requestId,"/auth/login".equals(path)||"/auth/bootstrap".equals(path)))return;
            JsonNode body=authBody(req);if(body==null||!body.isObject())throw new IllegalArgumentException("A JSON object is required");
            if("/auth/bootstrap".equals(path)) {Map<String,Object> user=runtime.auth.bootstrap(text(body,"token"),text(body,"loginId"),text(body,"displayName"),text(body,"password"),req.getRemoteAddr());runtime.store.audit((String)user.get("loginId"),"BOOTSTRAP",null,null,"SUCCESS");json(res,201,Map.of("user",user,"requestId",requestId));return;}
            if("/auth/login".equals(path)) {runtime.auth.logout(req);Map<String,Object> session=runtime.auth.login(text(body,"loginId"),text(body,"password"),req.getRemoteAddr());String token=(String)session.get("token");setSessionCookie(res,req,token,AuthService.ABSOLUTE_MILLIS/1000);runtime.store.audit((String)((Map<?,?>)session.get("user")).get("loginId"),"LOGIN",null,null,"SUCCESS");json(res,200,Map.of("user",session.get("user"),"requestId",requestId));return;}
            if("/auth/logout".equals(path)){String user=principal(req);runtime.auth.logout(req);expireSessionCookie(res,req);if(user!=null)runtime.store.audit(user,"LOGOUT",null,null,"SUCCESS");json(res,200,Map.of("loggedOut",true,"requestId",requestId));return;}
            if("/auth/password".equals(path)){String user=principal(req);runtime.auth.changePassword(user,text(body,"currentPassword"),text(body,"newPassword"),req.getRemoteAddr());expireSessionCookie(res,req);runtime.store.audit(user,"PASSWORD_CHANGE",null,null,"SUCCESS");json(res,200,Map.of("updated",true,"requestId",requestId));return;}
            if(path.matches("/auth/users/[^/]+/enable")){if(!requireAdmin(req,res,requestId))return;String target=path.split("/")[3];runtime.auth.enable(target);runtime.store.audit(principal(req),"USER_ENABLE",null,null,target);json(res,200,Map.of("enabled",true,"requestId",requestId));return;}
            if("/auth/users".equals(path)){if(!requireAdmin(req,res,requestId))return;String target=text(body,"loginId");runtime.auth.createUser(target,text(body,"displayName"),text(body,"password"),text(body,"role"));runtime.store.audit(principal(req),"USER_CREATE",null,null,target);json(res,201,Map.of("user",runtime.auth.safeUser(runtime.store.authUser(AuthService.normalize(target))),"requestId",requestId));return;}
            if(path.matches("/auth/users/[^/]+/password")){if(!requireAdmin(req,res,requestId))return;String target=path.split("/")[3];runtime.auth.password(target,text(body,"password"));runtime.store.audit(principal(req),"PASSWORD_RESET",null,null,target);json(res,200,Map.of("updated",true,"requestId",requestId));return;}
            error(res,404,"ATT-SERVER-NOT-FOUND","Authentication resource was not found",requestId);
        }catch(AuthService.AuthFailure failure){String action="/auth/login".equals(path)?"LOGIN":"/auth/bootstrap".equals(path)?"BOOTSTRAP":"AUTH_CHANGE";try{runtime.store.audit("anonymous",action,null,null,failure.code);}catch(Exception ignored){}error(res,failure.status,failure.code,"Authentication request could not be completed",requestId);}
         catch(IllegalArgumentException failure){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(failure),requestId);}
         catch(Exception failure){error(res,500,"ATT-SERVER-REQUEST-FAILED","The authentication request could not be completed",requestId);}
    }
    private void authDelete(HttpServletRequest req,HttpServletResponse res,String path,String requestId)throws IOException {
        if(!runtime.auth.local(runtime.config)){error(res,404,"ATT-SERVER-NOT-FOUND","Authentication resource was not found",requestId);return;}
        if(!path.matches("/auth/users/[^/]+")){error(res,404,"ATT-SERVER-NOT-FOUND","Authentication resource was not found",requestId);return;}
        if(!checkOrigin(req,res,requestId,false))return;
        if(!requireAdmin(req,res,requestId))return;
        try{String target=path.substring("/auth/users/".length());runtime.auth.disable(target);runtime.store.audit(principal(req),"USER_DISABLE",null,null,target);json(res,200,Map.of("disabled",true,"requestId",requestId));}
        catch(AuthService.AuthFailure e){error(res,e.status,e.code,"Authentication request could not be completed",requestId);}
        catch(IllegalArgumentException e){error(res,404,"ATT-SERVER-NOT-FOUND","User was not found",requestId);}
        catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The authentication request could not be completed",requestId);}
    }
    private boolean requireAdmin(HttpServletRequest req,HttpServletResponse res,String requestId)throws IOException {
        String login=principal(req);if(login==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","Authentication is required",requestId);return false;}
        try{Map<String,Object> user=runtime.store.authUser(login);if(user==null||!Boolean.TRUE.equals(user.get("active"))||!"ADMIN".equals(user.get("role"))){error(res,403,"ATT-SERVER-FORBIDDEN","Administrator access is required",requestId);return false;}return true;}
        catch(Exception failure){error(res,500,"ATT-SERVER-REQUEST-FAILED","The authentication request could not be completed",requestId);return false;}
    }
    private boolean requireSystemAdmin(HttpServletRequest req,HttpServletResponse res,String requestId)throws IOException {
        String login=principal(req);
        if(login==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","Authentication is required",requestId);return false;}
        try {
            if(systemAdminCapability(req,login))return true;
            error(res,403,"ATT-SERVER-FORBIDDEN","System administrator access is required",requestId);return false;
        } catch(Exception failure){error(res,500,"ATT-SERVER-REQUEST-FAILED","The authorization request could not be completed",requestId);return false;}
    }
    private boolean requireOperator(HttpServletRequest req,HttpServletResponse res,String requestId)throws IOException {
        if(!runtime.config.scheduling.enabled&&loadSchedulePath(path(req))) {error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return false;}
        String login=principal(req);
        if(login==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","Authentication is required",requestId);return false;}
        try {
            if(operatorCapability(req,login))return true;
            error(res,403,"ATT-SERVER-OPERATIONS-REQUIRED","An active USER account with operational access is required",requestId);return false;
        } catch(Exception failure){error(res,500,"ATT-SERVER-REQUEST-FAILED","The authorization request could not be completed",requestId);return false;}
    }
    private boolean systemAdminCapability(HttpServletRequest req,String login)throws Exception {
        if(runtime.auth.local(runtime.config)) {
            Map<String,Object> user=runtime.store.authUser(login);
            return user!=null&&Boolean.TRUE.equals(user.get("active"))&&"ADMIN".equals(user.get("role"));
        }
        return !RequestIdentity.ANONYMOUS.equals(login)&&req.isUserInRole(runtime.config.authorization.adminRole);
    }
    private boolean operatorCapability(HttpServletRequest req,String login)throws Exception {
        if(runtime.auth.local(runtime.config))return runtime.store.isActiveUser(login);
        if(RequestIdentity.ANONYMOUS.equals(login))return !runtime.config.authenticationRequired&&runtime.config.authorization.allowAnonymousOperations;
        return !req.isUserInRole(runtime.config.authorization.adminRole)&&req.isUserInRole(runtime.config.authorization.userRole);
    }
    private JsonNode authBody(HttpServletRequest req)throws IOException {byte[] bytes=req.getInputStream().readNBytes(Math.min(runtime.config.maxRequestBytes,65536)+1);if(bytes.length>Math.min(runtime.config.maxRequestBytes,65536))throw new IllegalArgumentException("Authentication request body is too large");try{return ServerRuntime.JSON.readTree(bytes);}catch(JsonProcessingException e){throw new IllegalArgumentException("Request body is not valid JSON");}}
    private static String text(JsonNode body,String name){JsonNode value=body.get(name);if(value==null||!value.isTextual())throw new IllegalArgumentException(name+" must be text");return value.asText();}
    private static boolean secureLocalAuthTransport(HttpServletRequest req){if(req.isSecure())return true;try{return java.net.InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress();}catch(Exception invalid){return false;}}
    private static boolean containsPasswordSubmission(String path) {
        return "/auth/login".equals(path) || "/auth/bootstrap".equals(path) || "/auth/password".equals(path)
                || "/auth/users".equals(path) || path.matches("/auth/users/[^/]+/password");
    }
    private static void setSessionCookie(HttpServletResponse res,HttpServletRequest req,String token,long maxAge){String path=req.getContextPath().isEmpty()?"/":req.getContextPath()+"/";res.addHeader("Set-Cookie",AuthService.COOKIE+"="+token+"; Path="+path+"; Max-Age="+maxAge+"; HttpOnly; SameSite=Lax"+(req.isSecure()?"; Secure":""));}
    private static void expireSessionCookie(HttpServletResponse res,HttpServletRequest req){String path=req.getContextPath().isEmpty()?"/":req.getContextPath()+"/";res.addHeader("Set-Cookie",AuthService.COOKIE+"=; Path="+path+"; Max-Age=0; HttpOnly; SameSite=Lax"+(req.isSecure()?"; Secure":""));}

    private void scheduleGet(HttpServletRequest req,HttpServletResponse res,String path,String requestId)throws IOException {
        String principal=principal(req);if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
        if(!requireOperator(req,res,requestId))return;
        try {
            String[] parts=path.split("/");
            if(parts.length==2){String type=req.getParameter("executionType");if(type!=null&&!List.of("run","load").contains(type))throw new IllegalArgumentException("executionType must be run or load");json(res,200,Map.of("items",runtime.loadSchedules().stream().filter(schedule->type==null||type.equals("load".equals(schedule.effectiveJobType())?"load":"run")).map(schedule->runtime.loadScheduleView(schedule,principal)).toList(),"requestId",requestId));return;}
            String id=parts[2];
            if(parts.length==3){LoadSchedule item=runtime.loadSchedule(id);Map<String,Object> view=runtime.loadScheduleView(item,principal);view.put("requestId",requestId);json(res,200,view);return;}
            if(parts.length==4&&"executions".equals(parts[3])){int limit=parseScheduleLimit(req.getParameter("limit"));json(res,200,Map.of("items",runtime.loadScheduleExecutions(id,limit),"scheduleId",id,"requestId",requestId));return;}
            error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);
        } catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
          catch(ServerRuntime.DraftValidationException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",e.getMessage(),requestId,e.diagnostics);}
          catch(ServerRuntime.StaleDraftException e){error(res,409,"ATT-SERVER-DRAFT-STALE","The validated execution draft expired or changed; validate it again",requestId);}
          catch(ServerRuntime.InspectionCapacityException e){error(res,503,"ATT-SERVER-INSPECTION-CAPACITY","Package inspection capacity is full; retry shortly",requestId);}
          catch(ServerRuntime.InspectionTimeoutException e){error(res,504,"ATT-SERVER-INSPECTION-TIMEOUT","Package inspection exceeded its time limit",requestId);}
          catch(ServerRuntime.InspectionResponseTooLargeException e){error(res,413,"ATT-SERVER-INSPECTION-RESPONSE-TOO-LARGE","Package inspection response exceeded the configured limit",requestId);}
          catch(ServerRuntime.InspectionConfigurationException e){error(res,422,"ATT-CFG-001",e.summary(),requestId,e.diagnostics);}
          catch(PackageConfigurationPolicy.EnvironmentDeniedException e){error(res,403,"ATT-PACKAGE-ENVIRONMENT-DENIED",safeDetail(e),requestId);}
          catch(IllegalArgumentException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(e),requestId);}
          catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The schedule request could not be completed",requestId);getServletContext().log("ATT Server schedule read failed id="+requestId,e);}
    }

    private void schedulePost(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String principal)throws IOException {
        if(!requireOperator(req,res,requestId))return;
        if(!checkOrigin(req,res,requestId,false))return;
        String[] parts=path.split("/");
        try {
            if(parts.length==2){JsonNode body=scheduleBody(req,res,requestId);if(body==null)return;LoadSchedule item=runtime.createLoadSchedule(body,principal);res.setHeader("Location",req.getContextPath()+"/api/v1/load-schedules/"+item.id);Map<String,Object> view=runtime.loadScheduleView(item,principal);view.put("requestId",requestId);json(res,201,view);return;}
            if(parts.length==3&&"preview".equals(parts[2])){JsonNode body=scheduleBody(req,res,requestId);if(body==null)return;Map<String,Object> preview=new LinkedHashMap<>(runtime.previewLoadSchedule(body));preview.put("requestId",requestId);json(res,200,preview);return;}
            if(parts.length==4&&"trigger".equals(parts[3])){Map<String,Object> result=runtime.triggerLoadSchedule(parts[2],principal);result.put("requestId",requestId);json(res,202,result);return;}
            if(parts.length==4&&("pause".equals(parts[3])||"resume".equals(parts[3]))) {
                LoadSchedule item=runtime.setLoadScheduleEnabled(parts[2],"resume".equals(parts[3]),principal);Map<String,Object> view=runtime.loadScheduleView(item,principal);view.put("requestId",requestId);json(res,200,view);return;
            }
            error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);
        } catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
          catch(ServerRuntime.ScheduleSourceChangedException e){error(res,409,"ATT-SERVER-SCHEDULE-SOURCE-CHANGED","package-revision-changed",requestId);}
          catch(ServerRuntime.DraftValidationException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",e.getMessage(),requestId,e.diagnostics);}
          catch(ServerRuntime.StaleDraftException e){error(res,409,"ATT-SERVER-DRAFT-STALE","The validated execution draft expired or changed; validate it again",requestId);}
          catch(ServerRuntime.InspectionCapacityException e){error(res,503,"ATT-SERVER-INSPECTION-CAPACITY","Package inspection capacity is full; retry shortly",requestId);}
          catch(ServerRuntime.InspectionTimeoutException e){error(res,504,"ATT-SERVER-INSPECTION-TIMEOUT","Package inspection exceeded its time limit",requestId);}
          catch(ServerRuntime.InspectionResponseTooLargeException e){error(res,413,"ATT-SERVER-INSPECTION-RESPONSE-TOO-LARGE","Package inspection response exceeded the configured limit",requestId);}
          catch(ServerRuntime.InspectionConfigurationException e){error(res,422,"ATT-CFG-001",e.summary(),requestId,e.diagnostics);}
          catch(PackageConfigurationPolicy.EnvironmentDeniedException e){error(res,403,"ATT-PACKAGE-ENVIRONMENT-DENIED",safeDetail(e),requestId);}
          catch(IllegalArgumentException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(e),requestId);}
          catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The schedule request could not be completed",requestId);getServletContext().log("ATT Server schedule action failed id="+requestId,e);}
    }

    private void schedulePatch(HttpServletRequest req,HttpServletResponse res)throws IOException {
        String requestId=requestId(req,res),path=path(req),principal=principal(req);
        if(!loadSchedulePath(path)){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return;}
        if(principal==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","An authenticated Servlet Principal is required",requestId);return;}
        if(!requireOperator(req,res,requestId))return;
        if(!checkOrigin(req,res,requestId,false))return;
        String[] parts=path.split("/");if(parts.length!=3){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return;}
        JsonNode body=scheduleBody(req,res,requestId);if(body==null)return;
        try{LoadSchedule item=runtime.updateLoadSchedule(parts[2],body,principal);Map<String,Object> view=runtime.loadScheduleView(item,principal);view.put("requestId",requestId);json(res,200,view);}
        catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
        catch(ServerRuntime.ScheduleSourceChangedException e){error(res,409,"ATT-SERVER-SCHEDULE-SOURCE-CHANGED","package-revision-changed",requestId);}
        catch(ServerRuntime.ScheduleConflictException e){error(res,409,"ATT-SERVER-SCHEDULE-CHANGED","The schedule changed while it was being edited; reload it and retry",requestId);}
        catch(ServerRuntime.DraftValidationException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",e.getMessage(),requestId,e.diagnostics);}
        catch(ServerRuntime.InspectionCapacityException e){error(res,503,"ATT-SERVER-INSPECTION-CAPACITY","Package inspection capacity is full; retry shortly",requestId);}
        catch(ServerRuntime.InspectionTimeoutException e){error(res,504,"ATT-SERVER-INSPECTION-TIMEOUT","Package inspection exceeded its time limit",requestId);}
        catch(ServerRuntime.InspectionResponseTooLargeException e){error(res,413,"ATT-SERVER-INSPECTION-RESPONSE-TOO-LARGE","Package inspection response exceeded the configured limit",requestId);}
        catch(PackageConfigurationPolicy.EnvironmentDeniedException e){error(res,403,"ATT-PACKAGE-ENVIRONMENT-DENIED",safeDetail(e),requestId);}
          catch(IllegalArgumentException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(e),requestId);}
        catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The schedule could not be updated",requestId);getServletContext().log("ATT Server schedule update failed id="+requestId,e);}
    }

    private void scheduleDelete(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String principal)throws IOException {
        if(!requireOperator(req,res,requestId))return;
        if(!checkOrigin(req,res,requestId,false))return;
        String[] parts=path.split("/");if(parts.length!=3){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);return;}
        try{runtime.deleteLoadSchedule(parts[2],principal);json(res,200,Map.of("scheduleId",parts[2],"status","DELETED","requestId",requestId));}
        catch(ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId);}
        catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The schedule could not be deleted",requestId);getServletContext().log("ATT Server schedule delete failed id="+requestId,e);}
    }

    private JsonNode scheduleBody(HttpServletRequest req,HttpServletResponse res,String requestId)throws IOException {
        if(!isJson(req.getContentType())){error(res,415,"ATT-SERVER-UNSUPPORTED-MEDIA-TYPE","Content-Type must be application/json",requestId);return null;}
        byte[] body=req.getInputStream().readNBytes(runtime.config.maxRequestBytes+1);
        if(body.length>runtime.config.maxRequestBytes){error(res,413,"ATT-SERVER-REQUEST-TOO-LARGE","Request body exceeds the configured size limit",requestId);return null;}
        JsonNode parsed;try{parsed=ServerRuntime.JSON.readTree(body);}catch(JsonProcessingException malformed){error(res,400,"ATT-SERVER-INVALID-REQUEST","Request body is not valid JSON",requestId);return null;}
        if(parsed==null||!parsed.isObject()){error(res,400,"ATT-SERVER-INVALID-REQUEST","A JSON object is required",requestId);return null;}return parsed;
    }

    private static int parseScheduleLimit(String raw){if(raw==null||raw.isBlank())return 100;if(!raw.matches("[0-9]{1,3}"))throw new IllegalArgumentException("limit must be between 1 and 500");int limit=Integer.parseInt(raw);if(limit<1||limit>500)throw new IllegalArgumentException("limit must be between 1 and 500");return limit;}
    private static boolean loadSchedulePath(String path){return path.matches("/(?:load-schedules|schedules)(?:/[^/]+(?:/(?:executions|trigger|pause|resume))?)?");}

    private void sse(HttpServletRequest req,HttpServletResponse res,String jobId) throws IOException {
        String sessionHash=null;Object localSession=req.getAttribute(AuthService.SESSION);if(localSession instanceof Map<?,?> session&&session.get("tokenHash") instanceof String hash)sessionHash=hash;
        String last=req.getHeader("Last-Event-ID");long cursor=0;
        if(last!=null&&!last.isBlank())try{cursor=Long.parseLong(last);}catch(NumberFormatException e){error(res,400,"ATT-SERVER-INVALID-LAST-EVENT-ID","Last-Event-ID must be a non-negative integer",requestId(req,res));return;}
        if(cursor<0){error(res,400,"ATT-SERVER-INVALID-LAST-EVENT-ID","Last-Event-ID must be a non-negative integer",requestId(req,res));return;}
        JobEvents events;Path packageRoot;Path outputDirectory;try{events=runtime.events(jobId);packageRoot=runtime.packageRootForJob(jobId);outputDirectory=runtime.outputDirectoryForJob(jobId);}catch(ServerRuntime.NotFoundException missing){error(res,404,"ATT-SERVER-NOT-FOUND","API resource was not found",requestId(req,res));return;}catch(Exception e){error(res,500,"ATT-SERVER-REQUEST-FAILED","The event journal is unavailable",requestId(req,res));return;}
        if(!runtime.streamSlots.tryAcquire()){error(res,503,"ATT-SERVER-STREAM-CAPACITY","SSE observer capacity is full",requestId(req,res));return;}
        boolean handedOff=false;
        try {
            AsyncContext async=req.startAsync();async.setTimeout(0);final long start=cursor;
            res.setStatus(200);res.setCharacterEncoding("UTF-8");res.setContentType("text/event-stream");res.setHeader("Cache-Control","no-cache, no-transform");res.setHeader("X-Accel-Buffering","no");
            String activeSessionHash=sessionHash;runtime.streams.execute(()->{try{stream(async,jobId,events,start,outputDirectory,packageRoot,activeSessionHash);}finally{runtime.streamSlots.release();}});handedOff=true;
        } catch(RejectedExecutionException full) {
            if(!res.isCommitted()){res.resetBuffer();error(res,503,"ATT-SERVER-STREAM-CAPACITY","SSE observer capacity is full",requestId(req,res));}
            if(req.isAsyncStarted())req.getAsyncContext().complete();
        } finally {if(!handedOff)runtime.streamSlots.release();}
    }
    private void stream(AsyncContext async,String jobId,JobEvents journal,long cursor,Path outputDirectory,Path packageRoot,String sessionHash){
        long lastWrite=System.nanoTime();java.util.concurrent.ArrayBlockingQueue<Boolean> wakeup=new java.util.concurrent.ArrayBlockingQueue<>(1);
        AutoCloseable subscription=journal.listen(event->wakeup.offer(Boolean.TRUE));wakeup.offer(Boolean.TRUE);
        try(PrintWriter out=async.getResponse().getWriter()){
            while(true){
                wakeup.poll(15,TimeUnit.SECONDS);
                if(sessionHash!=null&&!runtime.auth.sessionStillValid(sessionHash))break;
                List<Map<String,Object>> events=journal.after(cursor,100);
                for(Map<String,Object> event:events){long id=((Number)event.get("id")).longValue();Object projected=runtime.publicEventData(event.get("data"),outputDirectory,packageRoot);if(projected instanceof com.fasterxml.jackson.databind.node.ObjectNode node && journal.truncatedHistory() && id==journal.oldestId()) node.put("historyTruncated",true);out.print("id: "+id+"\nevent: "+event.get("event")+"\ndata: "+ServerRuntime.JSON.writeValueAsString(projected)+"\n\n");cursor=id;}
                if(!events.isEmpty()){out.flush();if(out.checkError())return;lastWrite=System.nanoTime();}
                if(journal.hasMore(cursor)){wakeup.offer(Boolean.TRUE);continue;}
                if(runtime.terminal(jobId)&&journal.resultDeliveredThrough(cursor)&&!journal.hasMore(cursor))break;
                if(System.nanoTime()-lastWrite>TimeUnit.SECONDS.toNanos(15)){out.print(": keepalive\n\n");out.flush();if(out.checkError())return;lastWrite=System.nanoTime();}
            }
        }catch(Exception ignored){}finally{
            try{subscription.close();}catch(Exception ignored){}
            // A disconnected SSE client can make Tomcat enter COMPLETING before this worker exits.
            try{async.complete();}catch(IllegalStateException alreadyCompleting){}
        }
    }
    private void resourceGet(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String principal)throws IOException {
        String[] parts=path.split("/");
        try {
            if(parts.length<4||!"packages".equals(parts[1])||parts[2].isEmpty()||!"resources".equals(parts[3]))throw new ServerRuntime.NotFoundException();
            String packageId=parts[2],action,kind=null,resourceId=null;String query=req.getParameter("query"),cursor=req.getParameter("cursor");int limit=parseLimit(req.getParameter("limit"));
            if(parts.length==4)action="list";
            else if(parts.length==7&&"source".equals(parts[6])){action="source";kind=parts[4];resourceId=parts[5];}
            else if(parts.length==7&&"source-file".equals(parts[6])){action="source-file";kind=parts[4];resourceId=parts[5];query=req.getParameter("referenceId");}
            else if(parts.length==7&&"debug-form".equals(parts[6])){action="debug-form";kind=parts[4];resourceId=parts[5];}
            else if(parts.length==7&&"quick-load-form".equals(parts[6])){action="quick-load-form";kind=parts[4];resourceId=parts[5];}
            else if(parts.length==6){action="detail";kind=parts[4];resourceId=parts[5];}
            else throw new ServerRuntime.NotFoundException();
            if("list".equals(action)){kind=req.getParameter("type");resourceId=null;}
            if(resourceId!=null&&(resourceId.isEmpty()||resourceId.length()>512))throw new IllegalArgumentException("resourceId is invalid");
            String sourceOrigin="source-file".equals(action)?req.getParameter("origin"):null;
            String sort="list".equals(action)?req.getParameter("sort"):null;
            String direction="list".equals(action)?req.getParameter("direction"):null;
            Map<String,Object> result="debug-form".equals(action)
                    ?runtime.inspectDebugForm(packageId,kind,resourceId,req.getParameter("environment"),principal)
                    :"quick-load-form".equals(action)?runtime.inspectQuickLoadForm(packageId,kind,resourceId,req.getParameter("model"),req.getParameter("environment"),principal)
                    :runtime.inspectResource(packageId,action,kind,resourceId,query,limit,cursor,sort,direction,sourceOrigin,principal);
            Object cacheRevision=result.remove("_cacheRevision");
            if(cacheRevision instanceof String)res.setHeader("X-ATT-Inspection-Revision",(String)cacheRevision);
            res.setHeader("Cache-Control","no-store");
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
          catch(ServerRuntime.InspectionConfigurationException e){throw e;}
          catch(ServerRuntime.InlineLoadDisabledException e){throw e;}
          catch(IllegalArgumentException e){throw e;}
          catch(Exception e){throw new IOException(e);}
    }
    private void loadCatalogGet(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String principal)throws IOException {
        String[] parts=path.split("/");
        try {
            if(parts.length<4||parts.length>5||!"packages".equals(parts[1])||parts[2].isEmpty()||!"load-catalog".equals(parts[3]))throw new ServerRuntime.NotFoundException();
            Map<String,Object> result;
            if(parts.length==5&&"source".equals(parts[4])) result=runtime.inspectLoadSource(parts[2],req.getParameter("resourceId"),principal);
            else if(parts.length==5&&"validate".equals(parts[4])) result=runtime.validateLoadCatalogScenario(parts[2],req.getParameter("resourceId"),req.getParameter("environment"),principal);
            else if(parts.length==5&&"detail".equals(parts[4])) result=runtime.inspectLoadCatalogDetail(parts[2],req.getParameter("resourceId"),principal);
            else if(parts.length==4) result=runtime.inspectLoadCatalog(parts[2],req.getParameter("query"),parseLimit(req.getParameter("limit")),req.getParameter("cursor"),principal);
            else throw new ServerRuntime.NotFoundException();
            Object cacheRevision=result.remove("_cacheRevision");
            if(cacheRevision instanceof String)res.setHeader("X-ATT-Inspection-Revision",(String)cacheRevision);
            res.setHeader("Cache-Control","no-store");result.put("requestId",requestId);json(res,200,result);
        } catch(ServerRuntime.NotFoundException e){throw e;}
          catch(ServerRuntime.StaleCursorException e){throw e;}
          catch(ServerRuntime.InspectionCapacityException e){throw e;}
          catch(ServerRuntime.InspectionTimeoutException e){throw e;}
          catch(ServerRuntime.InspectionResponseTooLargeException e){throw e;}
          catch(ServerRuntime.InspectionConfigurationException e){throw e;}
          catch(IllegalArgumentException e){throw e;}
          catch(Exception e){throw new IOException(e);}
    }
    private void configurationGet(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String principal)throws IOException {
        boolean shared=path.equals("/configuration")||path.startsWith("/configuration/");
        // Reuse validation and response policy, never resolve a registered Package for shared views.
        String[] parts=(shared?"/packages/_/configuration"+path.substring("/configuration".length()):path).split("/");
        try {
            if(shared&&parts.length==5&&"declared".equals(parts[4]))parts=java.util.Arrays.copyOf(parts,4);
            if(shared&&parts.length>5)throw new ServerRuntime.NotFoundException();
            if(parts.length==7&&"packages".equals(parts[1])&&!parts[2].isEmpty()&&"configuration".equals(parts[3])
                    &&"testdata".equals(parts[4])&&"records".equals(parts[6])) {
                boolean previewGenerated=parseOptionalBoolean(req.getParameter("previewGenerated"),"previewGenerated");
                Map<String,Object> records=runtime.inspectTestdataRecords(parts[2],segment(path,5),req.getParameter("environment"),
                        parseOffset(req.getParameter("offset")),parseConfigurationLimit(req.getParameter("limit")),previewGenerated,principal);
                records.put("requestId",requestId);json(res,200,records);return;
            }
            if(parts.length<4||!"packages".equals(parts[1])||parts[2].isEmpty()||!"configuration".equals(parts[3]))throw new ServerRuntime.NotFoundException();
            String packageId=shared?"":parts[2],action;
            if(parts.length==4) {
                String view=req.getParameter("view");
                if(view!=null&&!"declared".equals(view))throw new IllegalArgumentException("view must be declared");
                action="declared";
            } else if(parts.length==5&&"effective".equals(parts[4])) action="effective";
            else if(parts.length==5&&"compare".equals(parts[4])) action="compare";
            else if(parts.length==7&&"testdata".equals(parts[4])&&"records".equals(parts[6])) action="testdata-records";
            else if(parts.length==5&&"files".equals(parts[4])) action="files";
            else if(parts.length==5&&"source".equals(parts[4])) action="source";
            else throw new ServerRuntime.NotFoundException();
            String environment="effective".equals(action)||"testdata-records".equals(action)?req.getParameter("environment"):"compare".equals(action)?req.getParameter("left"):null;
            String otherEnvironment="compare".equals(action)?req.getParameter("right"):null;
            String section="effective".equals(action)||"compare".equals(action)?req.getParameter("section"):
                    "testdata-records".equals(action)?parts[5]:null;
            boolean includeInherited="compare".equals(action)&&parseOptionalBoolean(req.getParameter("includeInherited"),"includeInherited");
            int offset=parseOffset(req.getParameter("offset"));
            int limit="declared".equals(action)||"source".equals(action)?0:parseConfigurationLimit(req.getParameter("limit"));
            String inspectionPath="files".equals(action)||"source".equals(action)?req.getParameter("path"):null;
            String inspectionOrigin="source".equals(action)?req.getParameter("origin"):null;
            String inspectionQuery="files".equals(action)||"effective".equals(action)?req.getParameter("query"):
                    "testdata-records".equals(action)&&parseOptionalBoolean(req.getParameter("previewGenerated"),"previewGenerated")
                            ?"preview-generated":null;
            String inspectionReferenceId="source".equals(action)?req.getParameter("referenceId"):null;
            boolean includeAllFields=parseOptionalBoolean(req.getParameter("includeAllFields"),"includeAllFields");
            if(includeAllFields) {
                if(!Set.of("effective","compare").contains(action))throw new IllegalArgumentException("includeAllFields is available only for effective or compare configuration views");
                if(!configurationAdmin(req,res,requestId))return;
                res.setHeader("Cache-Control","no-store");
                runtime.store.audit(principal,"CONFIGURATION_INCLUDE_ALL_FIELDS",null,packageId,"SUCCESS");
            }
            res.setHeader("Cache-Control","no-store");
            Map<String,Object> result=runtime.inspectConfiguration(packageId,action,environment,otherEnvironment,section,
                    offset,limit,inspectionPath,inspectionOrigin,inspectionQuery,includeInherited,inspectionReferenceId,includeAllFields,principal);
            if("declared".equals(action)||"effective".equals(action)||"compare".equals(action)) {
                ConfigurationInspection.Response response=ServerRuntime.JSON.convertValue(result,ConfigurationInspection.Response.class);
                response.requestId=requestId;json(res,200,response);
            } else {
                result.put("requestId",requestId);json(res,200,result);
            }
        } catch(ServerRuntime.NotFoundException e){throw e;}
          catch(ServerRuntime.InspectionCapacityException e){throw e;}
          catch(ServerRuntime.InspectionTimeoutException e){throw e;}
          catch(ServerRuntime.InspectionResponseTooLargeException e){throw e;}
          catch(IllegalArgumentException e){throw e;}
          catch(Exception e){throw new IOException(e);}
    }
    private boolean configurationAdmin(HttpServletRequest req,HttpServletResponse res,String requestId)throws IOException {
        String login=principal(req);
        if("local".equals(runtime.config.authMode)) {
            if(login==null){error(res,401,"ATT-SERVER-AUTHENTICATION-REQUIRED","Authentication is required",requestId);return false;}
            try {
                Map<String,Object> user=runtime.store.authUser(login);
                if(user!=null&&"ADMIN".equals(user.get("role")))return true;
            } catch(Exception failure) {
                error(res,500,"ATT-SERVER-REQUEST-FAILED","The configuration request could not be completed",requestId);return false;
            }
            error(res,403,"ATT-SERVER-FORBIDDEN","Administrator access is required",requestId);return false;
        }
        if(login!=null&&req.isUserInRole(runtime.config.inspection.configurationAdminRole))return true;
        error(res,403,"ATT-SERVER-FORBIDDEN","The configured configuration administrator role is required",requestId);return false;
    }
    private void loadPolicyGet(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String principal)throws IOException {
        String[] parts=path.split("/");
        try {
            if(parts.length!=4||!"packages".equals(parts[1])||parts[2].isEmpty()||!"load-policy".equals(parts[3]))
                throw new ServerRuntime.NotFoundException();
            Map<String,Object> result=runtime.inspectQuickLoadPolicy(parts[2],req.getParameter("model"),
                    req.getParameter("environment"),req.getParameter("templateId"),principal);
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
    private static int parseOffset(String raw){if(raw==null||raw.isEmpty())return 0;if(!raw.matches("[0-9]{1,6}"))throw new IllegalArgumentException("offset must be a non-negative integer");return Integer.parseInt(raw);}
    private static int parseConfigurationLimit(String raw){if(raw==null||raw.isEmpty())return 50;if(!raw.matches("[0-9]{1,3}"))throw new IllegalArgumentException("limit must be between 1 and 100");int value=Integer.parseInt(raw);if(value<1||value>100)throw new IllegalArgumentException("limit must be between 1 and 100");return value;}
    private static boolean parseOptionalBoolean(String raw,String name){if(raw==null||raw.isEmpty())return false;if("true".equalsIgnoreCase(raw))return true;if("false".equalsIgnoreCase(raw))return false;throw new IllegalArgumentException(name+" must be true or false");}
    private static String principalSearch(HttpServletRequest req){String value=req.getParameter("principal");if(value==null)return null;value=value.strip();if(value.codePointCount(0,value.length())>120)throw new IllegalArgumentException("principal search must be at most 120 characters");for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))throw new IllegalArgumentException("principal search contains an invalid character");return value.isEmpty()?null:value;}
    private static boolean resourcePath(String path){return path.matches("/packages/[^/]+/resources(?:/.*)?");}
    private void manifestGet(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String actor) throws IOException {
        String[] parts=path.split("/");String action=parts.length==4?"effective":parts[4];
        try {
            Map<String,Object> result="history".equals(action)?new java.util.LinkedHashMap<>(Map.of("items",runtime.manifestPublisher.history(segment(path,2),actor))):
                    runtime.inspectPackageConfiguration(segment(path,2),action,req.getParameter("environment"),actor);
            result.put("requestId",requestId);res.setHeader("Cache-Control","no-store");json(res,200,result);
        } catch(PackageManifestPublisher.Denied e){error(res,403,"ATT-PACKAGE-SOURCE-DENIED","Package source editing is not authorized",requestId);}
          catch(PackageManifestPublisher.Missing|ServerRuntime.NotFoundException e){error(res,404,"ATT-SERVER-NOT-FOUND","Package source was not found",requestId);}
          catch(ServerRuntime.InspectionCapacityException e){error(res,503,"ATT-SERVER-INSPECTION-CAPACITY","Package inspection capacity is full",requestId);}
          catch(ServerRuntime.InspectionTimeoutException e){error(res,504,"ATT-SERVER-INSPECTION-TIMEOUT","Package inspection exceeded its time limit",requestId);}
          catch(ServerRuntime.InspectionConfigurationException e){error(res,422,"ATT-CFG-001",e.summary(),requestId,e.diagnostics);}
          catch(PackageConfigurationPolicy.EnvironmentDeniedException e){error(res,403,"ATT-PACKAGE-ENVIRONMENT-DENIED",safeDetail(e),requestId);}
          catch(IllegalArgumentException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(e),requestId);}
          catch(Exception e){error(res,500,"ATT-PACKAGE-SOURCE-FAILED","Package configuration could not be read",requestId);getServletContext().log("ATT Package Configuration failed id="+requestId,e);}
    }
    private void manifestPost(HttpServletRequest req,HttpServletResponse res,String path,String requestId,String actor) throws IOException {
        if(!isJson(req.getContentType())){error(res,415,"ATT-SERVER-UNSUPPORTED-MEDIA-TYPE","Content-Type must be application/json",requestId);return;}
        if(!checkOrigin(req,res,requestId,false))return;
        try {
            int limit=Math.min(runtime.config.maxRequestBytes,PackageManifestPublisher.MAX_SOURCE_BYTES*6+4096);
            byte[] bytes=req.getInputStream().readNBytes(limit+1);
            if(bytes.length>limit){error(res,413,"ATT-SERVER-REQUEST-TOO-LARGE","Manifest request exceeds its size limit",requestId);return;}
            JsonNode input=ServerRuntime.JSON.readTree(bytes);
            if(input==null||!input.isObject())throw new IllegalArgumentException("A JSON object is required");
            String packageId=segment(path,2);String[] parts=path.split("/");Map<String,Object> result;
            if(parts.length==5) {
                String sourceField="rollback".equals(parts[4])?"revision":"source";
                java.util.Iterator<String> fields=input.fieldNames();
                while(fields.hasNext())if(!java.util.Set.of(sourceField,"expectedManifestDigest").contains(fields.next()))throw new IllegalArgumentException("Unsupported Manifest request field");
                if(!input.path(sourceField).isTextual()||!input.path("expectedManifestDigest").isTextual())throw new IllegalArgumentException("Manifest source or revision and expectedManifestDigest are required");
                result="rollback".equals(parts[4])?runtime.manifestPublisher.rollbackDraft(packageId,actor,input.get("revision").asText(),input.get("expectedManifestDigest").asText()):
                        runtime.manifestPublisher.stage(packageId,actor,input.get("source").asText(),input.get("expectedManifestDigest").asText());
            } else {
                if(input.size()!=0)throw new IllegalArgumentException("Manifest draft actions accept an empty object");
                String id=parts[5];
                result=switch(parts[6]) {
                    case "validate" -> runtime.manifestPublisher.validate(packageId,actor,id);
                    case "review" -> runtime.manifestPublisher.review(packageId,actor,id);
                    case "publish" -> runtime.manifestPublisher.publish(packageId,actor,id);
                    case "discard" -> {runtime.manifestPublisher.discard(packageId,actor,id);yield new java.util.LinkedHashMap<>(Map.of("discarded",true));}
                    default -> throw new IllegalArgumentException("Unsupported Manifest action");
                };
            }
            result.put("requestId",requestId);res.setHeader("Cache-Control","no-store");json(res,200,result);
        } catch(PackageManifestPublisher.Denied e){error(res,403,"ATT-PACKAGE-SOURCE-DENIED","Package source editing is not authorized",requestId);}
          catch(PackageManifestPublisher.Missing e){error(res,404,"ATT-SERVER-NOT-FOUND","Manifest draft or revision was not found",requestId);}
          catch(PackageManifestPublisher.Conflict e){error(res,409,"ATT-PACKAGE-SOURCE-CONFLICT","Package or configuration sources changed; validate a new draft",requestId);}
          catch(PackageManifestPublisher.Capacity e){error(res,429,"ATT-PACKAGE-SOURCE-CAPACITY","Manifest draft capacity is full",requestId);}
          catch(ServerRuntime.DraftValidationException e){error(res,400,"ATT-PACKAGE-SOURCE-INVALID",e.getMessage(),requestId,e.diagnostics);}
          catch(PackageConfigurationPolicy.EnvironmentDeniedException e){error(res,403,"ATT-PACKAGE-ENVIRONMENT-DENIED",safeDetail(e),requestId);}
          catch(IllegalArgumentException e){error(res,400,"ATT-SERVER-INVALID-REQUEST",safeDetail(e),requestId);}
          catch(Exception e){error(res,500,"ATT-PACKAGE-SOURCE-FAILED","Manifest action could not be completed",requestId);getServletContext().log("ATT Manifest action failed id="+requestId,e);}
    }
    private static boolean configurationPath(String path){return path.matches("/packages/[^/]+/package-configuration(?:/.*)?")||path.matches("/packages/[^/]+/configuration(?:/.*)?")||path.matches("/configuration(?:/.*)?");}
    private static boolean loadPolicyPath(String path){return path.matches("/packages/[^/]+/load-policy");}
    private static boolean isJson(String value){if(value==null)return false;String[] parts=value.split(";",2);return "application/json".equalsIgnoreCase(parts[0].trim());}
    private boolean checkOrigin(HttpServletRequest req,HttpServletResponse res,String requestId,boolean force)throws IOException {
        if(!force&&!runtime.config.security.checkCrossOrigin)return true;
        if(runtime.config.security.originPolicy.allows(req))return true;
        try{runtime.store.audit(principal(req),"ORIGIN_REJECT",null,null,"ATT-SERVER-ORIGIN-MISMATCH");}catch(Exception ignored){}
        error(res,403,"ATT-SERVER-ORIGIN-MISMATCH","Request Origin does not match the server origin",requestId);
        return false;
    }
    private static String path(HttpServletRequest r){String p=r.getPathInfo();return p==null||p.isEmpty()?"/":p;}
    private static String segment(String path,int index){String[] parts=path.split("/");return parts.length>index?parts[index]:"";}
    static String principal(HttpServletRequest req){Object value=req.getServletContext().getAttribute(ServerBootstrap.RUNTIME);if(value instanceof ServerRuntime runtime&&"local".equals(runtime.config.authMode)){Object identity=req.getAttribute(AuthService.IDENTITY);return identity instanceof String?(String)identity:null;}if(value instanceof ServerRuntime runtime&&!runtime.config.authenticationRequired)return RequestIdentity.ANONYMOUS;Principal p=req.getUserPrincipal();if(p==null)return null;String name=p.getName();if(name==null||name.isBlank()||name.length()>256)return null;for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))return null;return name.trim();}
    private static String requestId(HttpServletRequest req,HttpServletResponse res){String value=req.getHeader("X-Request-ID");if(value==null||!value.matches("[A-Za-z0-9._-]{1,80}"))value=UUID.randomUUID().toString();res.setHeader("X-Request-ID",value);return value;}
    private static void json(HttpServletResponse res,int status,Object body)throws IOException{res.setStatus(status);res.setCharacterEncoding("UTF-8");res.setContentType("application/json");ServerRuntime.JSON.writeValue(res.getOutputStream(),body);}
    private static void error(HttpServletResponse res,int status,String code,String summary,String requestId)throws IOException{Map<String,Object> e=new LinkedHashMap<>();e.put("code",code);e.put("summary",summary);e.put("detail",summary);e.put("requestId",requestId);json(res,status,Map.of("error",e));}
    private static void error(HttpServletResponse res,int status,String code,String summary,String requestId,List<Map<String,Object>> diagnostics)throws IOException{
        Map<String,Object> e=new LinkedHashMap<>();e.put("code",code);e.put("summary",summary);e.put("detail",summary);e.put("requestId",requestId);
        List<Map<String,Object>> correlated=new ArrayList<>();
        if(diagnostics!=null)for(Map<String,Object> diagnostic:diagnostics){Map<String,Object> item=new LinkedHashMap<>(diagnostic);item.putIfAbsent("requestId",requestId);correlated.add(item);}
        Map<String,Object> response=new LinkedHashMap<>();response.put("error",e);response.put("diagnostics",correlated);response.put("requestId",requestId);json(res,status,response);
    }
    static String safeDetail(IllegalArgumentException e){String m=e.getMessage();if(m==null)return "Invalid request";m=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(att.server.api.internal.DiagnosticSanitizer.redactText(m));return m.length()>300?m.substring(0,300):m;}
}
