package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in disk-backed upgrade/query benchmark; never adds large fixtures to normal CI. */
@EnabledIfSystemProperty(named="att.jobs.benchmark",matches="true")
class JobTargetBenchmarkTest {
    @TempDir Path temp;
    private final com.sun.management.OperatingSystemMXBean os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    private final com.sun.management.ThreadMXBean threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private Connection connect(ServerConfig config) throws Exception {
        var properties=new Properties();properties.setProperty("user","sa");properties.setProperty("password","");
        return new org.h2.Driver().connect("jdbc:h2:file:"+config.dataDir.resolve("db/att-server")+";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0",properties);
    }
    private long bytes(ServerConfig config) throws Exception {return Files.size(config.dataDir.resolve("db/att-server.mv.db"));}
    private long scalar(Connection c,String sql) throws Exception {try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    private Map<String,String> io(Connection c) throws Exception {
        var out=new LinkedHashMap<String,String>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT SETTING_NAME,SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME LIKE 'info.FILE_%'")){while(r.next())out.put(r.getString(1),r.getString(2));}return out;
    }
    private long allocated(){return threads.getThreadAllocatedBytes(Thread.currentThread().getId());}
    private static double ms(long nanos){return Math.round(nanos/1e4)/100.0;}
    @Test void upgradeAndSubstringGroupedQueries() throws Exception {
        System.out.println("BENCH environment: "+System.getProperty("os.name")+" "+System.getProperty("os.arch")+" Java "+System.getProperty("java.version")+" CPUs="+os.getAvailableProcessors());
        for(int size:Arrays.stream(System.getProperty("att.jobs.benchmark.sizes","10000,50000").split(",")).mapToInt(Integer::parseInt).toArray()) {
            Path root=Files.createDirectories(temp.resolve("n"+size)),pkg=Files.createDirectories(root.resolve("pkg")),yaml=root.resolve("server.yaml");
            Files.writeString(yaml,"server:\n  dataDir: "+root.resolve("data")+"\nworkers: {}\npackages:\n  allowedRoots: ["+pkg+"]\n  entries:\n    p: "+pkg+"\n");
            ServerConfig config=ServerConfig.load(yaml);
            try(JobStore ignored=new JobStore(config)) { /* Initialize schema before legacy seed. */ }
            String[] specs=new String[3],identities=new String[3];
            for(int kind=0;kind<3;kind++) {
                var definition=ServerRuntime.JSON.createObjectNode();definition.put("executionType",kind==2?"load":"run");definition.put("packageId","p");
                if(kind==0){definition.putObject("input");definition.put("schemaVersion",RunDefinition.VERSION);definition.putObject("target").put("type","flow").put("id","ordinary-flow");}
                if(kind==1){definition.put("schemaVersion",RunDefinition.VERSION);var cases=definition.putObject("selection").putArray("caseIds");for(int i=0;i<2000;i++)cases.add("suite-case-"+String.format("%04d",i));}
                if(kind==2){var workloads=definition.putObject("scenario").putArray("workloads");for(int w=0;w<10;w++){var mix=workloads.addObject().put("id","workload-"+w).putArray("mix");for(int i=0;i<20;i++)mix.addObject().putObject("target").put("type",i%2==0?"flow":"template").put("id","load-target-"+w+"-"+i);}}
                specs[kind]=new JobExecutionSpec(definition,"a".repeat(64),null).json();identities[kind]=ServerRuntime.JSON.writeValueAsString(JobListIdentity.from(definition));
            }
            long snapshotBytes=0;
            try(var c=connect(config);var p=c.prepareStatement("INSERT INTO jobs(job_id,command,package_id,principal,status,created_at,att_version,request_summary,execution_spec,target_identity,target_sort,target_group,environment) VALUES(?,?,'p','alice','PASS','2026-10-23T00:00:00Z','4.2.4','{}',?,?,?,?,'SIT')")) {
                c.setAutoCommit(false);
                for(int i=0;i<size;i++) {int kind=i%100==0?1:i%100==1?2:0;
                    p.setString(1,String.format("J%016d",i));p.setString(2,kind==2?"load":"run");p.setString(3,specs[kind]);p.setString(4,identities[kind]);p.setString(5,"group-"+kind);p.setString(6,"group-"+kind);p.addBatch();snapshotBytes+=specs[kind].length();
                    if(i%100==99){p.executeBatch();c.commit();}
                }p.executeBatch();c.commit();
            }
            // The upgraded deployment does not yet have the version/cursor index.
            try(var c=connect(config);var s=c.createStatement()){s.execute("DROP INDEX jobs_target_search_version_idx");}
            System.out.println("BENCH seeded jobs="+size+" snapshotsBytes="+snapshotBytes);
            long beforeBytes=bytes(config),cpu=os.getProcessCpuTime(),allocation=allocated(),start=System.nanoTime();
            try(JobStore store=new JobStore(config)) {
                System.out.println("BENCH open jobs="+size+" wallMs="+ms(System.nanoTime()-start)+" cpuMs="+ms(os.getProcessCpuTime()-cpu)+" threadAllocatedBytes="+(allocated()-allocation));
                if(Boolean.getBoolean("att.jobs.benchmark.startupOnly")) {
                    assertEquals((long)size,store.queryJobs(JobQuery.parse(Map.of(),"alice")).get("total"));
                    assertThrows(JobStore.TargetSearchPendingException.class,()->store.queryJobs(JobQuery.parse(Map.of("search","case-1999"),"alice")));
                    continue;
                }
                cpu=os.getProcessCpuTime();allocation=allocated();start=System.nanoTime();
                int batches=0;double maxBatchMs=0;
                while(true){long batchStart=System.nanoTime();int count=store.backfillSearchTargetsBatch();maxBatchMs=Math.max(maxBatchMs,ms(System.nanoTime()-batchStart));if(count==0)break;batches++;if(batches%100==0)System.out.println("BENCH progress jobs="+size+" batches="+batches+" wallMs="+ms(System.nanoTime()-start));}
                double elapsed=ms(System.nanoTime()-start),cpuMs=ms(os.getProcessCpuTime()-cpu);long allocated=allocated()-allocation;
                try(var c=connect(config)) {
                    long expected=(size/100)*2200L+(size-size/50);assertEquals(expected,scalar(c,"SELECT COUNT(*) FROM job_targets"));assertEquals(0,scalar(c,"SELECT COUNT(*) FROM jobs WHERE target_search_version=0"));
                    System.out.println("BENCH backfill jobs="+size+" targets="+expected+" batches="+batches+" maxBatchMs="+maxBatchMs+" snapshotsBytes="+snapshotBytes+" wallMs="+elapsed+" cpuMs="+cpuMs+" threadAllocatedBytes="+allocated+" dbBeforeBytes="+beforeBytes+" dbAfterBytes="+bytes(config)+" io="+io(c));
                    for(String search:List.of("case-1999","load-target-9-19","ordinary","absent-identifier")) {
                        var q=JobQuery.parse(Map.of("groupBy","target","search",search,"limit","100"),"alice");
                        var params=new ArrayList<Object>();String where=q.where(params);
                        for(String sql:List.of("SELECT COUNT(*) FROM jobs WHERE "+where,"SELECT job_id FROM jobs WHERE "+where+" ORDER BY "+q.order()+" LIMIT 100","SELECT target_group,COUNT(*) FROM jobs WHERE "+where+" GROUP BY target_group")) {
                            try(var p=c.prepareStatement("EXPLAIN ANALYZE "+sql)){for(int i=0;i<params.size();i++)p.setObject(i+1,params.get(i));try(var r=p.executeQuery()){r.next();System.out.println("BENCH plan jobs="+size+" search="+search+" "+r.getString(1));}}
                        }
                        var times=new ArrayList<Double>();cpu=os.getProcessCpuTime();allocation=allocated();
                        for(int attempt=0;attempt<4;attempt++){start=System.nanoTime();var result=store.queryJobs(q);times.add(ms(System.nanoTime()-start));assertEquals(search.startsWith("absent")?0L:search.equals("ordinary")?size-size/50L:size/100L,result.get("total"));assertTrue(((List<?>)result.get("items")).size()<=100);}
                        System.out.println("BENCH query jobs="+size+" search="+search+" fullPageAndGroupedTotalsMs="+times+" cpuMs="+ms(os.getProcessCpuTime()-cpu)+" threadAllocatedBytes="+(allocated()-allocation)+" io="+io(c));
                    }
                }
            }
            start=System.nanoTime();try(JobStore ignored=new JobStore(config)){System.out.println("BENCH reopen jobs="+size+" wallMs="+ms(System.nanoTime()-start));}
        }
    }
}
