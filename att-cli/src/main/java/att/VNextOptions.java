package att;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Parsed public vNext command grammar. Legacy commands continue through CliOptions. */
public final class VNextOptions {
    private static final Set<String> COMMANDS = set("run", "load", "validate");
    private static final Set<String> TARGETS = set("case", "cases", "suite", "template", "flow", "tool", "scenario", "package", "config", "load");
    private final String command, targetKind, target, packageId, environment, server, format, runId;
    private final Path configPath, outputDirectory, input, suiteDirectory;
    private final boolean local, all, rerunFailed, dryRun, failFast, quiet, verbose, help, version;
    private final String users, arrivalRate, warmup, rampUp, duration, rampDown, thinkTime, maxConcurrent, overloadPolicy;
    private final List<Path> suites;
    private final Set<String> caseIds, tags, excludeTags;
    private final List<String> overrides;

    private VNextOptions(Builder b) {
        command=b.command; targetKind=b.targetKind; target=b.target; packageId=b.packageId;
        environment=b.environment; server=b.server; format=b.format; runId=b.runId;
        configPath=b.configPath; outputDirectory=b.outputDirectory; input=b.input; suiteDirectory=b.suiteDirectory;
        local=b.local; all=b.all; rerunFailed=b.rerunFailed; dryRun=b.dryRun; failFast=b.failFast;
        quiet=b.quiet; verbose=b.verbose; help=b.help; version=b.version;
        users=b.users; arrivalRate=b.arrivalRate; warmup=b.warmup; rampUp=b.rampUp; duration=b.duration;
        rampDown=b.rampDown; thinkTime=b.thinkTime; maxConcurrent=b.maxConcurrent; overloadPolicy=b.overloadPolicy;
        suites=Collections.unmodifiableList(new ArrayList<Path>(b.suites));
        caseIds=Collections.unmodifiableSet(new LinkedHashSet<String>(b.caseIds));
        tags=Collections.unmodifiableSet(new LinkedHashSet<String>(b.tags));
        excludeTags=Collections.unmodifiableSet(new LinkedHashSet<String>(b.excludeTags));
        overrides=Collections.unmodifiableList(new ArrayList<String>(b.overrides));
    }

    public static boolean isVNextRequest(String[] args) {
        if (args == null || args.length == 0) return false;
        for (String arg : args) if ("--local".equals(arg) || "--server".equals(arg)) return true;
        int command = -1;
        for (int i=0;i<args.length;i++) {
            String arg=args[i];
            if (takesValue(arg) && i+1<args.length) { i++; continue; }
            if (COMMANDS.contains(arg)) { command=i; break; }
        }
        if (command < 0) return false;
        for (int i=command+1;i<args.length;i++) {
            String arg=args[i];
            if (takesValue(arg) && i+1<args.length) { i++; continue; }
            if ("--all".equals(arg)) return "validate".equals(args[command]) || "cases".equals(nextPositional(args, command));
            if (!arg.startsWith("-")) return TARGETS.contains(arg);
        }
        return false;
    }

    /** Returns a valid machine-output request even when another argument later fails parsing. */
    public static String requestedFormat(String[] args) {
        String format = "human";
        if (args == null) return format;
        for (int i=0;i<args.length;i++) {
            if (!"--format".equals(args[i]) || i+1>=args.length) continue;
            String candidate=args[++i];
            if ("human".equals(candidate)||"json".equals(candidate)) format=candidate;
        }
        return format;
    }

    public static String requestedBackend(String[] args) {
        if (args != null) for (String arg : args) {
            if ("--server".equals(arg)) return "SERVER";
            if ("--local".equals(arg)) return "LOCAL";
        }
        return "LOCAL";
    }

    public static String requestedCommand(String[] args) {
        if (args == null) return null;
        for (int i=0;i<args.length;i++) {
            if (takesValue(args[i]) && i+1<args.length) { i++; continue; }
            if (COMMANDS.contains(args[i])) return args[i];
        }
        return null;
    }

    public static String requestedValidationScope(String[] args) {
        if (args == null) return "VALIDATION";
        int command=-1;
        for (int i=0;i<args.length;i++) {
            if (takesValue(args[i]) && i+1<args.length) { i++; continue; }
            if ("validate".equals(args[i])) { command=i; break; }
        }
        if (command<0) return "VALIDATION";
        for (int i=command+1;i<args.length;i++) {
            String arg=args[i];
            if ("--all".equals(arg)) return "ALL";
            if (takesValue(arg) && i+1<args.length) { i++; continue; }
            if (!arg.startsWith("-")) {
                if ("config".equals(arg)) return "CONFIG";
                if ("package".equals(arg)) return "PACKAGE";
                return "SELECTED";
            }
        }
        return "VALIDATION";
    }

    public static VNextOptions parse(String[] args) {
        if (args == null) args=new String[0];
        Builder b=new Builder();
        List<String> positional=new ArrayList<String>();
        for (int i=0;i<args.length;i++) {
            String arg=args[i];
            if ("--help".equals(arg)||"help".equals(arg)) { b.help=true; continue; }
            if ("--version".equals(arg)||"version".equals(arg)) { b.version=true; continue; }
            if ("--local".equals(arg)) { if (b.local) throw invalid("--local may be specified only once"); b.local=true; continue; }
            if ("--server".equals(arg)) { if (b.server!=null) throw invalid("--server may be specified only once"); b.server=value(args,++i,arg); continue; }
            if ("-p".equals(arg)||"--package".equals(arg)) { if (b.packageId!=null) throw invalid("-p/--package may be specified only once"); b.packageId=value(args,++i,arg); continue; }
            if ("-e".equals(arg)||"--env".equals(arg)) { if (b.environment!=null) throw invalid("-e/--env may be specified only once"); b.environment=value(args,++i,arg); continue; }
            if ("--config".equals(arg)) { if (b.configPath!=null) throw invalid("--config may be specified only once"); b.configPath=Paths.get(value(args,++i,arg)); continue; }
            if ("--format".equals(arg)) { b.format=value(args,++i,arg); continue; }
            if ("--output-dir".equals(arg)) { b.outputDirectory=Paths.get(value(args,++i,arg)); continue; }
            if ("--run-id".equals(arg)) { b.runId=value(args,++i,arg); continue; }
            if ("--input".equals(arg)) { b.input=Paths.get(value(args,++i,arg)); continue; }
            if ("--set".equals(arg)) { b.overrides.add(value(args,++i,arg)); continue; }
            if ("--suite".equals(arg)) { b.suites.add(Paths.get(value(args,++i,arg))); continue; }
            if ("--suite-dir".equals(arg)) { b.suiteDirectory=Paths.get(value(args,++i,arg)); continue; }
            if ("--case".equals(arg)||"--case-id".equals(arg)) { b.caseIds.add(value(args,++i,arg)); continue; }
            if ("--tag".equals(arg)) { b.tags.add(value(args,++i,arg)); continue; }
            if ("--exclude-tag".equals(arg)) { b.excludeTags.add(value(args,++i,arg)); continue; }
            if ("--users".equals(arg)) { b.users=value(args,++i,arg); continue; }
            if ("--arrival-rate".equals(arg)) { b.arrivalRate=value(args,++i,arg); continue; }
            if ("--warmup".equals(arg)) { b.warmup=value(args,++i,arg); continue; }
            if ("--ramp-up".equals(arg)) { b.rampUp=value(args,++i,arg); continue; }
            if ("--duration".equals(arg)) { b.duration=value(args,++i,arg); continue; }
            if ("--ramp-down".equals(arg)) { b.rampDown=value(args,++i,arg); continue; }
            if ("--think-time".equals(arg)) { b.thinkTime=value(args,++i,arg); continue; }
            if ("--max-concurrent".equals(arg)) { b.maxConcurrent=value(args,++i,arg); continue; }
            if ("--overload-policy".equals(arg)) { b.overloadPolicy=value(args,++i,arg); continue; }
            if ("--all".equals(arg)) { b.all=true; continue; }
            if ("--rerun-failed".equals(arg)) { b.rerunFailed=true; continue; }
            if ("--dry-run".equals(arg)) { b.dryRun=true; continue; }
            if ("--fail-fast".equals(arg)) { b.failFast=true; continue; }
            if ("--quiet".equals(arg)) { b.quiet=true; continue; }
            if ("--verbose".equals(arg)) { b.verbose=true; continue; }
            if (arg.startsWith("-")) throw invalid("Unsupported vNext option: " + arg);
            positional.add(arg);
        }
        if (b.help||b.version) return new VNextOptions(b);
        if (positional.isEmpty()) throw invalid("Expected one of run, load, or validate");
        if (!COMMANDS.contains(positional.get(0))) throw invalid("The only vNext commands are run, load, and validate");
        b.command=positional.get(0);
        if (positional.size()>1) b.targetKind=positional.get(1).toLowerCase(java.util.Locale.ROOT);
        if (positional.size()>2) b.target=positional.get(2);
        if (positional.size()>3) throw invalid("Only one target ID or path may follow the target kind");
        validate(b);
        return new VNextOptions(b);
    }

    private static void validate(Builder b) {
        if (b.local&&b.server!=null) throw invalid("--local and --server are mutually exclusive");
        if (b.server!=null&&b.configPath!=null) throw invalid("--config is available only with --local");
        if (b.server!=null&&b.outputDirectory!=null) throw invalid("--output-dir is available only with --local");
        if (!"human".equals(b.format)&&!"json".equals(b.format)) throw invalid("--format must be human or json");
        if (b.quiet&&b.verbose) throw invalid("--quiet and --verbose cannot be used together");
        if (b.packageId!=null&&!b.packageId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw invalid("Package ID is invalid");
        if (b.environment!=null&&!b.environment.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) throw invalid("Environment profile is invalid");
        if (b.command==null) return;
        boolean validate="validate".equals(b.command), run="run".equals(b.command), load="load".equals(b.command);
        if (b.all) {
            if (!validate && !(run&&"cases".equals(b.targetKind))) throw invalid("--all is available only for validate or run cases");
            if (validate&&(b.targetKind!=null||b.packageId!=null||b.environment!=null||!b.suites.isEmpty()||b.suiteDirectory!=null||!b.caseIds.isEmpty()||!b.tags.isEmpty()||!b.excludeTags.isEmpty()))
                throw invalid("validate --all cannot be combined with a target, -p/--package, -e/--env, or selectors");
        }
        if (b.rerunFailed||b.dryRun||b.failFast||!"reject".equals(b.concurrencyMode)||b.outputDirectory!=null||b.runId!=null||!b.suites.isEmpty()||b.suiteDirectory!=null||!b.caseIds.isEmpty()||!b.tags.isEmpty()||!b.excludeTags.isEmpty()) {
            if (!run) throw invalid("RUN selection and execution options are valid only for run");
        }
        if (b.input!=null && !(run&&isResource(b.targetKind)))
            throw invalid("--input is supported only for Template, Flow, or Tool RUN");
        if (!b.overrides.isEmpty() && !(run&&isResource(b.targetKind)||load&&isResource(b.targetKind)))
            throw invalid("--set requires a Template, Flow, or Tool target");
        if (load&&b.dryRun) throw invalid("load --dry-run is not available in the minimal CLI");
        if (b.users!=null||b.arrivalRate!=null||b.warmup!=null||b.rampUp!=null||b.duration!=null||b.rampDown!=null||b.thinkTime!=null||b.maxConcurrent!=null||b.overloadPolicy!=null) {
            if (!load) throw invalid("Load pacing options are valid only for load");
            if (b.users!=null&&b.arrivalRate!=null) throw invalid("--users and --arrival-rate are mutually exclusive");
        }
        if (run) {
            if (!set("case","cases","suite","template","flow","tool").contains(b.targetKind)) throw invalid("run target must be case, cases, suite, template, flow, or tool");
            if (b.dryRun&&isResource(b.targetKind))
                throw invalid("run --dry-run is not available for Template, Flow, or Tool targets");
            if ("cases".equals(b.targetKind)) {
                if (b.target!=null) throw invalid("run cases does not take a target ID");
                if (b.all&&(!b.suites.isEmpty()||b.suiteDirectory!=null||!b.caseIds.isEmpty()||!b.tags.isEmpty()||!b.excludeTags.isEmpty()))
                    throw invalid("run cases --all cannot be combined with Case, Suite, or tag selectors");
                if (!b.all&&b.suites.isEmpty()&&b.suiteDirectory==null&&b.caseIds.isEmpty()&&b.tags.isEmpty()) throw invalid("run cases requires --all, a suite, a Case, or a tag");
            } else if (b.target==null||b.target.trim().isEmpty()) throw invalid("run "+b.targetKind+" requires a target ID or path");
            if (isResource(b.targetKind)&&(!b.suites.isEmpty()||b.suiteDirectory!=null||!b.caseIds.isEmpty()||!b.tags.isEmpty()||!b.excludeTags.isEmpty()))
                throw invalid("Resource targets cannot be combined with Case, Suite, or tag selectors");
            if ("case".equals(b.targetKind)&&(!b.suites.isEmpty()||b.suiteDirectory!=null)) throw invalid("run case cannot be combined with suite selectors");
            if ("suite".equals(b.targetKind)&&(!b.caseIds.isEmpty()||!b.tags.isEmpty()||!b.excludeTags.isEmpty())) throw invalid("run suite cannot be combined with Case/tag selectors");
        } else if (load) {
            if (!set("scenario","template","flow","tool").contains(b.targetKind)) throw invalid("load target must be scenario, template, flow, or tool");
            if (b.target==null||b.target.trim().isEmpty()) throw invalid("load "+b.targetKind+" requires a target ID or path");
        } else if (validate) {
            if (!b.all&& !set("config","package","case","suite","template","flow","tool","load").contains(b.targetKind)) throw invalid("validate target must be config, package, case, suite, template, flow, tool, load, or --all");
            if (set("case","suite","template","flow","tool","load").contains(b.targetKind)&&(b.target==null||b.target.trim().isEmpty())) throw invalid("validate "+b.targetKind+" requires a target ID or path");
            if (set("config","package").contains(b.targetKind)&&b.target!=null) throw invalid("validate "+b.targetKind+" does not take a target ID");
        }
        if (b.server!=null && (run||load||validate&&!("config".equals(b.targetKind)||b.all)) && b.packageId==null)
            throw invalid("Remote RUN/LOAD/Package validation requires -p/--package");
        if (b.server!=null&&"validate".equals(b.command)&&b.all&&b.packageId!=null) throw invalid("Remote validate --all covers all registered Packages; omit -p/--package");
    }

    private static boolean isResource(String target) { return "template".equals(target)||"flow".equals(target)||"tool".equals(target); }
    private static String nextPositional(String[] args,int command) {
        for(int i=command+1;i<args.length;i++) { String a=args[i]; if(takesValue(a)&&i+1<args.length){i++;continue;} if(!a.startsWith("-"))return a; }
        return null;
    }
    private static boolean takesValue(String arg) {
        return set("--server","-p","--package","-e","--env","--config","--format","--output-dir","--run-id","--input","--set","--suite","--suite-dir","--case","--case-id","--tag","--exclude-tag","--users","--arrival-rate","--warmup","--ramp-up","--duration","--ramp-down","--think-time","--max-concurrent","--overload-policy").contains(arg);
    }
    private static String value(String[] args,int index,String option) { if(index>=args.length||args[index].startsWith("--"))throw invalid(option+" requires a value"); return args[index]; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static Set<String> set(String... values) { Set<String> result=new LinkedHashSet<String>(); Collections.addAll(result,values); return result; }

    public String command(){return command;} public String targetKind(){return targetKind;} public String target(){return target;}
    public String packageId(){return packageId;} public String environment(){return environment;} public String server(){return server;}
    public String format(){return format;} public String runId(){return runId;} public Path configPath(){return configPath;}
    public Path outputDirectory(){return outputDirectory;} public Path input(){return input;} public boolean local(){return local;}
    public boolean all(){return all;} public boolean rerunFailed(){return rerunFailed;} public boolean dryRun(){return dryRun;}
    public boolean failFast(){return failFast;} public boolean quiet(){return quiet;} public boolean verbose(){return verbose;}
    public boolean help(){return help;} public boolean version(){return version;} public String users(){return users;}
    public String arrivalRate(){return arrivalRate;} public String warmup(){return warmup;} public String rampUp(){return rampUp;}
    public String duration(){return duration;} public String rampDown(){return rampDown;} public String thinkTime(){return thinkTime;}
    public String maxConcurrent(){return maxConcurrent;} public String overloadPolicy(){return overloadPolicy;}
    public List<Path> suites(){return suites;} public Path suiteDirectory(){return suiteDirectory;} public Set<String> caseIds(){return caseIds;}
    public Set<String> tags(){return tags;} public Set<String> excludeTags(){return excludeTags;} public List<String> overrides(){return overrides;}

    private static final class Builder {
        String command,targetKind,target,packageId,environment,server,format="human",runId,users,arrivalRate,warmup,rampUp,duration,rampDown,thinkTime,maxConcurrent,overloadPolicy;
        Path configPath,outputDirectory,input,suiteDirectory;
        boolean local,all,rerunFailed,dryRun,failFast,quiet,verbose,help,version;
        String concurrencyMode="reject";
        List<Path> suites=new ArrayList<Path>(); Set<String> caseIds=new LinkedHashSet<String>(),tags=new LinkedHashSet<String>(),excludeTags=new LinkedHashSet<String>();
        List<String> overrides=new ArrayList<String>();
    }
}
