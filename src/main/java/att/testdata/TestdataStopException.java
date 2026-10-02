package att.testdata;

/** Internal scheduler signal raised when a Load selection policy reaches exhaustion: stop. */
public final class TestdataStopException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TestdataStopException(String message) { super(message); }
}
