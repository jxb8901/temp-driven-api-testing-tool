package att.load;

/** Receives bounded load events; listeners must not retain full Context objects. */
public interface LoadEventListener {
    void onEvent(LoadEvent event);

    /** Bounded, secret-free lifecycle event emitted before LOAD iterations begin. */
    default void onPreparationEvent(java.util.Map<String, Object> event) { }
}
