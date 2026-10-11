package att.server.api;

/** Submits a Server-issued Load draft without resending mutable scenario values. */
public final class LoadDraftSubmitRequest {
    public String packageId;
    public String draftId;
}
