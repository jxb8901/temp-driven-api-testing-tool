package att.load;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SplitQuotaCancellationTest {
    @TempDir Path temp;
    @Test void bothSchedulersReleaseReservationsWhenActiveExecutionsAreCancelledOrThrow() throws Exception {
        att.TestSchemas.install(temp);
        for(boolean arrival:new boolean[]{false,true}) {
            Path file=temp.resolve(arrival?"arrival.yaml":"closed.yaml");
            Files.write(file,("schemaVersion: att-load/v1.7\nworkloads:\n- id: cancellation\n  target: {type: template, id: T}\n"
                    +"  load: {"+(arrival?"arrivalRate: 1000/s, maxConcurrent: 8, overloadPolicy: drop":"users: 8")+", duration: 60s}\n"
                    +"evidence: {mode: all, maxSuccessSamples: 8, maxFailureSamples: 8}\n").getBytes("UTF-8"));
            LoadScenario scenario=new LoadScenarioLoader(temp).load(file);
            LoadEvidenceStore store=new LoadEvidenceStore(LoadEvidencePolicy.from(scenario));
            CountDownLatch entered=new CountDownLatch(1);
            LoadIterationRunner runner=request->{
                assertTrue(request.retainSuccessEvidence());entered.countDown();
                try {new CountDownLatch(1).await();}
                catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IllegalStateException("cancelled",cancelled);}
                throw new AssertionError("Execution should be interrupted");
            };
            LoadScheduler scheduler=arrival
                    ?new FixedArrivalRateScheduler(scenario.forWorkload(scenario.workload()),runner,"arrival-cancel",store::onEvent,
                        LoadSchedulerTiming.system(),null,store,temp.resolve("arrival-output"),null)
                    :new ClosedVuScheduler(scenario.forWorkload(scenario.workload()),runner,"closed-cancel",store::onEvent,
                        LoadSchedulerTiming.system(),store,temp.resolve("closed-output"),null);
            ExecutorService controller=Executors.newSingleThreadExecutor();
            try {
                Future<LoadRunResult> result=controller.submit(scheduler::run);
                assertTrue(entered.await(5,TimeUnit.SECONDS));scheduler.cancel();result.get(10,TimeUnit.SECONDS);
                assertEquals(0,store.statistics().get("reservedSuccessCount"));
                assertEquals(0,store.statistics().get("reservedFailureCount"));
                assertEquals(0,store.statistics().get("retainedSuccessCount"));
                assertEquals(0,store.statistics().get("retainedFailureCount"));
                assertTrue(((Number)store.statistics().get("skippedMissingEvidenceCount")).longValue()>0);
            } finally {scheduler.close();controller.shutdownNow();}
        }
    }
}
