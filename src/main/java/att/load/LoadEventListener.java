package att.load;

/** Receives bounded load events; listeners must not retain full Context objects. */
public interface LoadEventListener {
    void onEvent(LoadEvent event);
}
