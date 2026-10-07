package att.server.api;

/** Stable error envelope returned by the v1 REST API. */
public final class ApiError {
    public Error error;
    public ApiError() { }
    public static final class Error {
        public String code;
        public String summary;
        public String detail;
        public String requestId;
        public Error() { }
    }
}
