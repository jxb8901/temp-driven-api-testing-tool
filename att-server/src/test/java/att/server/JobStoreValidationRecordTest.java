package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Properties;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JobStoreValidationRecordTest {
    @TempDir Path temp;

    private ServerConfig config(String dataName,String recordLimits) throws Exception {
        Path allowed=Files.createDirectories(temp.resolve(dataName+"-packages"));
        Path pkg=Files.createDirectories(allowed.resolve("payments"));
        Path file=temp.resolve(dataName+"-server.yaml");
        String yaml="server:\n  dataDir: '"+temp.resolve(dataName+"-data")+"'\n"+
                (recordLimits==null?"":"  validationRecords:\n"+recordLimits)+
                "workers: {}\npackages:\n  allowedRoots: ['"+allowed+"']\n  entries: {payments: '"+pkg+"'}\n";
        return ServerConfig.load(Files.writeString(file,yaml));
    }

    private static void create(JobStore store,ServerConfig config,String id,String actor,String plan,Instant now) throws Exception {
        store.createValidationRecord(id,actor,false,"config",null,null,null,"request-364",now.toString(),plan,
                config.validationRecords,now);
    }

    private static void create(JobStore store,ServerConfig config,String id,String actor,String scope,String packageId,
            String plan,Instant now) throws Exception {
        store.createValidationRecord(id,actor,false,scope,packageId,null,null,"request-364",now.toString(),plan,
                config.validationRecords,now);
    }

    private static String databaseUrl(ServerConfig config) {
        return "jdbc:h2:file:"+config.dataDir.resolve("db/att-server").toString().replace('\\','/')
                +";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE;WRITE_DELAY=0";
    }

    @Test void opensFreshValidationDatabaseAndBackfillsLegacyOutcomesIdempotently() throws Exception {
        ServerConfig freshConfig=config("validation-outcome-fresh",null);
        try(JobStore ignored=new JobStore(freshConfig)) {
            assertTrue(ignored.validationRecordSummaries("alice",10).isEmpty());
        }

        ServerConfig upgradeConfig=config("validation-outcome-upgrade",null);
        Files.createDirectories(upgradeConfig.dataDir.resolve("db"));
        String id="V0123456789ABCDEF0123456789ABCDEF";
        String malformedId="VFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF";
        String url=databaseUrl(upgradeConfig);
        java.sql.Driver driver=org.h2.Driver.load();
        Properties credentials=new Properties();credentials.setProperty("user","sa");credentials.setProperty("password","");
        try(Connection connection=driver.connect(url,credentials);var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE validation_records (validation_id VARCHAR(40) PRIMARY KEY, actor VARCHAR(256) NOT NULL, all_scope BOOLEAN DEFAULT FALSE NOT NULL, scope VARCHAR(16) NOT NULL, package_id VARCHAR(64), target VARCHAR(1024), environment VARCHAR(128), state VARCHAR(16) NOT NULL, phase VARCHAR(64), request_id VARCHAR(128) NOT NULL, submitted_at VARCHAR(40) NOT NULL, started_at VARCHAR(40), finished_at VARCHAR(40), queue_elapsed_ms BIGINT, execution_elapsed_ms BIGINT, total_elapsed_ms BIGINT, source_revision VARCHAR(128), plan_json CLOB NOT NULL, report_json CLOB, error_code VARCHAR(80), error_message VARCHAR(512), record_bytes BIGINT NOT NULL)");
            statement.execute("INSERT INTO validation_records(validation_id,actor,all_scope,scope,state,request_id,submitted_at,plan_json,report_json,record_bytes) VALUES('"+id+"','alice',FALSE,'package','COMPLETED','legacy-pass','2026-11-14T10:00:00Z','{}','{\"overallStatus\":\"PASS\"}',42)");
            statement.execute("INSERT INTO validation_records(validation_id,actor,all_scope,scope,state,request_id,submitted_at,plan_json,report_json,record_bytes) VALUES('"+malformedId+"','alice',FALSE,'package','COMPLETED','legacy-bad','2026-11-14T10:00:01Z','{}','not-json',9)");
        }

        try(JobStore upgraded=new JobStore(upgradeConfig)) {
            List<Map<String,Object>> summaries=upgraded.validationRecordSummaries("alice",10);
            assertNull(summaries.stream().filter(row->malformedId.equals(row.get("validationId")))
                    .findFirst().orElseThrow().get("overallStatus"));
            assertEquals("PASS",summaries.stream().filter(row->id.equals(row.get("validationId"))).findFirst().orElseThrow().get("overallStatus"));
        }
        try(JobStore reopened=new JobStore(upgradeConfig)) {
            assertEquals("PASS",reopened.validationRecordSummaries("alice",10).stream()
                    .filter(row->id.equals(row.get("validationId"))).findFirst().orElseThrow().get("overallStatus"));
            assertNull(reopened.validationRecordSummaries("alice",10).stream()
                    .filter(row->malformedId.equals(row.get("validationId"))).findFirst().orElseThrow().get("overallStatus"));
        }
    }

    @Test void perRecordDefaultFitsWhenAnOperatorLowersTheAggregateLimit() throws Exception {
        ServerConfig config=config("small-total","    maxTotalSizeMb: 1\n");
        assertEquals(1024*1024,config.validationRecords.maxRecordBytes);
        assertEquals(1024L*1024L,config.validationRecords.maxTotalBytes);
    }

    @Test void persistsOwnerScopedRecordsAndMarksActiveRowsInterruptedOnServerRestart() throws Exception {
        ServerConfig config=config("restart",null);
        String id="V0123456789ABCDEF0123456789ABCDEF";
        try(JobStore store=new JobStore(config)) {
            create(store,config,id,"alice","{\"command\":\"validate\",\"targetKind\":\"config\"}",Instant.parse("2026-11-14T10:00:00Z"));
            assertTrue(store.startValidationRecord(id,"2026-11-14T10:00:01Z",100));
            assertNull(store.validationRecord(id,"bob"));
            assertEquals(1,store.validationRecords("alice",null,null,false,null,null,10).size());
            assertTrue(store.validationRecords("bob",null,null,false,null,null,10).isEmpty());
        }

        try(ServerRuntime restarted=new ServerRuntime(config,null)) {
            Map<String,Object> recovered=restarted.store.validationRecord(id,"alice");
            assertEquals("INTERRUPTED",recovered.get("status"));
            assertEquals("ATT-SERVER-VALIDATION-INTERRUPTED",recovered.get("errorCode"));
            assertNull(restarted.store.validationRecord(id,"bob"));
            assertTrue(restarted.store.validationRecords("bob",null,null,false,null,null,10).isEmpty());
        }
    }

    @Test void validationListsAndShellSummariesExcludeLargePlanAndReportClobs() throws Exception {
        ServerConfig config=config("large-validation-records","    maxTotalSizeMb: 4\n    maxRecordBytes: 3145728\n");
        String id="VAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        Instant now=Instant.parse("2026-11-14T10:00:00Z");
        String plan="{\"command\":\"validate\",\"padding\":\""+"p".repeat(300_000)+"\"}";
        String report="{\"payload\":\""+"r".repeat(600_000)+"\"}";
        try(JobStore store=new JobStore(config)) {
            create(store,config,id,"alice",plan,now);
            assertTrue(store.startValidationRecord(id,now.plusSeconds(1).toString(),10));
            assertTrue(store.finishValidationRecord(id,"COMPLETED",now.plusSeconds(2).toString(),10,20,30,
                    report,null,null,config.validationRecords));

            Map<String,Object> listed=store.validationRecords("alice",null,null,false,null,null,10).get(0);
            assertEquals(id,listed.get("validationId"));
            assertFalse(listed.containsKey("report"));
            assertFalse(listed.containsKey("planJson"));
            assertFalse(listed.containsKey("recordBytes"));

            Map<String,Object> summary=store.validationRecordSummaries("alice",1).get(0);
            assertEquals(id,summary.get("validationId"));
            assertFalse(summary.containsKey("report"));
            assertFalse(summary.containsKey("planJson"));

            Map<String,Object> detail=store.validationRecord(id,"alice");
            assertEquals("r".repeat(600_000),((Map<?,?>)detail.get("report")).get("payload"));
            assertEquals(plan.length(),((String)detail.get("planJson")).length());
        }
    }

    @Test void exactContextListLookupSeparatesPackageEnvironmentAndTarget() throws Exception {
        ServerConfig config=config("validation-context-list",null);
        Instant now=Instant.parse("2026-11-14T10:00:00Z");
        String sit="V0123456789ABCDEF0123456789ABCDEF",uat="VFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF";
        try(JobStore store=new JobStore(config)) {
            store.createValidationRecord(sit,"alice",false,"template","payments","tpl-a","SIT","request-364",now.toString(),
                    "{\"command\":\"validate\"}",config.validationRecords,now);
            store.createValidationRecord(uat,"alice",false,"template","payments","tpl-a","UAT","request-364",now.plusSeconds(1).toString(),
                    "{\"command\":\"validate\"}",config.validationRecords,now.plusSeconds(1));
            List<Map<String,Object>> exact=store.validationRecords("alice","payments","template","tpl-a","SIT",true,
                    false,null,null,1);
            assertEquals(1,exact.size());
            assertEquals(sit,exact.get(0).get("validationId"));
            assertEquals("SIT",exact.get(0).get("environment"));
        }
    }

    @Test void validationHistoryFiltersByPackageEnvironmentAndLifecycleAndPaginates() throws Exception {
        ServerConfig config=config("validation-history-filters",null);
        Instant now=Instant.parse("2026-11-14T10:00:00Z");
        String completedSit="V11111111111111111111111111111111";
        String runningSit="V22222222222222222222222222222222";
        String completedUat="V33333333333333333333333333333333";
        String otherPackage="V44444444444444444444444444444444";
        try(JobStore store=new JobStore(config)) {
            store.createValidationRecord(completedSit,"alice",false,"package","payments","payments","SIT","request-history",now.toString(),"{}",config.validationRecords,now);
            assertTrue(store.startValidationRecord(completedSit,now.plusSeconds(4).toString(),1));
            assertTrue(store.finishValidationRecord(completedSit,"COMPLETED",now.plusSeconds(5).toString(),1,1,2,
                    "{\"overallStatus\":\"PASS\"}","PASS",null,null,config.validationRecords));

            store.createValidationRecord(completedUat,"alice",false,"package","payments","payments","UAT","request-history",now.plusSeconds(2).toString(),"{}",config.validationRecords,now.plusSeconds(2));
            assertTrue(store.startValidationRecord(completedUat,now.plusSeconds(4).toString(),1));
            assertTrue(store.finishValidationRecord(completedUat,"COMPLETED",now.plusSeconds(5).toString(),1,1,2,
                    "{\"overallStatus\":\"PASS\"}","PASS",null,null,config.validationRecords));

            store.createValidationRecord(otherPackage,"alice",false,"package","ledger","ledger","SIT","request-history",now.plusSeconds(3).toString(),"{}",config.validationRecords,now.plusSeconds(3));
            assertTrue(store.startValidationRecord(otherPackage,now.plusSeconds(4).toString(),1));
            assertTrue(store.finishValidationRecord(otherPackage,"COMPLETED",now.plusSeconds(5).toString(),1,1,2,
                    "{\"overallStatus\":\"PASS\"}","PASS",null,null,config.validationRecords));

            store.createValidationRecord(runningSit,"alice",false,"package","payments","payments","SIT","request-history",now.plusSeconds(1).toString(),"{}",config.validationRecords,now.plusSeconds(1));
            assertTrue(store.startValidationRecord(runningSit,now.plusSeconds(6).toString(),1));

            List<Map<String,Object>> sitCompleted=store.validationRecords("alice","payments",null,null,"SIT","COMPLETED",false,false,null,null,10);
            assertEquals(List.of(completedSit),sitCompleted.stream().map(row->(String)row.get("validationId")).toList());
            List<Map<String,Object>> uat=store.validationRecords("alice","payments",null,null,"UAT",null,false,false,null,null,10);
            assertEquals(List.of(completedUat),uat.stream().map(row->(String)row.get("validationId")).toList());

            List<Map<String,Object>> firstPage=store.validationRecords("alice","payments",null,null,null,null,false,false,null,null,2);
            assertEquals(List.of(completedUat,runningSit),firstPage.stream().map(row->(String)row.get("validationId")).toList());
            Map<String,Object> last=firstPage.get(firstPage.size()-1);
            List<Map<String,Object>> secondPage=store.validationRecords("alice","payments",null,null,null,null,false,false,
                    (String)last.get("submittedAt"),(String)last.get("validationId"),2);
            assertEquals(List.of(completedSit),secondPage.stream().map(row->(String)row.get("validationId")).toList());
        }
    }

    @Test void completionCannotGrowDurableRecordsPastConfiguredAggregateBytes() throws Exception {
        ServerConfig config=config("quota","    maxTotalSizeMb: 1\n    maxRecordBytes: 1048576\n");
        String first="V0123456789ABCDEF0123456789ABCDEF";
        String second="VFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF";
        Instant now=Instant.parse("2026-11-14T10:00:00Z");
        try(JobStore store=new JobStore(config)) {
            create(store,config,first,"alice","x".repeat(700_000),now);
            create(store,config,second,"alice","package","payments","y".repeat(300_000),now.plusSeconds(1));
            assertTrue(store.startValidationRecord(first,now.plusSeconds(2).toString(),10));
            assertTrue(store.finishValidationRecord(first,"COMPLETED",now.plusSeconds(3).toString(),10,20,30,
                    "z".repeat(200_000),null,null,config.validationRecords));

            Map<String,Object> result=store.validationRecord(first,"alice");
            assertEquals("FAILED",result.get("status"));
            assertEquals("ATT-SERVER-VALIDATION-RECORD-CAPACITY",result.get("errorCode"));
            assertFalse(result.containsKey("report"));
            long retainedBytes=((Number)result.get("recordBytes")).longValue()
                    +((Number)store.validationRecord(second,"alice").get("recordBytes")).longValue();
            assertTrue(retainedBytes<=config.validationRecords.maxTotalBytes,Long.toString(retainedBytes));
        }
    }

    @Test void completionCannotGrowAnIndividualRecordPastConfiguredBytes() throws Exception {
        ServerConfig config=config("record-cap","    maxTotalSizeMb: 2\n    maxRecordBytes: 1048576\n");
        String id="VAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        Instant now=Instant.parse("2026-11-14T10:00:00Z");
        try(JobStore store=new JobStore(config)) {
            create(store,config,id,"alice","x".repeat(900_000),now);
            assertTrue(store.startValidationRecord(id,now.plusSeconds(1).toString(),10));
            assertTrue(store.finishValidationRecord(id,"COMPLETED",now.plusSeconds(2).toString(),10,20,30,
                    "z".repeat(200_000),null,null,config.validationRecords));

            Map<String,Object> result=store.validationRecord(id,"alice");
            assertEquals("FAILED",result.get("status"));
            assertEquals("ATT-SERVER-VALIDATION-RECORD-LIMIT",result.get("errorCode"));
            assertFalse(result.containsKey("report"));
            assertEquals(900_512L,((Number)result.get("recordBytes")).longValue());
        }
    }
}
