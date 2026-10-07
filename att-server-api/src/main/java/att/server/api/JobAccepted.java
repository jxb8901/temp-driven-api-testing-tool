package att.server.api;

/** Public subset returned when a job has been accepted. */
public final class JobAccepted {
    public String jobId;
    public String command;
    public String packageId;
    public String status;
    public String createdAt;
    public JobAccepted() { }
}
