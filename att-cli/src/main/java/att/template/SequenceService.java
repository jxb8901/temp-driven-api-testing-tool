package att.template;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe sequence counters owned by one ATT run. */
public final class SequenceService {
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<String, AtomicLong>();

    public Object next(String name, Integer width) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("seq.next sequence name must not be blank");
        if (width != null && (width.intValue() < 1 || width.intValue() > 1000))
            throw new IllegalArgumentException("seq.next width must be from 1 to 1000");
        AtomicLong counter = counters.get(name);
        if (counter == null) {
            AtomicLong created = new AtomicLong();
            AtomicLong previous = counters.putIfAbsent(name, created);
            counter = previous == null ? created : previous;
        }
        long value = counter.incrementAndGet();
        if (value <= 0L) throw new IllegalStateException("Sequence '" + name + "' overflowed Long");
        if (width == null) return Long.valueOf(value);
        String digits = Long.toString(value);
        if (digits.length() > width.intValue()) throw new IllegalStateException("Sequence '" + name + "' exceeded width " + width);
        StringBuilder padded = new StringBuilder(width.intValue());
        for (int i = digits.length(); i < width.intValue(); i++) padded.append('0');
        return padded.append(digits).toString();
    }
}
