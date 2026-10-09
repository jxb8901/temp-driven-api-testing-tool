package att.remote;

/** Safe user-facing client/protocol failure. */
public final class RemoteException extends Exception {
    private final int exitCode;
    private final boolean retryableTransport;
    public RemoteException(String message) { this(message,4,null); }
    public RemoteException(String message, Throwable cause) { this(message,4,cause); }
    public RemoteException(String message,int exitCode) { this(message,exitCode,null); }
    private RemoteException(String message,int exitCode,Throwable cause) { this(message,exitCode,cause,false); }
    private RemoteException(String message,int exitCode,Throwable cause,boolean retryableTransport) { super(message,cause);this.exitCode=exitCode;this.retryableTransport=retryableTransport; }
    public static RemoteException retryableTransport(String message,Throwable cause) { return new RemoteException(message,4,cause,true); }
    public static RemoteException retryableHttp(String message,int exitCode) { return new RemoteException(message,exitCode,null,true); }
    public int exitCode(){return exitCode;}
    public boolean isRetryableTransport(){return retryableTransport;}
}
