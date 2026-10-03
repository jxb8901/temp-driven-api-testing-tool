package att.load;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Lazily grows a bounded Load worker pool and hands work directly to available workers. */
final class LoadWorkerPool {
    private LoadWorkerPool() { }

    static int coreSize(int maximum) {
        return Math.min(maximum, Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())));
    }

    static ThreadPoolExecutor create(int core, int maximum, ThreadFactory factory) {
        return new ThreadPoolExecutor(core, maximum, 30L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), new ThreadPoolExecutor.AbortPolicy()) {
            @Override public void execute(Runnable command) {
                try { super.execute(command); }
                catch (RejectedExecutionException saturated) {
                    handOffWhenWorkerIsReady(command, this, saturated);
                }
            }
        };
    }

    private static void handOffWhenWorkerIsReady(Runnable command, ThreadPoolExecutor executor,
                                                  RejectedExecutionException original) {
        while (!executor.isShutdown()) {
            try {
                if (executor.getQueue().offer(command, 100L, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while handing work to a Load worker", interrupted);
            }
        }
        throw original;
    }
}
