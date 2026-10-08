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
        String format="human";
        try {
            RemoteOptions options=RemoteOptions.parse(args);format=options.format;
            if(options.command.isEmpty()||"help".equals(options.command.get(0))){help();return 0;}
            ServerProfile profile=ServerProfileLoader.resolve(options.server,System.getenv("ATT_SERVER"),ServerProfileLoader.profilePath(),options.noAuth);
            Credentials credentials=options.noAuth?new Credentials(null,null):password(profile);
            RemoteHttpClient client=new RemoteHttpClient(profile,credentials.password,credentials.username);
            JsonNode version=client.get("/version");
            if(!version.path("apiVersion").isTextual()||!ServerApi.VERSION.equals(version.path("apiVersion").asText()))throw new RemoteException("ATT Server API is missing or unsupported; client supports API v"+ServerApi.VERSION);
            List<String> command=options.command;String op=command.get(0);
            if("ping".equals(op)){JsonNode health=client.get("/health");if("json".equals(format))System.out.println(health.toString());else System.out.println("ATT Server "+profile.url+": "+health.path("status").asText("UNKNOWN"));return 0;}
            if("version".equals(op)){if("json".equals(format))System.out.println(version.toString());else System.out.println("ATT Server "+version.path("version").asText("unknown")+" (API v"+version.path("apiVersion").asText()+")");return 0;}
            if("packages".equals(op))return packages(client,command,format);
            if("package".equals(op))return packageView(client,command,format);
            if("jobs".equals(op))return jobs(client,format);
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
    private static int jobs(RemoteHttpClient c,String f)throws Exception {
        // The Server itself applies the v1 visibility policy; the client does not filter jobs.
        JsonNode items=c.get("/jobs").path("items");if("json".equals(f)){System.out.println(items.toString());return 0;}
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
            if("--suite".equals(key))suites.add(logicalPath(value,key));
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
        return follow(c,id,f);
    }
    static int follow(RemoteHttpClient c,String id,String format)throws Exception {
        JsonNode current=c.get("/jobs/"+id);ApiStatus status=parseStatus(current.path("status").asText(null));
        if(!status.isTerminal()){
            String cursor=null;int retries=0;
            while(!status.isTerminal()) {
                HttpURLConnection connection=null;
                try {
                    connection=c.openEvents("/jobs/"+id+"/events",cursor);
                    SseReader reader=new SseReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8));SseEvent event;
                    while((event=reader.next())!=null) {
                        if(event.id!=null){long sequence;try{sequence=Long.parseLong(event.id);}catch(NumberFormatException e){throw new RemoteException("ATT Server returned an invalid SSE event ID");}if(cursor!=null&&sequence<=Long.parseLong(cursor))continue;cursor=event.id;}
                        JsonNode data;try{data=RemoteHttpClient.JSON.readTree(event.data);}catch(Exception e){throw new RemoteException("ATT Server returned malformed SSE event data");}
                        if(data==null||!data.isObject())throw new RemoteException("ATT Server returned malformed SSE event data");
                        RemoteRenderer.event(format,id,event.event,data);
                        if(("status".equals(event.event)||"result".equals(event.event))&&data.has("status")){ApiStatus candidate=parseStatus(data.path("status").asText(null));if(candidate.isTerminal())status=candidate;}
                    }
                    if(!status.isTerminal()){JsonNode confirmed=c.get("/jobs/"+id);ApiStatus canonical=parseStatus(confirmed.path("status").asText(null));if(canonical.isTerminal())status=canonical;else throw new java.io.IOException("SSE closed before terminal status");}
                } catch(RemoteException e){if(!transientHttp(e)||++retries>5)throw e;pause(retries);}catch(Exception e){if(status.isTerminal())break;if(++retries>5)throw new RemoteException("ATT Server event stream disconnected repeatedly before a terminal result");pause(retries);}
                finally {if(connection!=null)connection.disconnect();}
            }
        }
        JsonNode value=c.get("/jobs/"+id+"/result");return renderResult(value,format);
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
        JsonNode items=c.get("/jobs/"+id+"/artifacts").path("items");if("json".equals(f))System.out.println(items.toString());else {System.out.println("TYPE\tNAME\tSIZE");for(JsonNode item:items){String name=item.path("path").asText();String type=name.contains("/")?name.substring(0,name.indexOf('/')):"artifact";System.out.println(type+"\t"+name+"\t"+item.path("size").asLong()+" bytes");}}
        if(directory!=null)for(JsonNode item:items){String name=item.path("path").asText();Path saved=ArtifactDownloader.download(c,id,name,Paths.get(directory));if(!"json".equals(f))System.out.println("Downloaded: "+saved);}
        return 0;
    }
    private static int artifact(RemoteHttpClient c,List<String>a)throws Exception {
        if(a.size()!=5||!"--download".equals(a.get(3)))throw new IllegalArgumentException("Usage: att remote artifact <jobId> <logicalName> --download <directory>");
        String id=jobId(a,1),name=a.get(2);JsonNode items=c.get("/jobs/"+id+"/artifacts").path("items");boolean listed=false;
        for(JsonNode item:items)if(name.equals(item.path("path").asText())){listed=true;break;}
        if(!listed)throw new RemoteException("Artifact name is not present in the Server's logical artifact list");
        Path saved=ArtifactDownloader.download(c,id,name,Paths.get(a.get(4)));System.out.println("Downloaded: "+saved);return 0;
    }
    private static Credentials password(ServerProfile profile)throws RemoteException {
        if(!profile.basicAuth)return new Credentials(profile.username,null);
        String username=profile.username;
        String password=profile.passwordEnv==null?null:System.getenv(profile.passwordEnv);
        Console console=System.console();
        if(username==null&&console!=null)username=console.readLine("Username: ");
        if(password==null){if(console==null)throw new RemoteException("No password secret is configured and no secure terminal is available; set passwordEnv in the Server profile");char[] secret=console.readPassword("Password: ");if(secret==null)throw new RemoteException("Password was not provided");password=new String(secret);java.util.Arrays.fill(secret,'\0');}
        if(username==null||username.trim().isEmpty())throw new RemoteException("Basic authentication username is missing");
        if(username.indexOf(':')>=0||username.indexOf('\n')>=0||username.indexOf('\r')>=0||password.isEmpty())throw new RemoteException("Basic authentication credentials are invalid");
        // Http Basic's user:password tuple is passed only in memory to the transport.
        return new Credentials(username,password);
    }
    private static final class Credentials { final String username,password; Credentials(String username,String password){this.username=username;this.password=password;} }
    private static String optionValue(List<String>a,int index,String key){if(index+1>=a.size()||a.get(index+1).startsWith("--"))throw new IllegalArgumentException(key+" requires a value");return a.get(index+1);}
    private static String logicalPath(String value,String label){Path path=Paths.get(value);if(path.isAbsolute()||path.normalize().startsWith("..")||value.indexOf('\0')>=0||value.indexOf('\\')>=0)throw new IllegalArgumentException(label+" must be a package-relative logical path");return path.normalize().toString();}
    private static String age(String value){try{long seconds=Math.max(0,java.time.Duration.between(java.time.Instant.parse(value),java.time.Instant.now()).getSeconds());if(seconds<60)return seconds+"s";if(seconds<3600)return (seconds/60)+"m";if(seconds<86400)return (seconds/3600)+"h";return (seconds/86400)+"d";}catch(Exception ignored){return "unknown";}}
    private static ApiStatus parseStatus(String value)throws RemoteException{try{return ApiStatus.parse(value);}catch(IllegalArgumentException e){throw new RemoteException(e.getMessage());}}
    private static boolean transientHttp(RemoteException e){String m=e.getMessage();return m!=null&&m.matches("(?s).*HTTP 5[0-9][0-9].*");}
    private static void pause(int retries)throws RemoteException{try{Thread.sleep(Math.min(250L<<retries,4000L));}catch(InterruptedException x){Thread.currentThread().interrupt();throw new RemoteException("Interrupted while reconnecting to ATT Server");}}
    private static String jobId(List<String>a,int index){if(a.size()!=index+1||!a.get(index).matches("J[A-F0-9]{16}"))throw new IllegalArgumentException("A valid job ID is required");return a.get(index);}
    private static String segment(String value){try{return java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20");}catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}}
    private static int fail(String message,int code,String format){String safe=message==null?"Remote command failed":message.replaceAll("(?i)(password|authorization)\\s*[:=]\\s*[^ ,]+","$1=[REDACTED_SECRET]");if("json".equals(format)){ObjectNode out=RemoteHttpClient.JSON.createObjectNode();out.put("error",safe);out.put("exitCode",code);System.err.println(out.toString());}else System.err.println("ATT Remote: "+safe);return code;}
    private static String safe(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    private static void help(){System.out.println("Usage: att remote [--server <profile>] [--format human|json] [--no-auth] <command>\nCommands: ping, version, packages, package <id>, run|debug|load|validate <packageId>, jobs, job|watch|result|cancel <jobId>, artifacts <jobId>, artifact <jobId> <name> --download <directory>\nSet ATT_SERVER to override the configured default profile URL.");}
}
