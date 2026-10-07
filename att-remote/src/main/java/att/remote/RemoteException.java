package att.remote;

/** Safe user-facing client/protocol failure. */
public final class RemoteException extends Exception {
    private final int exitCode;
    public RemoteException(String message) { this(message,4,null); }
    public RemoteException(String message, Throwable cause) { this(message,4,cause); }
    public RemoteException(String message,int exitCode) { this(message,exitCode,null); }
    private RemoteException(String message,int exitCode,Throwable cause) { super(message,cause);this.exitCode=exitCode; }
    public int exitCode(){return exitCode;}
}
