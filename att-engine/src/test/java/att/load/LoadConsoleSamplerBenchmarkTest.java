package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opt-in single-thread sampler cost measurement; this is not a real Worker soak. */
class LoadConsoleSamplerBenchmarkTest {
    @Test
    @EnabledIfSystemProperty(named = "att.load.samplerBenchmark", matches = "true")
    void measuresPerEventAndCoalescedSnapshotCost() {
        final int iterations=1_000_000;
        LoadEvent event=LoadEvent.completed("benchmark","arrivalRate","STEADY","iteration","VU",1,1L,2L,5L,ResultStatus.PASS)
                .withWorkloadIdentity("checkout","template","TEST");
        LoadConsoleSampler sampler=new LoadConsoleSampler("benchmark",TimeUnit.HOURS.toMillis(1),1000L);
        for(int i=0;i<100_000;i++)sampler.onEvent(event);
        java.lang.management.ThreadMXBean base=ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean allocations=base instanceof com.sun.management.ThreadMXBean?(com.sun.management.ThreadMXBean)base:null;
        boolean measurable=allocations!=null&&allocations.isThreadAllocatedMemorySupported();
        if(measurable&&!allocations.isThreadAllocatedMemoryEnabled())allocations.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId();long bytesBefore=measurable?allocations.getThreadAllocatedBytes(thread):0L;
        long start=System.nanoTime();
        for(int i=0;i<iterations;i++) {
            sampler.onEvent(event);
            if(i%500==0)sampler.snapshot(); // 100 TPS, one snapshot per five seconds.
        }
        long nanos=System.nanoTime()-start;long bytes=measurable?allocations.getThreadAllocatedBytes(thread)-bytesBefore:-1L;
        @SuppressWarnings("unchecked") Map<String,Object> counts=(Map<String,Object>)sampler.snapshot().get("counts");
        assertEquals(1_100_000L,counts.get("completed"));
        sampler.finish("PASS");
        System.out.println("LOAD_SAMPLER_BENCHMARK events="+iterations+" snapshots="+(iterations/500)+" nanosPerEvent="+(nanos/iterations)+" allocatedBytesPerEvent="+(measurable?(bytes/(double)iterations):"unavailable"));
    }
}
