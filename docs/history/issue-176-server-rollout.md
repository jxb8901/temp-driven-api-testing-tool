<!-- att-doc-status: historical release=4.1.0 issue=176 -->

# Issue #176 rollout checklist

## Issue #176 rollout checklist

1. Back up the Server `dataDir` and retain the current WAR and deployment configuration for rollback.
2. Keep Tomcat authentication enabled and terminate public traffic with HTTPS. Confirm the package roots and Server data directory are separate and writable only by the service account.
3. Set `ATT_HOME` or `-Datt.home` to the matching Local installation so Worker processes can use `$ATT_HOME/lib/*`. Tomcat can deploy the WAR packed; `workers.libraryDirs` remains additive for external JDBC, MQ, and other libraries. Confirm `/api/v1/health` and `/api/v1/version` through the deployed Tomcat context path.
4. Begin with `server.inlineLoad.enabled: false`. Verify the Package and Configuration Explorers against a non-production package. Enable browser Load only in an approved environment and set caps for expected workload count, targets, users or rate, concurrency, and total envelope duration.
5. Monitor Worker and Load queue capacity, job outcomes, cancellation, and retained artifacts. The package remains read-only; rollback consists of disabling inline Load and restoring the prior WAR/config if needed.

This checklist describes deployment steps; it does not substitute for the P6 security, browser, compatibility, packaged-WAR, and hosted-CI acceptance evidence.
