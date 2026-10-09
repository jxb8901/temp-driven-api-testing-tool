package att.remote.sse;

import att.remote.RemoteException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/** Bounded UTF-8 SSE frame reader. Comments and unknown fields follow the SSE framing rules. */
public final class SseReader {
    private final BufferedReader reader;
    public SseReader(Reader reader) { this.reader=new BufferedReader(reader); }
    public SseEvent next() throws IOException, RemoteException {
        String id=null,event="message"; StringBuilder data=new StringBuilder(); int total=0;
        while(true) {
            String line=boundedLine();
            if(line==null) return data.length()==0?null:new SseEvent(id,event,data.toString());
            if(line.isEmpty()) {
                if(data.length()==0 && id==null) { event="message"; continue; }
                if(data.length()>0) data.setLength(data.length()-1);
                return new SseEvent(id,event,data.toString());
            }
            if(line.charAt(0)==':') continue;
            int colon=line.indexOf(':'); String field=colon<0?line:line.substring(0,colon);
            String value=colon<0?"":line.substring(colon+1); if(value.startsWith(" "))value=value.substring(1);
            if("id".equals(field)) { if(value.indexOf('\0')<0) id=value; }
            else if("event".equals(field)) event=value;
            else if("data".equals(field)) { total+=value.length()+1; if(total>1024*1024)throw new RemoteException("ATT Server SSE event exceeded the 1 MiB limit"); data.append(value).append('\n'); }
        }
    }
    private String boundedLine() throws IOException, RemoteException {
        StringBuilder line=new StringBuilder(); int c;
        while((c=reader.read())!=-1) { if(c=='\n')break; if(c!='\r') { if(line.length()>=256*1024)throw new RemoteException("ATT Server SSE line exceeded the 256 KiB limit"); line.append((char)c); } }
        if(c==-1 && line.length()==0)return null;
        return line.toString();
    }
}
