package att.remote;

import att.remote.artifact.ArtifactDownloader;
import att.remote.config.ServerProfile;
import att.remote.config.ServerProfileLoader;
import att.remote.http.RemoteHttpClient;
import att.remote.render.RemoteRenderer;
import att.remote.sse.SseEvent;
import att.remote.sse.SseReader;
import att.server.api.ApiStatus;
import att.server.api.ServerApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Public command adapter over ATT Server's REST and SSE API. */
public final class RemoteCommand {
    private RemoteCommand() { }
    public static int run(String[] args) {
        return run(args,System.getenv(),ServerProfileLoader.profilePath(),systemConsolePrompt(),
                (profile,password,username,provider)->new RemoteHttpClient(profile,password,username,provider));
    }
    /** Run the typed vNext plan through the existing authenticated Server transport. */
    public static int runVNext(String server,String format,Map<String,Object> plan) {
        try {
            List<String> args=new ArrayList<>();
            if(server!=null){args.add("--server");args.add(server);}
            args.add("--format");args.add(format==null?"human":format);
            args.add("vnext");args.add(RemoteHttpClient.JSON.writeValueAsString(plan));
            return run(args.toArray(new String[0]));
        } catch(Exception failure) { return fail("Unable to prepare Remote ATT command: "+safe(failure),4,format==null?"human":format); }
    }
    static int run(String[] args,Map<String,String> environment,Path profilePath,CredentialPrompt prompt,RemoteClientFactory clientFactory) {
        String format="human";
        try {
            RemoteOptions options=RemoteOptions.parse(args);format=options.format;
            if(options.command.isEmpty()||"help".equals(options.command.get(0))){help();return 0;}
            List<String> command=options.command;String op=command.get(0);
            ServerProfile profile=ServerProfileLoader.resolve(options.server,environment.get("ATT_SERVER"),profilePath,options.noAuth);
            RemoteHttpClient client;
            if(profile.basicAuth) {
                Credentials credentials=password(profile,environment,prompt);
                client=clientFactory.create(profile,credentials.password,credentials.username,null);
            } else if(profile.autoAuth) {
                client=clientFactory.create(profile,null,profile.username,()->{
                    Credentials credentials=password(profile,environment,prompt);
                    return new RemoteHttpClient.Credentials(credentials.username,credentials.password);
                });
                if(requiresCredentialPreflight(op)) client.get("vnext".equals(op)?"/auth/capabilities":"/packages");
            } else {
                client=clientFactory.create(profile,null,null,null);
            }
            JsonNode version=client.get("/version");
            if(!version.path("apiVersion").isTextual()||!ServerApi.VERSION.equals(version.path("apiVersion").asText()))throw new RemoteException("ATT Server API is missing or unsupported; client supports API v"+ServerApi.VERSION);
            if("ping".equals(op)){JsonNode health=client.get("/health");if("json".equals(format))System.out.println(health.toString());else System.out.println("ATT Server "+profile.url+": "+health.path("status").asText("UNKNOWN"));return 0;}
            if("vnext".equals(op)){if(command.size()!=2)throw new IllegalArgumentException("Invalid typed vNext command plan");JsonNode plan;try{plan=RemoteHttpClient.JSON.readTree(command.get(1));}catch(Exception invalid){throw new IllegalArgumentException("Invalid typed vNext command plan");}if(plan==null||!plan.isObject())throw new IllegalArgumentException("Invalid typed vNext command plan");return runVNext(client,plan,format);}
            if("version".equals(op)){if("json".equals(format))System.out.println(version.toString());else System.out.println("ATT Server "+version.path("version").asText("unknown")+" (API v"+version.path("apiVersion").asText()+")");return 0;}
            if("packages".equals(op))return packages(client,command,format);
            if("package".equals(op))return packageView(client,command,format);
            if("jobs".equals(op))return jobs(client,command,format);
            if("job".equals(op))return job(client,command,format);
            if("result".equals(op))return result(client,command,format);
            if("cancel".equals(op))return cancel(client,command,format);
            if("watch".equals(op))return watch(client,command,format);
            if("artifacts".equals(op))return artifacts(client,command,format);
            if("artifact".equals(op))return artifact(client,command);
            if("run".equals(op)||"debug".equals(op)||"load".equals(op)||"validate".equals(op))return submit(client,command,format,op);
            throw new IllegalArgumentException("Unknown att remote command: "+op);
        } catch(IllegalArgumentException e) { return fail(e.getMessage(),2,format); }
        catch(RemoteException e) { return fail(e.getMessage(),e.exitCode(),format); }
        catch(Exception e) { return fail("ATT Remote failed: "+safe(e),4,format); }
    }
    private static int packages(RemoteHttpClient c,List<String>a,String f)throws Exception {
        if(a.size()!=1)throw new IllegalArgumentException("Usage: att remote packages");JsonNode result=c.get("/packages").path("items");
        if("json".equals(f)){System.out.println(result.toString());return 0;}
        System.out.println("PACKAGE ID\tNAME");for(JsonNode item:result)System.out.println(item.path("packageId").asText()+"\t"+item.path("name").asText());return 0;
    }
    private static int packageView(RemoteHttpClient c,List<String>a,String f)throws Exception {
        if(a.size()!=2)throw new IllegalArgumentException("Usage: att remote package <packageId>");JsonNode result=c.get("/packages/"+segment(a.get(1)));
        if("json".equals(f))System.out.println(result.toString());else System.out.println(result.path("packageId").asText()+"\t"+result.path("name").asText());return 0;
    }
    private static int jobs(RemoteHttpClient c,List<String>a,String f)throws Exception {
        String path="/jobs";
        if(a.size()==3&&"--principal".equals(a.get(1))){if(a.get(2).trim().isEmpty())throw new IllegalArgumentException("--principal requires a non-empty search value");path+="?principal="+segment(a.get(2));}
        else if(a.size()!=1)throw new IllegalArgumentException("Usage: att remote jobs [--principal <search>]");
        JsonNode items=c.get(path).path("items");if("json".equals(f)){System.out.println(items.toString());return 0;}
        System.out.println("JOB\tPACKAGE\tCOMMAND\tPRINCIPAL\tSTATUS\tAGE");for(JsonNode i:items)System.out.println(i.path("jobId").asText()+"\t"+i.path("packageId").asText()+"\t"+i.path("command").asText()+"\t"+i.path("principal").asText()+"\t"+i.path("status").asText()+"\t"+age(i.path("createdAt").asText()));return 0;
    }
    private static int job(RemoteHttpClient c,List<String>a,String f)throws Exception {
        String id=jobId(a,1);JsonNode value=c.get("/jobs/"+id);if("json".equals(f))System.out.println(value.toString());else System.out.println(value.toPrettyString());return 0;
    }
    private static int result(RemoteHttpClient c,List<String>a,String f)throws Exception {
        String id=jobId(a,1);JsonNode value=c.get("/jobs/"+id+"/result");return renderResult(value,f);
    }
    private static int cancel(RemoteHttpClient c,List<String>a,String f)throws Exception {
        String id=jobId(a,1);JsonNode value=c.delete("/jobs/"+id);if("json".equals(f))System.out.println(value.toString());else System.out.println("Cancellation requested for "+id+" ("+value.path("status").asText()+")");return 0;
    }
    private static int watch(RemoteHttpClient c,List<String>a,String f)throws Exception {return follow(c,jobId(a,1),f);}
    static int submit(RemoteHttpClient c,List<String>a,String f,String operation)throws Exception {
        if(a.size()<2)throw new IllegalArgumentException("Usage: att remote "+operation+" <packageId> [ATT options]");String packageId=a.get(1);if(packageId.trim().isEmpty()||packageId.contains("/"))throw new IllegalArgumentException("packageId must be a logical Server package ID");
        ObjectNode body=RemoteHttpClient.JSON.createObjectNode();body.put("packageId",packageId);boolean detached=false;String debugType=null,debugId=null,scenario=null;
        ArrayNode suites=body.putArray("suites"),caseIds=body.putArray("caseIds"),tags=body.putArray("tags"),exclude=body.putArray("excludeTags"),overrides=body.putArray("overrides");ObjectNode load=body.putObject("load");
        int optionStart=2;
        if("debug".equals(operation)) optionStart=4;
        else if("load".equals(operation)&&a.size()>2&&!a.get(2).startsWith("--")){scenario=a.get(2);optionStart=3;}
        for(int i=optionStart;i<a.size();i++) {
            String key=a.get(i);
            if("--detach".equals(key)){detached=true;continue;}
            if("--all".equals(key)){body.put("all",true);continue;}
            if("--rerun-failed".equals(key)){body.put("rerunFailed",true);continue;}
            if("--dry-run".equals(key)){body.put("dryRun",true);continue;}
            if("--fail-fast".equals(key)){body.put("failFast",true);continue;}
            if("--package".equals(key)){body.put("validationScope","package");continue;}
            if("--selected".equals(key)){body.put("validationScope","selected");continue;}
            if("--debug".equals(key)&&"load".equals(operation)) { if(i+2>=a.size())throw new IllegalArgumentException("--debug requires target type and id");debugType=a.get(++i);debugId=a.get(++i);continue; }
            String value=optionValue(a,i,key);i++;
            if("--principal".equals(key))body.put("principal",value);
            else if("--suite".equals(key))suites.add(logicalPath(value,key));
            else if("--case".equals(key)||"--case-id".equals(key))caseIds.add(value);
            else if("--tag".equals(key))tags.add(value);
            else if("--exclude-tag".equals(key))exclude.add(value);
            else if("--env".equals(key))body.put("environment",value);
            else if("--config".equals(key))body.put("config",logicalPath(value,key));
            else if("--suite-dir".equals(key))body.put("suiteDirectory",logicalPath(value,key));
            else if("--run-id".equals(key))body.put("runId",value);
            else if("--debug-id".equals(key)&&"debug".equals(operation))body.put("debugId",value);
            else if("--input".equals(key))body.put("debugInput",logicalPath(value,key));
            else if("--set".equals(key))overrides.add(value);
            else if("--scenario".equals(key))scenario=logicalPath(value,key);
            else if("--users".equals(key))load.put("users",value);
            else if("--arrival-rate".equals(key))load.put("arrivalRate",value);
            else if("--warmup".equals(key))load.put("warmup",value);
            else if("--ramp-up".equals(key))load.put("rampUp",value);
            else if("--duration".equals(key))load.put("duration",value);
            else if("--ramp-down".equals(key))load.put("rampDown",value);
            else if("--think-time".equals(key))load.put("thinkTime",value);
            else if("--max-concurrent".equals(key))load.put("maxConcurrent",value);
            else if("--overload-policy".equals(key))load.put("overloadPolicy",value);
            else throw new IllegalArgumentException("Option is not available for att remote "+operation+": "+key);
        }
        if(suites.isEmpty())body.remove("suites");if(caseIds.isEmpty())body.remove("caseIds");if(tags.isEmpty())body.remove("tags");if(exclude.isEmpty())body.remove("excludeTags");if(overrides.isEmpty())body.remove("overrides");if(load.isEmpty())body.remove("load");
        if("debug".equals(operation)) {
            if(a.size()<4)throw new IllegalArgumentException("Usage: att remote debug <packageId> <template|flow|tool> <id>");
            debugType=a.get(2);debugId=a.get(3);
            if(!"template".equals(debugType)&&!"flow".equals(debugType)&&!"tool".equals(debugType))throw new IllegalArgumentException("Debug target must be template, flow, or tool");
        }
        if(debugType!=null){ObjectNode target=body.putObject("target");target.put("type",debugType);target.put("id",debugId);}
        if("load".equals(operation)&&scenario==null&&a.size()>2&&!a.get(2).startsWith("-"))scenario=logicalPath(a.get(2),"scenario");
        if("validate".equals(operation)&&!body.has("validationScope"))body.put("validationScope","package");
        if(scenario!=null)body.put("scenario",scenario);
        JsonNode accepted=c.post("/jobs/"+operation,body);
        String id=accepted.path("jobId").asText("");if(!id.matches("J[A-F0-9]{16}"))throw new RemoteException("ATT Server accepted a job without a valid job ID");
        if(detached){if("json".equals(f)){ObjectNode result=RemoteHttpClient.JSON.createObjectNode();result.put("jobId",id);result.put("status",accepted.path("status").asText("QUEUED"));System.out.println(result.toString());}else System.out.println(id);return 0;}
        return followAccepted(c,id,f);
    }
    static int followAccepted(RemoteHttpClient c,String id,String format)throws RemoteException {
        try{return follow(c,id,format);}
        catch(Exception failure){String detail=safe(failure);throw new RemoteException("Job "+id+" was accepted, but following it failed: "+detail+". Recover with `att remote watch "+id+"` or `att remote result "+id+"`.",failure);}
    }
    static int follow(RemoteHttpClient c,String id,String format)throws Exception {
        JsonNode value=awaitResult(c,id,format);return renderResult(value,format);
    }
    private static JsonNode awaitResult(RemoteHttpClient c,String id,String format)throws Exception {
        return awaitResult(c,id,format,false,false);
    }
    private static JsonNode awaitResult(RemoteHttpClient c,String id,String format,boolean quiet,boolean verbose)throws Exception {
        JsonNode current=c.get("/jobs/"+id);ApiStatus status=parseStatus(current.path("status").asText(null));
            String cursor=null;int retries=0;
            while(true) {
                HttpURLConnection connection=null;
                try {
                    connection=c.openEvents("/jobs/"+id+"/events",cursor);
                    SseReader reader=new SseReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8));SseEvent event;
                    while((event=reader.next())!=null) {
                        if(event.id!=null){long sequence;try{sequence=Long.parseLong(event.id);}catch(NumberFormatException e){throw new RemoteException("ATT Server returned an invalid SSE event ID");}if(cursor!=null&&sequence<=Long.parseLong(cursor))continue;cursor=event.id;}
                        JsonNode data;try{data=RemoteHttpClient.JSON.readTree(event.data);}catch(Exception e){throw new RemoteException("ATT Server returned malformed SSE event data");}
                        if(data==null||!data.isObject())throw new RemoteException("ATT Server returned malformed SSE event data");
                        retries=0;
                        if((!quiet&&verbose)||"diagnostic".equals(event.event))RemoteRenderer.event(format,id,event.event,data);
                        if(("status".equals(event.event)||"result".equals(event.event))&&data.has("status")){ApiStatus candidate=parseStatus(data.path("status").asText(null));if(candidate.isTerminal())status=candidate;}
                    }
                    if(!status.isTerminal()){JsonNode confirmed=c.get("/jobs/"+id);ApiStatus canonical=parseStatus(confirmed.path("status").asText(null));if(canonical.isTerminal())status=canonical;else throw new java.io.IOException("SSE closed before terminal status");}
                    if(status.isTerminal())break;
                } catch(RemoteException e){if(!e.isRetryableTransport()||++retries>5)throw e;pause(retries);}catch(Exception e){if(status.isTerminal())break;if(++retries>5)throw new RemoteException("ATT Server event stream disconnected repeatedly before a terminal result",e);pause(retries);}
                finally {if(connection!=null)connection.disconnect();}
            }
        return c.get("/jobs/"+id+"/result");
    }

    private static int runVNext(RemoteHttpClient c,JsonNode plan,String format)throws Exception {
        String command=plan.path("command").asText("");
        if("validate".equals(command)) {
            JsonNode report=c.post("/validation",plan);
            if(!"att-validation/v1".equals(report.path("schemaVersion").asText()))throw new RemoteException("ATT Server returned an unsupported validation report");
            int exit=report.path("exitCode").asInt(-1);if(exit<0||exit>4)throw new RemoteException("ATT Server returned an invalid validation exit code");
            if("json".equals(format))System.out.println(report.toString());else renderValidation(report,0);
            return exit;
        }
        if(!"run".equals(command)&&!"load".equals(command))throw new IllegalArgumentException("The Remote vNext command must be run, load, or validate");
        String packageId=plan.path("packageId").asText("");
        if(packageId.trim().isEmpty()||packageId.contains("/"))throw new IllegalArgumentException("Remote RUN/LOAD requires a logical -p/--package ID");
        ObjectNode body=RemoteHttpClient.JSON.createObjectNode();body.put("packageId",packageId);
        copyText(plan,body,"environment");copyText(plan,body,"runId");
        copyArray(plan,body,"suites");copyArray(plan,body,"caseIds");copyArray(plan,body,"tags");copyArray(plan,body,"excludeTags");copyArray(plan,body,"overrides");
        copyText(plan,body,"suiteDirectory");
        if(plan.path("all").asBoolean(false))body.put("all",true);
        if(plan.path("rerunFailed").asBoolean(false))body.put("rerunFailed",true);
        if(plan.path("dryRun").asBoolean(false))body.put("dryRun",true);
        if(plan.path("failFast").asBoolean(false))body.put("failFast",true);
        String targetKind=plan.path("targetKind").asText("");String target=plan.path("target").asText(null);
        if("run".equals(command)) {
            if(java.util.Arrays.asList("template","flow","tool").contains(targetKind)) {
                ObjectNode value=body.putObject("target");value.put("type",targetKind);value.put("id",target);
                copyLogicalPath(plan,body,"input","debugInput");
            } else if("case".equals(targetKind))body.withArray("caseIds").add(target);
            else if("suite".equals(targetKind))body.withArray("suites").add(logicalPath(target,"suite"));
        } else {
            if("scenario".equals(targetKind)||"load".equals(targetKind))body.put("scenario",logicalPath(target,"scenario"));
            else {ObjectNode value=body.putObject("target");value.put("type",targetKind);value.put("id",target);}
            JsonNode load=plan.path("load");if(load.isObject())body.set("load",load.deepCopy());
        }
        JsonNode accepted=c.post("/jobs/"+command,body);String id=accepted.path("jobId").asText("");
        if(!id.matches("J[A-F0-9]{16}"))throw new RemoteException("ATT Server accepted a job without a valid job ID");
        JsonNode result;
        try {result=awaitResult(c,id,format,plan.path("quiet").asBoolean(false),plan.path("verbose").asBoolean(false));} catch(Exception failure){throw new RemoteException("Job "+id+" was accepted, but following it failed: "+safe(failure)+". Recover with `att remote watch "+id+"` or `att remote result "+id+"`.",failure);}
        int exit=terminalExitCode(result);
        JsonNode job=result.path("job"),canonical=result.path("result");
        if("json".equals(format)) {
            ObjectNode output=RemoteHttpClient.JSON.createObjectNode();output.put("schemaVersion","att-operation/v1");output.put("backend","SERVER");
            output.put("command",command);output.put("targetKind",targetKind);if(target!=null)output.put("target",target);
            output.put("packageId",packageId);copyText(plan,output,"environment");output.put("jobId",id);
            if(canonical.has("executionId"))output.set("executionId",canonical.get("executionId"));
            output.put("status",job.path("status").asText("UNKNOWN"));output.put("exitCode",exit);
            if(canonical.has("summary"))output.set("summary",canonical.get("summary"));
            if(canonical.has("paths"))output.set("paths",canonical.get("paths"));
            if(result.has("diagnostic"))output.set("diagnostic",result.get("diagnostic"));
            System.out.println(output.toString());
        } else {
            System.out.println(command.toUpperCase(java.util.Locale.ROOT)+" "+job.path("status").asText("UNKNOWN")+" ("+exit+")");
            System.out.println("Server Job: "+id);
            if(canonical.has("executionId")&&!canonical.path("executionId").isNull())System.out.println("Run ID: "+canonical.path("executionId").asText());
            if(result.path("diagnostic").isObject())System.err.println(result.path("diagnostic").path("summary").asText("ATT Server diagnostic"));
        }
        return exit;
    }
    private static int terminalExitCode(JsonNode value)throws RemoteException {
        JsonNode job=value.path("job");if(!job.isObject())throw new RemoteException("ATT Server result response is missing its job");
        ApiStatus status=parseStatus(job.path("status").asText(null));if(!status.isTerminal())throw new RemoteException("ATT Server has not produced a terminal result");
        int code;switch(status){case PASS:code=0;break;case FAIL:code=1;break;case INVALID:code=2;break;case ERROR:case CANCELLED:code=3;default:throw new RemoteException("ATT Server returned an unsupported terminal result");}
        JsonNode canonical=value.path("result");if(canonical.isObject()&&canonical.has("exitCode")){int serverCode=canonical.path("exitCode").asInt(-1);if(serverCode<0||serverCode>3)throw new RemoteException("ATT Server returned an unsupported ATT exit code");code=serverCode;}return code;
    }
    private static void copyText(JsonNode source,ObjectNode target,String name){JsonNode v=source.get(name);if(v!=null&&v.isTextual()&&!v.asText().isEmpty())target.set(name,v.deepCopy());}
    private static void copyArray(JsonNode source,ObjectNode target,String name){JsonNode v=source.get(name);if(v!=null&&v.isArray()&&v.size()>0)target.set(name,v.deepCopy());}
    private static void copyLogicalPath(JsonNode source,ObjectNode target,String from,String to){JsonNode v=source.get(from);if(v!=null&&v.isTextual())target.put(to,logicalPath(v.asText(),to));}
    private static void renderValidation(JsonNode report,int depth) {
        if(depth==0){System.out.println("ATT Validation Report");System.out.println("Backend       : SERVER");System.out.println("Scope         : "+report.path("scope").asText());JsonNode context=report.path("context");if(context.isObject())context.fields().forEachRemaining(entry->System.out.println(entry.getKey()+" : "+entry.getValue().asText(entry.getValue().toString())));}
        JsonNode sections=report.path("sections");if(sections.isArray())for(JsonNode section:sections)renderValidationSection(section,depth);
        if(depth==0){JsonNode counts=report.path("counts");System.out.println("Summary       : "+counts.path("pass").asInt()+" pass, "+counts.path("fail").asInt()+" fail, "+counts.path("warnings").asInt()+" warning(s), "+counts.path("skip").asInt()+" skipped");System.out.println("Overall Status: "+report.path("overallStatus").asText());System.out.println("Exit Code     : "+report.path("exitCode").asInt());}
    }
    private static void renderValidationSection(JsonNode section,int depth) {
        StringBuilder line=new StringBuilder();for(int i=0;i<depth;i++)line.append("  ");line.append('[').append(section.path("status").asText()).append("] ").append(section.path("name").asText());if(section.path("source").isTextual())line.append("  ").append(section.path("source").asText());System.out.println(line.toString());
        JsonNode children=section.path("sections");if(children.isArray())for(JsonNode child:children)renderValidationSection(child,depth+1);
        JsonNode diagnostics=section.path("diagnostics");if(diagnostics.isArray())for(JsonNode d:diagnostics){StringBuilder detail=new StringBuilder();for(int i=0;i<=depth;i++)detail.append("  ");detail.append(d.path("severity").asText()).append(' ').append(d.path("code").asText());if(d.path("source").isTextual())detail.append(" | ").append(d.path("source").asText());if(d.path("field").isTextual())detail.append(" | field: ").append(d.path("field").asText());if(d.path("message").isTextual())detail.append(" | ").append(d.path("message").asText());System.out.println(detail.toString());}
    }
    private static int renderResult(JsonNode value,String format)throws Exception {
        JsonNode job=value.path("job");if(!job.isObject())throw new RemoteException("ATT Server result response is missing its job");
        ApiStatus status=parseStatus(job.path("status").asText(null));if(!status.isTerminal())throw new RemoteException("ATT Server has not produced a terminal result");
        int code;switch(status){case PASS:code=0;break;case FAIL:code=1;break;case INVALID:code=2;break;case ERROR:case CANCELLED:code=3;break;default:throw new RemoteException("ATT Server returned an unsupported terminal result");}
        JsonNode canonical=value.path("result");if(canonical.isObject()&&canonical.has("exitCode")){int serverCode=canonical.path("exitCode").asInt(-1);if(serverCode<0||serverCode>3)throw new RemoteException("ATT Server returned an unsupported ATT exit code");code=serverCode;}
        RemoteRenderer.result(format,value,code);return code;
    }
    private static int artifacts(RemoteHttpClient c,List<String>a,String f)throws Exception {
        String id=jobId(a,1);String directory=null;for(int i=2;i<a.size();i++){if("--download".equals(a.get(i))){if(++i>=a.size())throw new IllegalArgumentException("--download requires a local directory");directory=a.get(i);}else throw new IllegalArgumentException("Unknown artifacts option: "+a.get(i));}
        JsonNode archive=c.get("/jobs/"+id+"/artifacts");
        com.fasterxml.jackson.databind.node.ArrayNode items=att.remote.http.RemoteHttpClient.JSON.createArrayNode();
        JsonNode page=archive;
        int pages=0;
        do {
            if(page.path("items").isArray())for(JsonNode item:page.path("items"))items.add(item);
            JsonNode next=page.get("nextOffset");
            if(next==null||next.isNull())break;
            if(!next.canConvertToInt()||next.asInt()<0||++pages>100)throw new RemoteException("Invalid archive pagination");
            page=c.get("/jobs/"+id+"/artifacts?offset="+next.asInt()+"&limit=100");
        } while(true);
        if("json".equals(f))System.out.println(items.toString());
        else {System.out.println("Archive: "+archive.path("archiveStatus").asText("PENDING"));System.out.println("TYPE\tNAME\tSIZE");for(JsonNode item:items){String name=item.path("path").asText();System.out.println((item.path("report").asBoolean()?"report":"artifact")+"\t"+name+"\t"+item.path("size").asLong()+" bytes");}}
        if(directory!=null){
            if(!"READY".equals(archive.path("archiveStatus").asText()))throw new RemoteException("Artifact archive is not ready; retry after publication completes");
            Path saved=ArtifactDownloader.download(c,id,"artifacts.zip",Paths.get(directory));if(!"json".equals(f))System.out.println("Downloaded: "+saved);
        }
        return 0;
    }
    private static int artifact(RemoteHttpClient c,List<String>a)throws Exception {
        if(a.size()!=5||!"--download".equals(a.get(3)))throw new IllegalArgumentException("Usage: att remote artifact <jobId> <logicalName> --download <directory>");
        String id=jobId(a,1),name=a.get(2);
        if(!"artifacts.zip".equals(name))throw new RemoteException("Individual artifact downloads are unavailable; use att remote artifacts <jobId> --download <directory>");
        Path saved=ArtifactDownloader.download(c,id,name,Paths.get(a.get(4)));System.out.println("Downloaded: "+saved);return 0;
    }
    private static Credentials password(ServerProfile profile,Map<String,String> environment,CredentialPrompt console)throws RemoteException {
        String username=profile.username;
        String password=profile.passwordEnv==null?null:environment.get(profile.passwordEnv);
        if(username==null&&console!=null)username=console.readLine("Username: ");
        if(password==null){if(console==null)throw new RemoteException("No password secret is configured and no secure terminal is available; use a named profile with passwordEnv or run ATT Remote in a secure terminal");char[] secret=console.readPassword("Password: ");if(secret==null)throw new RemoteException("Password was not provided");password=new String(secret);java.util.Arrays.fill(secret,'\0');}
        if(username==null||username.trim().isEmpty())throw new RemoteException("Basic authentication username is missing");
        if(username.indexOf(':')>=0||username.indexOf('\n')>=0||username.indexOf('\r')>=0||password.isEmpty())throw new RemoteException("Basic authentication credentials are invalid");
        // Http Basic's user:password tuple is passed only in memory to the transport.
        return new Credentials(username,password);
    }
    private static CredentialPrompt systemConsolePrompt() {
        Console console=System.console();if(console==null)return null;
        return new CredentialPrompt() {
            @Override public String readLine(String prompt){return console.readLine(prompt);}
            @Override public char[] readPassword(String prompt){return console.readPassword(prompt);}
        };
    }
    private static boolean requiresCredentialPreflight(String operation) {
        return "run".equals(operation)||"debug".equals(operation)||"load".equals(operation)||"validate".equals(operation)||"cancel".equals(operation)||"vnext".equals(operation);
    }
    private static final class Credentials { final String username,password; Credentials(String username,String password){this.username=username;this.password=password;} }
    interface CredentialPrompt { String readLine(String prompt); char[] readPassword(String prompt); }
    interface RemoteClientFactory { RemoteHttpClient create(ServerProfile profile,String password,String username,RemoteHttpClient.CredentialProvider provider)throws RemoteException; }
    private static String optionValue(List<String>a,int index,String key){if(index+1>=a.size()||a.get(index+1).startsWith("--"))throw new IllegalArgumentException(key+" requires a value");return a.get(index+1);}
    private static String logicalPath(String value,String label){Path path=Paths.get(value);if(path.isAbsolute()||path.normalize().startsWith("..")||value.indexOf('\0')>=0||value.indexOf('\\')>=0)throw new IllegalArgumentException(label+" must be a package-relative logical path");return path.normalize().toString();}
    private static String age(String value){try{long seconds=Math.max(0,java.time.Duration.between(java.time.Instant.parse(value),java.time.Instant.now()).getSeconds());if(seconds<60)return seconds+"s";if(seconds<3600)return (seconds/60)+"m";if(seconds<86400)return (seconds/3600)+"h";return (seconds/86400)+"d";}catch(Exception ignored){return "unknown";}}
    private static ApiStatus parseStatus(String value)throws RemoteException{try{return ApiStatus.parse(value);}catch(IllegalArgumentException e){throw new RemoteException(e.getMessage());}}
    private static void pause(int retries)throws RemoteException{try{Thread.sleep(Math.min(250L<<retries,4000L));}catch(InterruptedException x){Thread.currentThread().interrupt();throw new RemoteException("Interrupted while reconnecting to ATT Server");}}
    private static String jobId(List<String>a,int index){if(a.size()!=index+1||!a.get(index).matches("J[A-F0-9]{16}"))throw new IllegalArgumentException("A valid job ID is required");return a.get(index);}
    private static String segment(String value){try{return java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20");}catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}}
    private static int fail(String message,int code,String format){String safe=message==null?"Remote command failed":message.replaceAll("(?i)(password|authorization)\\s*[:=]\\s*[^ ,]+","$1=[REDACTED_SECRET]");if("json".equals(format)){ObjectNode out=RemoteHttpClient.JSON.createObjectNode();out.put("error",safe);out.put("exitCode",code);System.err.println(out.toString());}else System.err.println("ATT Remote: "+safe);return code;}
    private static String safe(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    private static void help(){System.out.println("Usage: att remote [--server <profile|url>] [--format human|json] [--no-auth] <command>\nCommands: ping, version, packages, package <id>, run|debug|load|validate <packageId> [--principal <name>], jobs [--principal <search>], job|watch|result|cancel <jobId>, artifacts <jobId> [--download <directory>], artifact <jobId> artifacts.zip --download <directory>\n--server accepts a named profile or an HTTP(S) base URL. ATT_SERVER accepts either value and overrides the YAML default. Basic authentication, including credentials entered for a direct URL, requires HTTPS.");}
}
