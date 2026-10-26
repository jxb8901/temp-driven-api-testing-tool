package att;

import att.api.DebugRequest;
import att.api.LoadRequest;
import att.api.OperationResult;
import att.api.RunRequest;
import att.api.ValidateRequest;
import att.config.RuntimeConfigPath;
import att.resource.PackageResourceInspector;
import att.server.api.AttValidationReport;
import att.validation.Diagnostic;
import att.validation.RuntimeConfigurationValidator;
import att.validation.ValidationReportRenderer;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal run/load/validate command adapter. Legacy verbs remain on FrameworkRunner's compatibility path. */
public final class VNextCommand {
    private VNextCommand() { }

    public static int run(String[] args) {
        VNextOptions options=null;
        Path cwd=Paths.get("").toAbsolutePath().normalize();
        try {
            options=VNextOptions.parse(args);
            if(options.help()) { help(); return 0; }
            if(options.version()) { System.out.println(Version.DISPLAY); return 0; }
            if(options.server()!=null) return att.remote.RemoteCommand.runVNext(options.server(),options.format(),remotePlan(options));
            Path packageRoot=packageRoot(options,cwd);
            Path config=RuntimeConfigPath.resolve(options.configPath(),packageRoot);
            int exit;
            if("validate".equals(options.command())) exit=validate(options,packageRoot,config);
            else if("run".equals(options.command())) exit=runTarget(options,packageRoot,config);
            else exit=loadTarget(options,packageRoot,config);
            return exit;
        } catch (IllegalArgumentException invalid) {
            return fail(options,"ATT-CLI-INVALID",invalid.getMessage(),2,args);
        } catch (Exception failure) {
            return fail(options,"ATT-CLI-FAILED",safe(failure),3,args);
        }
    }

    private static Path packageRoot(VNextOptions options,Path cwd) throws Exception {
        String packageId=options.packageId();
        if(packageId==null) {
            String selected=System.getenv("ATT_PACKAGE_ROOT");
            if(selected==null||selected.trim().isEmpty()) return cwd;
            Path root=Paths.get(selected).toAbsolutePath().normalize();
            if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(root))
                throw new IllegalArgumentException("ATT_PACKAGE_ROOT must name a readable, non-symlink Package directory");
            return root.toRealPath();
        }
        String home=System.getProperty("att.home");
        if(home==null||home.trim().isEmpty())home=System.getenv("ATT_HOME");
        if(home==null||home.trim().isEmpty())throw new IllegalArgumentException("-p/--package requires ATT_HOME so the logical Package ID can be resolved safely");
        Path packages=Paths.get(home).toAbsolutePath().normalize().resolve("packages");
        Path root=packages.resolve(packageId).normalize();
        if(!root.getParent().equals(packages)||Files.isSymbolicLink(packages)||Files.isSymbolicLink(root)
                ||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(root))
            throw new IllegalArgumentException("Package ID is not a readable immediate Package directory under ATT_HOME/packages: "+packageId);
        Path canonicalPackages=packages.toRealPath(),canonicalRoot=root.toRealPath();
        if(!canonicalRoot.getParent().equals(canonicalPackages))throw new IllegalArgumentException("Package root escapes ATT_HOME/packages");
        return canonicalRoot;
    }

    private static Map<String,Object> remotePlan(VNextOptions options) {
        Map<String,Object> plan=new LinkedHashMap<String,Object>();
        plan.put("command",options.command());plan.put("targetKind",options.targetKind());plan.put("target",options.target());
        plan.put("packageId",options.packageId());plan.put("environment",options.environment());plan.put("all",Boolean.valueOf(options.all()));
        if("validate".equals(options.command()))return plan;
        plan.put("rerunFailed",Boolean.valueOf(options.rerunFailed()));plan.put("dryRun",Boolean.valueOf(options.dryRun()));
        plan.put("failFast",Boolean.valueOf(options.failFast()));plan.put("runId",options.runId());
        plan.put("quiet",Boolean.valueOf(options.quiet()));plan.put("verbose",Boolean.valueOf(options.verbose()));
        plan.put("suiteDirectory",options.suiteDirectory()==null?null:options.suiteDirectory().toString());
        List<String> suites=new ArrayList<String>();for(Path path:options.suites())suites.add(path.toString());plan.put("suites",suites);
        plan.put("caseIds",new ArrayList<String>(options.caseIds()));plan.put("tags",new ArrayList<String>(options.tags()));
        plan.put("excludeTags",new ArrayList<String>(options.excludeTags()));plan.put("input",options.input()==null?null:options.input().toString());
        plan.put("overrides",new ArrayList<String>(options.overrides()));
        Map<String,String> load=new LinkedHashMap<String,String>();
        if(options.users()!=null)load.put("users",options.users());if(options.arrivalRate()!=null)load.put("arrivalRate",options.arrivalRate());
        if(options.warmup()!=null)load.put("warmup",options.warmup());if(options.rampUp()!=null)load.put("rampUp",options.rampUp());
        if(options.duration()!=null)load.put("duration",options.duration());if(options.rampDown()!=null)load.put("rampDown",options.rampDown());
        if(options.thinkTime()!=null)load.put("thinkTime",options.thinkTime());if(options.maxConcurrent()!=null)load.put("maxConcurrent",options.maxConcurrent());
        if(options.overloadPolicy()!=null)load.put("overloadPolicy",options.overloadPolicy());plan.put("load",load);
        return plan;
    }

    private static int runTarget(VNextOptions o,Path root,Path config) throws Exception {
        OperationResult result;
        if(isResource(o.targetKind())) {
            result=new att.api.DefaultAttService().debug(new DebugRequest(root,config,o.environment(),o.outputDirectory(),o.runId(),
                    o.targetKind(),o.target(),o.input(),false,null,o.runId(),o.overrides()));
        } else {
            List<Path> suites=new ArrayList<Path>(o.suites());
            java.util.Set<String> cases=new java.util.LinkedHashSet<String>(o.caseIds());
            if("suite".equals(o.targetKind()))suites.add(Paths.get(o.target()));
            if("case".equals(o.targetKind()))cases.add(o.target());
            result=new att.api.DefaultAttService().run(new RunRequest(root,config,o.environment(),o.outputDirectory(),o.runId(),
                    suites,o.suiteDirectory(),cases,o.tags(),o.excludeTags(),o.all(),o.rerunFailed(),o.dryRun(),o.failFast()));
        }
        printOperation(o,result,root);
        return result.exitCode();
    }

    private static int loadTarget(VNextOptions o,Path root,Path config) throws Exception {
        Path scenario="scenario".equals(o.targetKind())||"load".equals(o.targetKind())?Paths.get(o.target()):null;
        String targetType=scenario==null?o.targetKind():null;
        String targetId=scenario==null?o.target():null;
        final boolean json="json".equals(o.format());
        att.load.LoadEventListener listener=new att.load.LoadEventListener() {
            @Override public void onEvent(att.load.LoadEvent event) {
                if(o.quiet()||!o.verbose())return;
                java.io.PrintStream stream=json?System.err:System.out;
                synchronized(stream){stream.println((json?"[LOAD] ":"")+att.validation.JsonSupport.write(event.toMap(root)));stream.flush();}
            }
            @Override public void onPreparationEvent(Map<String,Object> event) {
                if(o.quiet()||!o.verbose())return;
                java.io.PrintStream stream=json?System.err:System.out;
                synchronized(stream){stream.println((json?"[LOAD] ":"")+att.validation.JsonSupport.write(event));stream.flush();}
            }
        };
        OperationResult result=new att.api.DefaultAttService().load(new LoadRequest(root,config,o.environment(),o.outputDirectory(),o.runId(),scenario,
                targetType,targetId,o.users(),o.arrivalRate(),o.warmup(),o.rampUp(),o.duration(),o.rampDown(),o.thinkTime(),
                o.maxConcurrent(),o.overloadPolicy(),o.overrides(),listener,null,false));
        printOperation(o,result,root);
        return result.exitCode();
    }

    static int validate(VNextOptions o,Path packageRoot,Path config) throws Exception {
        Path runtimeRoot=RuntimeConfigPath.root(config);
        if(o.all()) return validateAll(o,packageRoot,config,runtimeRoot);
        String scope=o.targetKind();
        if("config".equals(scope)) {
            AttValidationReport report=RuntimeConfigurationValidator.validate(config,runtimeRoot,
                    o.packageId()==null?null:packageRoot,o.environment(),"LOCAL",false);
            report.context("packageId",o.packageId());report.context("environment",o.environment());
            printValidation(o,report);
            return report.exitCode();
        }
        AttValidationReport report=RuntimeConfigurationValidator.validate(config,runtimeRoot,packageRoot,
                o.environment(),"LOCAL",false,"package".equals(scope),null);
        AttValidationReport combined=new AttValidationReport("package".equals(scope)?"PACKAGE":"SELECTED","LOCAL");
        combined.context("packageId",o.packageId()==null?packageRoot.getFileName().toString():o.packageId());
        combined.context("environment",o.environment());
        combined.appendSections(report);
        AttValidationReport.Section checks;
        if("package".equals(scope)) {
            checks=combined.addSection("Testcases / Templates / Flows / dependencies",null,"PASS");
            OperationResult validation=new att.api.DefaultAttService().validate(new ValidateRequest(packageRoot,config,o.environment(),
                    Collections.<Path>emptyList(),null,Collections.<String>emptySet(),Collections.<String>emptySet(),
                    Collections.<String>emptySet(),false,"package"));
            addDiagnostics(checks,validation.diagnostics(),packageRoot);
            if(validation.exitCode()!=0)checks.status("FAIL");
        } else if("case".equals(scope)||"suite".equals(scope)||"cases".equals(scope)) {
            checks=combined.addSection("Selected Cases / dependency closure",targetSource(scope,o),"PASS");
            List<Path> suites=new ArrayList<Path>(o.suites());
            java.util.Set<String> cases=new java.util.LinkedHashSet<String>(o.caseIds());
            if("suite".equals(scope))suites.add(Paths.get(o.target()));
            if("case".equals(scope))cases.add(o.target());
            OperationResult validation=new att.api.DefaultAttService().validate(new ValidateRequest(packageRoot,config,o.environment(),
                    suites,o.suiteDirectory(),cases,o.tags(),o.excludeTags(),false,"selected"));
            addDiagnostics(checks,validation.diagnostics(),packageRoot);
            if(validation.exitCode()!=0)checks.status("FAIL");
        } else if("load".equals(scope)) {
            checks=combined.addSection("Load Scenario / dependency closure",targetSource(scope,o),"PASS");
            try(PackageResourceInspector inspector=inspector(packageRoot,config,o.environment())) {
                Map<String,Object> details=inspector.validateLoadScenarioFile(o.target());
                Object diagnostics=details.get("diagnostics");
                if(diagnostics instanceof List) for(Object d:(List<?>)diagnostics) if(d instanceof Map)addSafeDiagnostic(checks,castMap(d),packageRoot);
                if(hasErrors(checks))checks.status("FAIL");
            } catch(Exception invalid) {
                addTargetValidationFailure(checks,o,packageRoot,"ATT-LOAD-SCENARIO-INVALID",invalid);
            }
        } else {
            checks=combined.addSection("Selected Resource / dependency closure",targetSource(scope,o),"PASS");
            try(PackageResourceInspector inspector=inspector(packageRoot,config,o.environment())) {
                Map<String,Object> details=inspector.inspect("detail",scope,o.target(),null,0,1);
                Object resource=details.get("resource");
                String state=resource instanceof Map?String.valueOf(((Map<?,?>)resource).get("state")):"missing";
                if(!"ready".equalsIgnoreCase(state)) {
                    checks.status("FAIL");
                    addSafeDiagnostic(checks,validationDiagnostic("ATT-RESOURCE-INVALID","ERROR",targetSource(scope,o),"target",
                            "Selected resource is missing or has invalid dependencies",null),packageRoot);
                }
                Object diagnostics=details.get("diagnostics");
                if(diagnostics instanceof List)for(Object d:(List<?>)diagnostics)if(d instanceof Map)addSafeDiagnostic(checks,castMap(d),packageRoot);
                if(hasErrors(checks))checks.status("FAIL");
            } catch(Exception invalid) {
                addTargetValidationFailure(checks,o,packageRoot,"ATT-RESOURCE-INVALID",invalid);
            }
        }
        if(report.exitCode()==3)combined.incomplete();
        else if(report.exitCode()!=0)combined.exitCode(2);
        if(hasErrors(checks))combined.exitCode(2);
        printValidation(o,combined);
        return combined.exitCode();
    }

    private static int validateAll(VNextOptions o,Path currentRoot,Path config,Path runtimeRoot) throws Exception {
        Path home=attHome();
        AttValidationReport report=new AttValidationReport("ALL","LOCAL");
        report.context("runtimeConfig",logical(config.toString(),runtimeRoot));
        AttValidationReport runtime=RuntimeConfigurationValidator.validate(config,runtimeRoot,null,null,"LOCAL",true);
        copySections(report,runtime);
        if(runtime.exitCode()==2)report.exitCode(2);else if(runtime.exitCode()!=0)report.incomplete();

        Map<Path,Set<String>> packagesByRoot=new LinkedHashMap<Path,Set<String>>();
        Set<Path> homeRoots=new LinkedHashSet<Path>(),registeredRoots=new LinkedHashSet<Path>();
        Set<Path> unregisteredRoots=new LinkedHashSet<Path>();
        if(home==null) {
            report.incomplete();
            AttValidationReport.Section homeSection=report.addSection("ATT_HOME Package Registry",null,"WARN");
            homeSection.diagnostic(validationDiagnostic("ATT-VALIDATION-INCOMPLETE","WARNING",null,"ATT_HOME",
                    "ATT_HOME is not configured, so local Package directories cannot be enumerated",null));
        } else {
            Path packageDirectory=home.resolve("packages");
            if(!Files.exists(packageDirectory,LinkOption.NOFOLLOW_LINKS)) {
                report.addSection("ATT_HOME Package Registry","packages/","SKIP").diagnostic(
                        validationDiagnostic("ATT-PACKAGE-DIRECTORY-ABSENT","INFO","packages/","packages","No ATT_HOME/packages directory is present",null));
            } else if(Files.isSymbolicLink(packageDirectory)||!Files.isDirectory(packageDirectory,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(packageDirectory)) {
                report.exitCode(2);
                report.addSection("ATT_HOME Package Registry","packages/","FAIL").diagnostic(
                        validationDiagnostic("ATT-PACKAGE-DIRECTORY-INVALID","ERROR","packages/","packages","ATT_HOME/packages must be a readable, non-symlink directory",null));
            } else {
                Path canonicalPackages=packageDirectory.toRealPath();
                List<Path> immediate=new ArrayList<Path>();
                try(java.util.stream.Stream<Path> stream=Files.list(packageDirectory)) {
                    java.util.Iterator<Path> entries=stream.iterator();
                    while(entries.hasNext()&&immediate.size()<=200)immediate.add(entries.next());
                }
                Collections.sort(immediate);
                if(immediate.size()>200) {
                    immediate=immediate.subList(0,200);report.incomplete();
                    report.addSection("ATT_HOME Package Registry","packages/","WARN").diagnostic(
                            validationDiagnostic("ATT-VALIDATION-LIMIT","WARNING","packages/","packages","Package scan limit of 200 roots was reached; report is incomplete",null));
                }
                for(Path child:immediate) {
                    String id=child.getFileName().toString();
                    if(Files.isSymbolicLink(child)) {
                        if(id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
                            report.addSection("Skipped Package Directory","packages/"+id,"WARN").diagnostic(
                                    validationDiagnostic("ATT-PACKAGE-SYMLINK-SKIPPED","WARNING","packages/"+id,"root",
                                            "Symbolic link was not followed during Package discovery",null));
                        continue;
                    }
                    if(!Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS))continue;
                    if(!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                        report.addSection("Skipped Package Directory","packages/","WARN").diagnostic(
                                validationDiagnostic("ATT-PACKAGE-ID-INVALID","WARNING","packages/","packageId","A directory has an invalid Package ID and was not scanned",null));
                        continue;
                    }
                    try {
                        if(Files.isSymbolicLink(child)||!Files.isReadable(child))throw new IllegalArgumentException("Package root must be readable and must not be a symlink");
                        Path canonical=child.toRealPath();
                        if(!canonical.getParent().equals(canonicalPackages))throw new IllegalArgumentException("Package root must be an immediate child of ATT_HOME/packages");
                        addPackageRoot(packagesByRoot,canonical,id);homeRoots.add(canonical);
                    } catch(Exception invalid) {
                        AttValidationReport.Section skipped=report.addSection("Package "+id,"packages/"+id,"WARN");
                        skipped.diagnostic(validationDiagnostic("ATT-PACKAGE-ROOT-INVALID","WARNING","packages/"+id,"root",
                                "Package root is unreadable or fails the safe-directory checks",null));
                    }
                }
            }
        }

        Path serverConfig=att.validation.ServerConfigurationValidator.activeConfigPath();
        att.server.api.AttValidationReport.Section serverSection;
        att.validation.ServerConfigurationValidator.Result serverResult=null;
        if(serverConfig==null) {
            serverSection=report.addSection("ATT Server Config","att-server.yaml","SKIP");
            serverSection.diagnostic(validationDiagnostic("ATT-SERVER-CONFIG-SKIPPED","INFO","att-server.yaml","serverConfig",
                    "No active ATT Server Config is installed or configured",null));
        } else {
            report.context("serverConfig","att-server.yaml");
            serverSection=report.addSection("ATT Server Config","att-server.yaml","PASS");
            try {
                serverResult=att.validation.ServerConfigurationValidator.validate(serverConfig);
                serverSection.child("Settings / Security / Workers / Package Registry","att-server.yaml","PASS");
                Path boundRuntime=serverResult.runtimeConfig();
                boolean sameRuntime=false;
                try { sameRuntime=boundRuntime.toRealPath().equals(config.toRealPath()); }
                catch(java.io.IOException ignored) { }
                List<String> serverProfiles;
                if(sameRuntime) {
                    try { serverProfiles=RuntimeConfigurationValidator.declaredProfiles(config); }
                    catch(Exception invalidRuntime) { serverProfiles=Collections.emptyList(); }
                    serverSection.child("Bound Shared Runtime Config",logical(boundRuntime.toString(),runtimeRoot),runtime.exitCode()==0?"PASS":runtime.exitCode()==2?"FAIL":"WARN");
                    if(runtime.exitCode()==2){serverSection.status("FAIL");report.exitCode(2);}
                    else if(runtime.exitCode()!=0){serverSection.status("WARN");report.incomplete();}
                } else {
                    Path boundRoot=RuntimeConfigPath.root(boundRuntime);
                    AttValidationReport boundReport=RuntimeConfigurationValidator.validate(boundRuntime,boundRoot,null,null,"LOCAL",true);
                    AttValidationReport.Section binding=serverSection.child("Bound Shared Runtime Config",logical(boundRuntime.toString(),boundRoot),
                            boundReport.exitCode()==0?"PASS":"FAIL");
                    copySections(binding,boundReport);
                    try { serverProfiles=RuntimeConfigurationValidator.declaredProfiles(boundRuntime); }
                    catch(Exception invalidRuntime) { serverProfiles=Collections.emptyList(); }
                    if(boundReport.exitCode()==2){serverSection.status("FAIL");report.exitCode(2);}
                    else if(boundReport.exitCode()!=0){serverSection.status("WARN");report.incomplete();}
                }
                for(Map.Entry<String,Path> entry:serverResult.packages().entrySet()) {
                    Path canonical=entry.getValue().toRealPath();
                    addPackageRoot(packagesByRoot,canonical,entry.getKey());registeredRoots.add(canonical);
                }
                if(serverResult.allowedEnvironments()!=null) for(String allowed:serverResult.allowedEnvironments())
                    if(!containsIgnoreCase(serverProfiles,allowed)) {
                        serverSection.status("FAIL");report.exitCode(2);
                        serverSection.diagnostic(validationDiagnostic("ATT-SERVER-ENVIRONMENT-UNKNOWN","ERROR","att-server.yaml",
                                "server.packageConfiguration.allowedEnvironments","Server policy refers to an Environment not declared in Shared Runtime Config",null));
                    }
                for(String required:serverResult.requiredExplicitEnvironments())
                    if(!containsIgnoreCase(serverProfiles,required)) {
                        serverSection.status("FAIL");report.exitCode(2);
                        serverSection.diagnostic(validationDiagnostic("ATT-SERVER-ENVIRONMENT-UNKNOWN","ERROR","att-server.yaml",
                                "server.packageConfiguration.requireExplicitEnvironments","Server policy refers to an Environment not declared in Shared Runtime Config",null));
                    }
            } catch(Exception invalid) {
                serverSection.status("FAIL");report.exitCode(2);
                String message=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(
                        att.server.api.internal.DiagnosticSanitizer.redactText(safe(invalid)),home);
                serverSection.diagnostic(validationDiagnostic("ATT-SERVER-CONFIG-INVALID","ERROR","att-server.yaml","server",message,
                        "Correct the active ATT Server Config and retry read-only validation."));
            }
        }
        if(serverResult!=null)for(Path path:homeRoots)if(!registeredRoots.contains(path))unregisteredRoots.add(path);

        if(packagesByRoot.size()>200) {
            Map<Path,Set<String>> bounded=new LinkedHashMap<Path,Set<String>>();
            for(Map.Entry<Path,Set<String>> entry:packagesByRoot.entrySet()) {if(bounded.size()==200)break;bounded.put(entry.getKey(),entry.getValue());}
            packagesByRoot=bounded;report.incomplete();
            AttValidationReport.Section limit=report.addSection("Package Scan Limit",null,"WARN");
            limit.diagnostic(validationDiagnostic("ATT-VALIDATION-LIMIT","WARNING",null,"packages","Package scan limit of 200 roots was reached; report is incomplete",null));
        }
        try {report.context("packages",packagesByRoot.size());report.context("environments",RuntimeConfigurationValidator.validationProfiles(config).profiles());}
        catch(Exception ignored) {report.incomplete();}

        long validationWorkUnits=0L;
        Map<String,String> packageRuntimeConfigs=new LinkedHashMap<String,String>();
        for(Map.Entry<Path,Set<String>> candidate:packagesByRoot.entrySet()) {
            Path canonical=candidate.getKey();List<String> ids=new ArrayList<String>(candidate.getValue());Collections.sort(ids,String.CASE_INSENSITIVE_ORDER);
            String id=ids.isEmpty()?canonical.getFileName().toString():ids.get(0);
            String name="Package "+id+(ids.size()>1?" (also registered as "+String.join(", ",ids.subList(1,ids.size()))+")":"");
            boolean unregistered=unregisteredRoots.contains(canonical);
            boolean registered=registeredRoots.contains(canonical);
            Path packageConfig=registered&&serverResult!=null?serverResult.runtimeConfig():config;
            Path packageRuntimeRoot=RuntimeConfigPath.root(packageConfig);
            packageRuntimeConfigs.put(id,registered?"server.runtimeConfigPath":"cli.config");
            att.validation.PackageValidationBudget.Result budget=att.validation.PackageValidationBudget.inspect(canonical);
            validationWorkUnits+=budget.entries();
            if(!budget.complete()) {
                report.incomplete();AttValidationReport.Section skipped=report.addSection(name,"packages/"+id,"WARN");
                skipped.diagnostic(validationDiagnostic("ATT-VALIDATION-LIMIT","WARNING","packages/"+id,"resources",
                        "Package inventory exceeded the bounded read-only scan budget; its full validation was skipped",null));
                if(unregistered)skipped.diagnostic(validationDiagnostic("ATT-PACKAGE-NOT-REGISTERED","WARNING","packages/"+id,"packageId",
                        "Package directory is not registered with the active Server Config",null));
                if(validationWorkUnits>=att.validation.PackageValidationBudget.MAX_INSTALLATION_WORK_UNITS) {
                    AttValidationReport.Section limit=report.addSection("Package Validation Work Limit",null,"WARN");
                    limit.diagnostic(validationDiagnostic("ATT-VALIDATION-LIMIT","WARNING",null,"packages",
                            "The installation-wide Package/Environment work budget was reached; remaining checks were skipped",null));
                    break;
                }
                continue;
            }
            List<String> workProfiles;
            try {workProfiles=RuntimeConfigurationValidator.validationProfiles(packageConfig).profiles();}
            catch(Exception invalid) {workProfiles=Collections.emptyList();}
            long packageWorkUnits=budget.entries()*(Math.max(1,workProfiles.size())+1L);
            if(packageWorkUnits>att.validation.PackageValidationBudget.MAX_INSTALLATION_WORK_UNITS-validationWorkUnits) {
                report.incomplete();AttValidationReport.Section limit=report.addSection("Package Validation Work Limit",null,"WARN");
                limit.diagnostic(validationDiagnostic("ATT-VALIDATION-LIMIT","WARNING",null,"packages",
                        "The installation-wide Package/Environment work budget was reached; remaining checks were skipped",null));
                break;
            }
            validationWorkUnits+=packageWorkUnits;
            AttValidationReport packageReport=RuntimeConfigurationValidator.validate(packageConfig,packageRuntimeRoot,canonical,null,"LOCAL",true,
                    registered&&serverResult!=null?serverResult.allowedEnvironments():null);
            int packageExit=packageReport.exitCode();
            String packageStatus=packageExit==2?"FAIL":packageExit==0?(unregistered?"WARN":"PASS"):"WARN";
            AttValidationReport.Section pkg=report.addSection(name,"packages/"+id,packageStatus);
            if(unregistered)pkg.diagnostic(validationDiagnostic("ATT-PACKAGE-NOT-REGISTERED","WARNING","packages/"+id,"packageId",
                    "Package directory is not registered with the active Server Config",null));
            copySections(pkg,packageReport);
            if(packageExit==2)report.exitCode(2);else if(packageExit!=0)report.incomplete();

            List<String> profiles;
            try {profiles=RuntimeConfigurationValidator.validationProfiles(packageConfig).profiles();}
            catch(Exception invalid) {
                report.incomplete();
                AttValidationReport.Section incomplete=pkg.child("Testcases / Templates / Flows / dependencies",null,"WARN");
                incomplete.diagnostic(validationDiagnostic("ATT-VALIDATION-INCOMPLETE","ERROR","config/config.yaml","environments",
                        "Environment profiles could not be enumerated, so full Package inventory checks could not finish",null));
                continue;
            }
            if(profiles.isEmpty())profiles=Collections.singletonList(null);
            for(String profile:profiles) {
                String profileLabel=profile==null?"default":profile;
                if(registered&&serverResult!=null&&serverResult.allowedEnvironments()!=null&&!serverResult.allowedEnvironments().contains(profileLabel.toLowerCase(java.util.Locale.ROOT))) {
                    AttValidationReport.Section skipped=pkg.child(profileLabel+" — Testcases / Templates / Flows / dependencies",null,"SKIP");
                    skipped.diagnostic(validationDiagnostic("ATT-ENVIRONMENT-FORBIDDEN","INFO","att-server.yaml","environment",
                            "Environment is excluded by the active Server Package policy",null));
                    continue;
                }
                final String selectedProfile=profile;
                validateInventory(report,pkg,id,profileLabel,canonical,()->new att.api.DefaultAttService().validate(
                        new ValidateRequest(canonical,packageConfig,selectedProfile,Collections.<Path>emptyList(),null,
                                Collections.<String>emptySet(),Collections.<String>emptySet(),Collections.<String>emptySet(),false,"package")));
            }
        }
        report.context("validationWorkUnits",validationWorkUnits);
        report.context("packageRuntimeConfigs",packageRuntimeConfigs);
        if(runtime.exitCode()==2)report.exitCode(2);else if(runtime.exitCode()!=0)report.incomplete();
        printValidation(o,report);return report.exitCode();
    }

    private static void addPackageRoot(Map<Path,Set<String>> packages,Path root,String id) {
        Set<String> ids=packages.get(root);if(ids==null){ids=new LinkedHashSet<String>();packages.put(root,ids);}ids.add(id);
    }
    static void validateInventory(AttValidationReport report,AttValidationReport.Section pkg,String packageId,
                                  String profileLabel,Path packageRoot,PackageInventoryValidator validator) {
        String sectionName=profileLabel+" — Testcases / Templates / Flows / dependencies";
        try {
            OperationResult full=validator.validate();
            AttValidationReport.Section inventory=pkg.child(sectionName,null,full.exitCode()==0?"PASS":"FAIL");
            addDiagnostics(inventory,full.diagnostics(),packageRoot);
            if(full.exitCode()!=0){pkg.status("FAIL");report.exitCode(2);}
        } catch(Exception invalid) {
            pkg.status("FAIL");
            AttValidationReport.Section inventory=pkg.child(sectionName,null,"FAIL");
            String message=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(
                    att.server.api.internal.DiagnosticSanitizer.redactText(safe(invalid)),packageRoot);
            inventory.diagnostic(validationDiagnostic("ATT-PACKAGE-INVENTORY-FAILED","ERROR","packages/"+packageId,
                    "environment",message,"Correct the Package or Environment inputs and retry read-only validation."));
            report.exitCode(2);
        }
    }
    interface PackageInventoryValidator { OperationResult validate() throws Exception; }
    private static boolean containsIgnoreCase(List<String> values,String value) {
        for(String item:values)if(item.equalsIgnoreCase(value))return true;return false;
    }

    private static PackageResourceInspector inspector(Path root,Path config,String environment) {
        return new PackageResourceInspector(root,config,environment,Collections.<String>emptyList(),65536,262144);
    }
    private static void copySections(AttValidationReport target,AttValidationReport source) {
        Object sections=source.toMap().get("sections");
        if(!(sections instanceof List))return;
        for(Object value:(List<?>)sections) if(value instanceof Map)copySection(target,(Map<String,Object>)value);
    }
    private static void copySections(AttValidationReport.Section target,AttValidationReport source) {
        Object sections=source.toMap().get("sections");
        if(!(sections instanceof List))return;
        for(Object value:(List<?>)sections) if(value instanceof Map)copySection(target,(Map<String,Object>)value);
    }
    @SuppressWarnings("unchecked") private static void copySection(AttValidationReport target,Map<String,Object> source) {
        AttValidationReport.Section to=target.addSection(String.valueOf(source.get("name")),(String)source.get("source"),String.valueOf(source.get("status")));
        Object diagnostics=source.get("diagnostics");if(diagnostics instanceof List)for(Object d:(List<?>)diagnostics)if(d instanceof Map)to.diagnostic((Map<String,Object>)d);
        Object children=source.get("sections");if(children instanceof List)for(Object child:(List<?>)children)if(child instanceof Map)copySection(to,(Map<String,Object>)child);
    }
    @SuppressWarnings("unchecked") private static void copySection(AttValidationReport.Section target,Map<String,Object> source) {
        AttValidationReport.Section to=target.child(String.valueOf(source.get("name")),(String)source.get("source"),String.valueOf(source.get("status")));
        Object diagnostics=source.get("diagnostics");if(diagnostics instanceof List)for(Object d:(List<?>)diagnostics)if(d instanceof Map)to.diagnostic((Map<String,Object>)d);
        Object children=source.get("sections");if(children instanceof List)for(Object child:(List<?>)children)if(child instanceof Map)copySection(to,(Map<String,Object>)child);
    }
    private static void addDiagnostics(AttValidationReport.Section section,List<Diagnostic> diagnostics,Path root) {
        for(Diagnostic diagnostic:diagnostics) {
            Map<String,Object> value=diagnostic.toMap();
            Object file=value.remove("file");if(file instanceof String)value.put("source",logical((String)file,root));
            Object summary=value.remove("summary");Object detail=value.remove("detail");
            value.put("message",summary==null?String.valueOf(detail):String.valueOf(summary));
            if(detail!=null)value.put("detail",detail);
            @SuppressWarnings("unchecked") Map<String,Object> safe=(Map<String,Object>)safeDiagnosticValue(value,root);
            section.diagnostic(safe);
        }
    }
    private static void addSafeDiagnostic(AttValidationReport.Section section,Map<String,Object> diagnostic,Path root) {
        section.diagnostic(castMap(safeDiagnosticValue(diagnostic,root)));
    }
    private static void addTargetValidationFailure(AttValidationReport.Section section,VNextOptions options,Path root,
                                                  String code,Exception failure) {
        section.status("FAIL");
        String source=options.target();
        try {
            Path target=Paths.get(source);
            if(!target.isAbsolute())source=target.normalize().toString().replace('\\','/');
            else source=logical(target.toString(),root);
        } catch(Exception ignored) { }
        addSafeDiagnostic(section,validationDiagnostic(code,"ERROR",source,"target",safe(failure),
                "Correct the selected target and its package-relative source, then retry validation."),root);
    }
    static Object safeDiagnosticValue(Object raw,Path root) {
        if(raw instanceof Map) {
            Map<String,Object> result=new LinkedHashMap<String,Object>();
            for(Map.Entry<?,?> entry:((Map<?,?>)raw).entrySet())result.put(String.valueOf(entry.getKey()),safeDiagnosticValue(entry.getValue(),root));
            return result;
        }
        if(raw instanceof Iterable) {
            List<Object> result=new ArrayList<Object>();for(Object item:(Iterable<?>)raw)result.add(safeDiagnosticValue(item,root));return result;
        }
        if(raw instanceof String) {
            String safe=att.server.api.internal.DiagnosticSanitizer.redactText((String)raw);
            return att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(safe,root);
        }
        return raw;
    }
    private static boolean hasErrors(AttValidationReport.Section section) {
        for(Map<String,Object> diagnostic:section.diagnosticsView())if(!"WARNING".equals(diagnostic.get("severity"))&& !"INFO".equals(diagnostic.get("severity")))return true;
        return false;
    }
    private static Map<String,Object> validationDiagnostic(String code,String severity,String source,String field,String message,String suggestion) {
        Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();diagnostic.put("code",code);diagnostic.put("severity",severity);
        if(source!=null)diagnostic.put("source",source);if(field!=null)diagnostic.put("field",field);diagnostic.put("message",message);if(suggestion!=null)diagnostic.put("suggestion",suggestion);return diagnostic;
    }
    private static String targetSource(String scope,VNextOptions options) { return "cases".equals(scope)?"case selection":options.target(); }
    private static String logical(String path,Path root) { try {Path value=Paths.get(path).toAbsolutePath().normalize();Path base=root.toAbsolutePath().normalize();return value.startsWith(base)?base.relativize(value).toString().replace('\\','/'):value.getFileName().toString();}catch(Exception ignored){return path;} }
    @SuppressWarnings("unchecked") private static Map<String,Object> castMap(Object value){return (Map<String,Object>)value;}
    private static boolean isResource(String kind){return "template".equals(kind)||"flow".equals(kind)||"tool".equals(kind);}
    private static Path attHome() {String home=System.getProperty("att.home");if(home==null||home.trim().isEmpty())home=System.getenv("ATT_HOME");return home==null||home.trim().isEmpty()?null:Paths.get(home).toAbsolutePath().normalize();}

    private static void printOperation(VNextOptions options,OperationResult result,Path root) {
        Map<String,Object> output=new LinkedHashMap<String,Object>();output.put("schemaVersion","att-operation/v1");output.put("backend","LOCAL");
        output.put("command",options.command());output.put("targetKind",options.targetKind());output.put("target",options.target());
        output.put("packageId",options.packageId());output.put("environment",options.environment());output.put("executionId",result.executionId());
        output.put("status",result.status());output.put("exitCode",result.exitCode());output.put("durationMs",result.durationMs());
        output.put("paths",displayPaths(result.paths(),root));output.put("summary",safeDiagnosticValue(result.summary(),root));
        List<Object> diagnostics=new ArrayList<Object>();for(Diagnostic d:result.diagnostics())diagnostics.add(safeDiagnosticValue(d.toMap(),root));output.put("diagnostics",diagnostics);
        if("json".equals(options.format()))System.out.println(att.validation.JsonSupport.write(output));
        else {
            System.out.println(options.command().toUpperCase(java.util.Locale.ROOT)+" "+result.status()+" ("+result.exitCode()+")");
            if(result.executionId()!=null)System.out.println("Run ID: "+result.executionId());
            for(Map.Entry<String,String> path:result.paths().entrySet())System.out.println(path.getKey()+": "+logical(path.getValue(),root));
            if(!result.diagnostics().isEmpty())System.err.println(att.validation.DiagnosticRenderer.exception(result.diagnostics().get(0)));
        }
    }
    private static Map<String,String> displayPaths(Map<String,String> paths,Path root) {Map<String,String> out=new LinkedHashMap<String,String>();for(Map.Entry<String,String> item:paths.entrySet())out.put(item.getKey(),logical(item.getValue(),root));return out;}
    private static void printValidation(VNextOptions options,AttValidationReport report) {
        report.limitOutputBytes(1024*1024);
        if("json".equals(options.format()))System.out.println(att.validation.JsonSupport.write(report.toMap()));else System.out.println(ValidationReportRenderer.human(report));
    }
    private static int fail(VNextOptions options,String code,String message,int exit,String[] args) {
        String safe=message==null?"ATT command failed":message;
        safe=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(att.server.api.internal.DiagnosticSanitizer.redactText(safe));
        String format=options==null?VNextOptions.requestedFormat(args):options.format();
        String command=options==null?VNextOptions.requestedCommand(args):options.command();
        String backend=options==null?VNextOptions.requestedBackend(args):options.server()==null?"LOCAL":"SERVER";
        if(exit==2&&"validate".equals(command)) {
            String scope=options==null?VNextOptions.requestedValidationScope(args):validationScope(options);
            String source=options==null?null:options.target()==null?options.targetKind():options.target();
            if(source!=null)source=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(source);
            AttValidationReport report=new AttValidationReport(scope,backend);
            AttValidationReport.Section request=report.addSection("Validation request",source,"FAIL");
            request.diagnostic(validationDiagnostic(code,"ERROR",source,"command",safe,null));
            if("json".equals(format))System.out.println(att.validation.JsonSupport.write(report.toMap()));
            else System.out.println(ValidationReportRenderer.human(report));
            return exit;
        }
        if("json".equals(format)) {
            Map<String,Object> output=new LinkedHashMap<String,Object>();output.put("schemaVersion","att-operation/v1");
            output.put("backend",backend);
            output.put("command",command==null?"parse":command);
            output.put("status",exit==2?"INVALID":"ERROR");output.put("exitCode",exit);output.put("diagnostics",Collections.singletonList(validationDiagnostic(code,"ERROR",null,null,safe,null)));
            System.out.println(att.validation.JsonSupport.write(output));
        } else System.err.println(code+": "+safe);
        return exit;
    }
    private static String validationScope(VNextOptions options) {
        if(options.all())return "ALL";
        if("config".equals(options.targetKind()))return "CONFIG";
        if("package".equals(options.targetKind()))return "PACKAGE";
        return "SELECTED";
    }
    private static String safe(Exception error) {String message=error.getMessage();return message==null?error.getClass().getSimpleName():message.replaceAll("(?i)(password|authorization)\\s*[:=]\\s*[^ ,]+","$1=[REDACTED_SECRET]");}
    private static String safe(Throwable error) {String message=error.getMessage();return message==null?error.getClass().getSimpleName():message.replaceAll("(?i)(password|authorization)\\s*[:=]\\s*[^ ,]+","$1=[REDACTED_SECRET]");}
    private static void help() {System.out.println("Usage: att [--local|--server <profile|url>] [options] <run|load|validate> <target-kind> [target-id/path]\nCommands: run, load, validate\nValidate scopes: config, package, case, suite, template, flow, tool, load, --all\nOptions: -p/--package <id>, -e/--env <profile>, --config <path> (local), --format human|json, --quiet, --verbose, --help, --version");}
}
