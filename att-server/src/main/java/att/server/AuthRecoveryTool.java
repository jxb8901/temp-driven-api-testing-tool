package att.server;

import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Offline recovery for an existing local ADMIN when no administrator can sign in. */
public final class AuthRecoveryTool {
    private AuthRecoveryTool() {}

    public static void main(String[] args) {
        try {
            if(args.length!=0)throw new IllegalArgumentException("This tool takes no command-line arguments");
            Console console=System.console();
            if(console==null)throw new IllegalStateException("Run this command from an interactive terminal");
            console.printf("ATT local administrator recovery%n");
            console.printf("Stop Tomcat first. This updates one existing ADMIN, reactivates it, and revokes its sessions.%n");
            console.printf("It does not create accounts or reopen the one-time bootstrap.%n%n");

            ServerConfig config=ServerConfig.load(ServerConfig.configPath());
            requireExistingLocalDatabase(config);
            if(!"local".equals(config.authMode))throw new IllegalStateException("The configured authentication mode is not local");
            String entered=console.readLine("Existing ADMIN login ID: ");
            if(entered==null)throw new IllegalStateException("No account was selected");
            String login=AuthService.normalize(entered);
            String confirmation=console.readLine("Type RECOVER ADMIN to continue: ");
            if(!"RECOVER ADMIN".equals(confirmation))throw new IllegalStateException("Recovery was not confirmed");

            char[] first=console.readPassword("New password (12-1024 characters): ");
            char[] second=console.readPassword("Confirm new password: ");
            try {
                if(first==null||second==null||!Arrays.equals(first,second))
                    throw new IllegalArgumentException("The passwords do not match");
                recover(config,login,first);
            } finally {
                if(first!=null)Arrays.fill(first,'\0');
                if(second!=null)Arrays.fill(second,'\0');
            }
            console.printf("Existing ADMIN account recovered. Start Tomcat and sign in with the new password.%n");
        } catch(Exception failure) {
            System.err.println("Administrator recovery failed: "+failure.getMessage());
            System.exit(1);
        }
    }

    static void recover(ServerConfig config,String loginId,char[] password) throws Exception {
        if(config==null||!"local".equals(config.authMode))throw new IllegalArgumentException("Recovery requires server.auth.mode: local");
        requireExistingLocalDatabase(config);
        String login=AuthService.normalize(loginId);
        String passwordHash=AuthService.hash(password);
        try(JobStore store=new JobStore(config)) {
            store.recoverExistingAdmin(login,passwordHash,AuthService.recoveryCsrfToken());
        }
    }

    private static void requireExistingLocalDatabase(ServerConfig config) {
        Path database=config.dataDir.resolve("db/att-server.mv.db");
        if(!Files.isRegularFile(database)||!Files.isReadable(database)||!Files.isWritable(database))
            throw new IllegalStateException("The existing writable ATT Server database was not found under server.dataDir");
    }
}
