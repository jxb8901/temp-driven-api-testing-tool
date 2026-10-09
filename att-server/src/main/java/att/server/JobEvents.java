package att.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Retained, job-local event journal. Client delivery runs independently from the Worker consumer. */
final class JobEvents {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path file;
    private final int capacity;
    private final AtomicLong sequence=new AtomicLong();
    private final Deque<Map<String,Object>> events=new ArrayDeque<>();
    private final CopyOnWriteArrayList<Consumer<Map<String,Object>>> listeners=new CopyOnWriteArrayList<>();
    JobEvents(Path file,int capacity) throws Exception {
        this.file=file;this.capacity=capacity;Files.createDirectories(file.getParent());
        if(Files.isRegularFile(file)) try(BufferedReader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){String line;while((line=reader.readLine())!=null){try{@SuppressWarnings("unchecked") Map<String,Object> e=JSON.readValue(line,Map.class);sequence.set(Math.max(sequence.get(),((Number)e.get("id")).longValue()));events.addLast(e);}catch(Exception ignored){}}
        while(events.size()>capacity)events.removeFirst();
        }
    }
    synchronized Map<String,Object> append(String type,Map<String,?> data) throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>(data);if(JSON.writeValueAsBytes(payload).length>65536){payload.clear();payload.put("truncated",true);payload.put("summary","Event payload exceeded the retained event size limit");}
        Map<String,Object> event=new LinkedHashMap<>();event.put("id",sequence.incrementAndGet());event.put("event",type);event.put("data",payload);
        Files.writeString(file,JSON.writeValueAsString(event)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        events.addLast(event);while(events.size()>capacity)events.removeFirst();
        if(sequence.get()%128==0)rewrite();
        for(Consumer<Map<String,Object>> listener:listeners)try{listener.accept(event);}catch(RuntimeException ignored){}
        return event;
    }
    synchronized List<Map<String,Object>> after(long id){return after(id,Integer.MAX_VALUE);}
    synchronized List<Map<String,Object>> after(long id,int limit){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> e:events)if(((Number)e.get("id")).longValue()>id){result.add(e);if(result.size()>=limit)break;}return result;}
    synchronized boolean hasMore(long id){return !events.isEmpty()&&((Number)events.getLast().get("id")).longValue()>id;}
    long latest(){return sequence.get();}
    AutoCloseable listen(Consumer<Map<String,Object>> listener){listeners.add(listener);return ()->listeners.remove(listener);}
    boolean isClosed(){return false;}
    private synchronized void rewrite() throws Exception {Path temp=file.resolveSibling(file.getFileName()+".tmp");StringBuilder b=new StringBuilder();for(Map<String,Object> e:events)b.append(JSON.writeValueAsString(e)).append('\n');Files.writeString(temp,b.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);try{Files.move(temp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}
}
