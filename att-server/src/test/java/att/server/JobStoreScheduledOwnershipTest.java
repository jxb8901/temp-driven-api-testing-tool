package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JobStoreScheduledOwnershipTest {
    @TempDir Path temp;

    @Test void activeUsersShareJobsWhileAdministratorsHaveNoOperationalAccess() throws Exception {
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
            store.createAuthUser("erin", "Erin", "x", "USER", "e");
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

            assertFalse(store.ownsJob("J0000000000000001", "alice"));
            assertFalse(store.ownsJob("J0000000000000001", "carol"));
            assertTrue(store.ownsJob("J0000000000000001", "bob"));
            assertTrue(store.ownsJob("J0000000000000001", "erin"));
            assertFalse(store.ownsJob("J0000000000000001", "dana"));
            assertTrue(store.ownsJob("J0000000000000002", "bob"));
            assertTrue(store.ownsJob("J0000000000000002", "erin"));
            assertFalse(store.ownsJob("J0000000000000002", "carol"));
            assertTrue(store.ownsJob("J0000000000000003", "bob"));
            assertFalse(store.ownsJob("J0000000000000003", "alice"));

            assertEquals(3, store.listOwned(100, "bob").size());
            assertEquals(3, store.listOwned(100, "erin").size());
            assertTrue(store.listOwned(100, "alice").isEmpty());
            assertTrue(store.listOwned(100, "carol").isEmpty());
            assertTrue(store.listOwned(100, "dana").isEmpty());
        }
    }
}
