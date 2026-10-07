package att.api;

/** Receives structured execution events. Service operations never choose a console stream. */
public interface ExecutionEventListener {
    void onEvent(ExecutionEvent event);
}
