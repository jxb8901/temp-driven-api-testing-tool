package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JobStoreScheduledOwnershipTest {
    @TempDir Path temp;

    @Test void scheduledJobsAreReadableByTheirCreatorAndLocalAdministratorsOnly() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        Path allowed = Files.createDirectories(temp.resolve("packages"));
        Path packageRoot = Files.createDirectories(allowed.resolve("p"));
        Path configFile = temp.resolve("server.yaml");
        Files.writeString(configFile,
                "server:\n  dataDir: '" + data + "'\nworkers: {}\npackages:\n  allowedRoots:\n    - '" + allowed + "'\n  entries:\n    p: '" + packageRoot + "'\n");
        ServerConfig config = ServerConfig.load(configFile);

        try (JobStore store = new JobStore(config)) {
            store.createBootstrapAdmin("alice", "Alice", "x", "a");
            store.createAuthUser("bob", "Bob", "x", "USER", "b");
            store.createAuthUser("carol", "Carol", "x", "ADMIN", "c");
            store.createAuthUser("dana", "Dana", "x", "ADMIN", "d");
            store.setAuthActive("dana", false);

            LoadSchedule schedule = new LoadSchedule();
            schedule.id = "S1";
            schedule.createdBy = "alice";
            schedule.jobType = "debug";
            schedule.updatedAt = "2026-10-16T00:00:00Z";
            store.saveSchedule(schedule);

            store.insert(new Job("J0000000000000001", "debug", "p", "debug-schedule:S1", null, null), "{}");
            store.insert(new Job("J0000000000000002", "load", "p", "load-schedule:S1", null, null), "{}");
            store.insert(new Job("J0000000000000003", "debug", "p", "alice", null, null), "{}");

            assertTrue(store.ownsJob("J0000000000000001", "alice"));
            assertTrue(store.ownsJob("J0000000000000001", "carol"));
            assertFalse(store.ownsJob("J0000000000000001", "bob"));
            assertFalse(store.ownsJob("J0000000000000001", "dana"));
            assertTrue(store.ownsJob("J0000000000000002", "alice"));
            assertTrue(store.ownsJob("J0000000000000002", "carol"));
            assertFalse(store.ownsJob("J0000000000000002", "bob"));
            assertTrue(store.ownsJob("J0000000000000003", "alice"));
            assertFalse(store.ownsJob("J0000000000000003", "carol"));

            assertEquals(3, store.listOwned(100, "alice").size());
            assertEquals(2, store.listOwned(100, "carol").size());
            assertTrue(store.listOwned(100, "dana").isEmpty());
            assertTrue(store.listOwned(100, "bob").isEmpty());
        }
    }
}
