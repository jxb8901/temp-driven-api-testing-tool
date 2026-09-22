package att.load;

/** Common scheduler contract; scheduler timing is separate from iteration execution. */
public interface LoadScheduler extends AutoCloseable {
    LoadRunResult run() throws Exception;
    void cancel();
    @Override void close();
}
