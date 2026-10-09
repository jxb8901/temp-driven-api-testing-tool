package att.remote.sse;

public final class SseEvent {
    public final String id, event, data;
    public SseEvent(String id, String event, String data) { this.id=id; this.event=event; this.data=data; }
}
