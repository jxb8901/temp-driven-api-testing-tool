package att.server.api;

import com.fasterxml.jackson.databind.JsonNode;

/** One retained Server-sent event payload. */
public final class ServerEvent {
    public long id;
    public String event;
    public JsonNode data;
    public ServerEvent() { }
}
