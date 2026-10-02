package att.testdata;

/** Signals that scheduler shutdown ended an ordered exhaustion wait before it was conclusive. */
public final class TestdataSelectionDeferredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TestdataSelectionDeferredException() {
        super("Testdata exhaustion decision was deferred because the Load workload is ending");
    }
}
