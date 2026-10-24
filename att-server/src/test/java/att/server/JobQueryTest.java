package att.server;

import att.server.api.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JobQueryTest {
    @TempDir Path temp;
    private ServerConfig config() throws Exception {
        Path pkg=Files.createDirectories(temp.resolve("package")),file=temp.resolve("server.yaml");
        Files.writeString(file,"server:\n  dataDir: "+temp.resolve("data")+"\nworkers: {}\npackages:\n  allowedRoots: ["+pkg+"]\n  entries:\n    p: "+pkg+"\n");return ServerConfig.load(file);
    }
    @SuppressWarnings("unchecked")
    @Test void filtersSortsAndAggregatesAcrossFullHistoryWithStableIndividualJobPages() throws Exception {
        ServerConfig config=config();
        try(JobStore store=new JobStore(config)) {
            for(int i=0;i<240;i++) {
                String principal=i%3==0?"load-schedule:S-private":i%3==1?"alice":"bob";
                Job job=new Job("J"+String.format("%016X",i),i%2==0?"load":"run","p",principal,new WorkerRequest(),new JobEvents(config.dataDir.resolve("jobs/"+i+"/events.jsonl"),100));
                var identity=Map.of("type","flow","id",i<120?"z-target":"a-target","signature",i<120?"z-signature":"a-signature");
                store.insert(job,ServerRuntime.JSON.writeValueAsString(Map.of("targetIdentity",identity,"environment",i%2==0?"prod":"test")));
                job.status=i%2==0?"PASS":"FAIL";job.finishedAt=Instant.now();store.update(job);store.artifactRecoveryNeeded(job.id,false);
            }
            var page=store.queryJobs(JobQuery.parse(Map.of("limit","100","sort","target-asc"),"alice"));
            assertEquals(240L,page.get("total"));var items=(List<Map<String,Object>>)page.get("items");assertEquals(100,items.size());
            assertEquals("a-target",((Map<?,?>)items.get(0).get("targetIdentity")).get("id"));
            var second=store.queryJobs(JobQuery.parse(Map.of("offset","100","limit","100","sort","target-asc"),"alice"));
            Set<Object> ids=new HashSet<>();for(var item:items)ids.add(item.get("jobId"));for(var item:(List<Map<String,Object>>)second.get("items"))assertTrue(ids.add(item.get("jobId")));
            var schedules=store.queryJobs(JobQuery.parse(Map.of("principalMode","schedule","limit","25","groupBy","target"),"alice"));assertEquals(80L,schedules.get("total"));
            var groups=(List<Map<String,Object>>)schedules.get("groups");assertEquals(2,groups.size());assertTrue(groups.stream().allMatch(g->Long.valueOf(20).equals(g.get("total")))); // first target + environment group spans page boundaries
            var mine=store.queryJobs(JobQuery.parse(Map.of("principalMode","mine"),"alice"));assertEquals(80L,mine.get("total"));
            assertFalse(((List<String>)page.get("principalSuggestions")).stream().anyMatch(p->p.contains("S-private")));
            var filtered=store.queryJobs(JobQuery.parse(Map.of("executionType","load","status","PASS","search","z-target","groupBy","target"),"alice"));assertEquals(60L,filtered.get("total"));
            assertEquals(60L,((List<Map<String,Object>>)filtered.get("groups")).get(0).get("total"));
            String id=(String)items.get(0).get("jobId");store.setDeletionRequested(id,true,"alice",100);
            assertEquals(1L,store.queryJobs(JobQuery.parse(Map.of("deletion","pending"),"alice")).get("total"));
            assertEquals(239L,store.queryJobs(JobQuery.parse(Map.of("deletion","unmarked"),"alice")).get("total"));
        }
    }
    @Test void dateBoundsIncludeFractionalSecondsAfterAnExactSecond() throws Exception {
        ServerConfig config=config();
        try(JobStore store=new JobStore(config)) {
            Job job=new Job("J0000000000000001","run","p","alice",new WorkerRequest(),new JobEvents(config.dataDir.resolve("event"),1));store.insert(job,"{}");
            Instant start=job.createdAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            assertEquals(1L,store.queryJobs(JobQuery.parse(Map.of("from",start.toString(),"to",start.plusSeconds(1).toString()),"alice")).get("total"));
            assertEquals(0L,store.queryJobs(JobQuery.parse(Map.of("to",start.minusSeconds(1).toString()),"alice")).get("total"));
        }
    }
    @Test void boundedQueryAcrossFiveThousandJobs() throws Exception {
        ServerConfig config=config();
        try(JobStore store=new JobStore(config)) {
            for(int i=0;i<5000;i++) {
                Job job=new Job("J"+String.format("%016X",i),"run","p","principal-"+(i%200),new WorkerRequest(),new JobEvents(config.dataDir.resolve("events/"+i),1));
                store.insert(job,ServerRuntime.JSON.writeValueAsString(Map.of("targetIdentity",Map.of("type","flow","id","flow-"+(i%50),"signature","group-"+(i%50)))));
            }
            long start=System.nanoTime();
            var result=store.queryJobs(JobQuery.parse(Map.of("groupBy","target","offset","3000","limit","100"),"alice"));
            long millis=(System.nanoTime()-start)/1_000_000;
            assertEquals(5000L,result.get("total"));assertEquals(100,((List<?>)result.get("items")).size());
            assertTrue(((List<?>)result.get("groups")).size()<=2);
            var suggestions=store.queryJobs(JobQuery.parse(Map.of("principalMode","text","principal","principal-199"),"alice"));
            assertEquals(25L,suggestions.get("total"));assertEquals(List.of("principal-199"),suggestions.get("principalSuggestions"));
            System.out.println("Jobs benchmark: 5000 rows, grouped offset 3000, page 100: "+millis+"ms; bounded items/groups/suggestions verified");
            assertTrue(millis<10000,"Bounded local query must finish within 10 seconds");
        }
    }
    @Test void summariesAreSanitizedAndCompositeSignatureIgnoresBindingOrder() throws Exception {
        var scenario=ServerRuntime.JSON.readTree("{\"workloads\":[{\"id\":\"mix\",\"mix\":[{\"target\":{\"type\":\"tool\",\"id\":\"x\",\"arguments\":{\"password\":\"private\"}}},{\"target\":{\"type\":\"flow\",\"id\":\"y\"}}],\"load\":{\"users\":3,\"duration\":\"2s\",\"warmup\":\"1s\",\"rampUp\":\"1s\",\"maxConcurrent\":500}}]}");
        var summary=JobListIdentity.load(scenario);assertEquals(2,summary.get("bindingCount"));
        assertFalse(ServerRuntime.JSON.writeValueAsString(summary).contains("private"));assertFalse(ServerRuntime.JSON.writeValueAsString(summary).contains("maxConcurrent"));
        var workloads=(List<?>)summary.get("workloads");assertEquals(4000L,((Map<?,?>)workloads.get(0)).get("totalDurationMs"));
        var mix=(com.fasterxml.jackson.databind.node.ArrayNode)scenario.path("workloads").get(0).path("mix");var first=mix.remove(0);mix.add(first);
        assertEquals(summary.get("signature"),JobListIdentity.load(scenario).get("signature"));
        assertTrue(JobListIdentity.load(ServerRuntime.JSON.readTree("{}")).isEmpty());
        var singleLoad=JobListIdentity.load(ServerRuntime.JSON.readTree("{\"workloads\":[{\"id\":\"renamable-workload\",\"target\":{\"type\":\"flow\",\"id\":\"y\"},\"load\":{\"users\":1,\"duration\":\"1s\"}}]}"));
        var singleRun=JobListIdentity.from(ServerRuntime.JSON.readTree("{\"executionType\":\"run\",\"target\":{\"type\":\"flow\",\"id\":\"y\"}}"));
        assertEquals(singleRun.get("signature"),singleLoad.get("signature"),"A single target groups by its canonical identity across RUN and LOAD");
    }
}
