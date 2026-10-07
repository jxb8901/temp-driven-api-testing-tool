package att.remote;

import java.util.ArrayList;
import java.util.List;

/** Remote-only option model, deliberately independent from the local CliOptions parser. */
public final class RemoteOptions {
    public final String server, format;
    public final List<String> command;
    private RemoteOptions(String server,String format,List<String> command){this.server=server;this.format=format;this.command=command;}
    public static RemoteOptions parse(String[] args) {
        String server=null,format="human";List<String> command=new ArrayList<String>();
        for(int i=0;i<args.length;i++) {
            if("--server".equals(args[i])) { if(++i>=args.length)throw new IllegalArgumentException("--server requires a profile name");server=args[i]; }
            else if("--format".equals(args[i])) { if(++i>=args.length)throw new IllegalArgumentException("--format requires human or json");format=args[i]; }
            else command.add(args[i]);
        }
        if(!"human".equals(format)&&!"json".equals(format))throw new IllegalArgumentException("--format must be human or json");
        return new RemoteOptions(server,format,command);
    }
}
