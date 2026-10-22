#!/usr/bin/env python3
"""Measure the real baseline/current identity paths at paced arrivals; not an end-to-end TPS benchmark.
Run after mvn -pl att-engine -am test. Uses only disposable output; never cleans retained job artifacts.
"""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = r'''
package att.load;
import att.config.*;
import java.nio.file.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
public class IdentityBenchmark {
 public static void main(String[] args) throws Exception {
  Path root=Paths.get(args[0]); int seconds=Integer.parseInt(args[1]);
  Path cfg=root.resolve("config.yaml");
  Files.write(cfg,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  FrameworkConfig config=new FrameworkConfigLoader().load(cfg,root);
  com.sun.management.ThreadMXBean threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
  com.sun.management.OperatingSystemMXBean os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
  for (int rate:new int[]{100,500,1000}) for(String mode:new String[]{"legacy-default","legacy-custom","engine-owned"}) {
   Path run=root.resolve(mode+"-"+rate).resolve("RUN");
   LoadRunResources current=new LoadRunResources(root,config);
   LegacyLoadRunResources previous=new LegacyLoadRunResources(root,config);
   if(mode.equals("engine-owned")) current.initializeExecutionNamespace(run); else previous.initializeExecutionNamespace(run);
   long gcBefore=gcCount(), gcMsBefore=gcMs(); long allocated=threads.getThreadAllocatedBytes(Thread.currentThread().getId());
   long cpu=os.getProcessCpuTime(), start=System.nanoTime(); int count=rate*seconds;
   for(int i=1;i<=count;i++) {
    long due=start+(i-1)*1000000000L/rate; long wait=due-System.nanoTime(); if(wait>0) LockSupport.parkNanos(wait);
    if(mode.equals("engine-owned")) current.nextExecutionId("RUN");
    else if(mode.equals("legacy-default")) previous.nextExecutionId("RUN");
    else {
     // Exact baseline reservation implementation, including SHA256, mkdirs and CREATE_NEW.
     IterationRequest request=new IterationRequest("RUN", "arrivalRate", "iteration-"+i, i, "STEADY", java.time.Instant.now(), null, Collections.<String,Object>emptyMap(), null).withWorkloadId("workload-"+(i%2));
     previous.reserveExecutionId("custom-"+i,request,true,run);
    }
   }
   long elapsed=System.nanoTime()-start, cpuNs=os.getProcessCpuTime()-cpu;
   long bytes=threads.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;
   current.close(); previous.close();
   long files=0; if(Files.exists(run)) try(java.util.stream.Stream<Path> paths=Files.walk(run)){files=paths.filter(Files::isRegularFile).count();}
   long cleanupStart=System.nanoTime(); if(Files.exists(run.getParent())) try(java.util.stream.Stream<Path> paths=Files.walk(run.getParent())){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}
   long cleanupNs=System.nanoTime()-cleanupStart;
   System.out.println(String.format(Locale.ROOT,"{\"mode\":\"%s\",\"targetArrivalsPerSecond\":%d,\"iterations\":%d,\"elapsedSeconds\":%.6f,\"observedAllocationsPerSecond\":%.3f,\"processCpuMs\":%.3f,\"threadAllocatedBytes\":%d,\"gcCount\":%d,\"gcMs\":%d,\"filesAfterClose\":%d,\"cleanupMs\":%.3f}",mode,rate,count,elapsed/1e9,count/(elapsed/1e9),cpuNs/1e6,bytes,gcCount()-gcBefore,gcMs()-gcMsBefore,files,cleanupNs/1e6));
  }
 }
 static long gcCount(){long n=0;for(GarbageCollectorMXBean gc:ManagementFactory.getGarbageCollectorMXBeans())n+=Math.max(0,gc.getCollectionCount());return n;}
 static long gcMs(){long n=0;for(GarbageCollectorMXBean gc:ManagementFactory.getGarbageCollectorMXBeans())n+=Math.max(0,gc.getCollectionTime());return n;}
}
'''

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', default='1eedaa0dd28de1e5cbb7eac458560524e98ec302')
    parser.add_argument('--seconds',type=int,default=3)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    report=next((ROOT/'att-engine/target/surefire-reports').glob('TEST-att.load.LoadExecutionIdPatternTest.xml'))
    props={p.attrib['name']:p.attrib.get('value','') for p in ET.parse(report).findall('./properties/property')}
    cp=props['java.class.path']
    baseline=subprocess.check_output(['git','show',args.baseline+':att-engine/src/main/java/att/load/LoadRunResources.java'],cwd=ROOT,text=True)
    with tempfile.TemporaryDirectory(prefix='att-identity-') as directory:
        root=Path(directory)
        (root/'LegacyLoadRunResources.java').write_text(baseline.replace('LoadRunResources','LegacyLoadRunResources'))
        (root/'IdentityBenchmark.java').write_text(SOURCE)
        subprocess.run(['javac','-cp',cp,'-d',directory,str(root/'LegacyLoadRunResources.java'),str(root/'IdentityBenchmark.java')],check=True,cwd=ROOT)
        result=subprocess.run(['java','-cp',directory+':'+cp,'att.load.IdentityBenchmark',directory,str(args.seconds)],cwd=ROOT,text=True,capture_output=True)
        if result.returncode: raise RuntimeError(result.stderr)
        data={'baseline':args.baseline,'scope':'Identity allocation/reservation only; paced single-thread arrivals, alternating workload labels, no target execution. Includes JVM warmup/order effects; CPU/allocations are not end-to-end throughput claims. Cleanup only removes this harness disposable directories.','secondsPerCondition':args.seconds,'measurements':[json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]}
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(data,indent=2)+'\n')
        print(args.output)
if __name__=='__main__':main()
