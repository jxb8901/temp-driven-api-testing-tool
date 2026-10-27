<!-- att-doc-status: current release=4.2.5 -->

# ATT Server deployment

## Prepare the deployment

1. Extract the Local archive into the installation root and set `ATT_HOME` to that directory. When upgrading, remove the previous `lib/att-local-*.jar` before extracting the new archive so `ATT_HOME/lib` contains one runtime. Runtime configuration is shared from `$ATT_HOME/config/config.yaml`; trusted schemas are bundled with the ATT runtime. A Package contains business resources such as `testcase/`, `templates/`, and package-local scripts, with no required `config/` or `schemas/` directory. `ATT_HOME/config/att-server.example.yaml` remains separate Server deployment configuration.
2. Register package roots beneath an allowed root and ensure the Tomcat service account can read them. The extracted default package can be registered directly as `$ATT_HOME/packages/default`.
3. Create `server.dataDir` before startup and make it writable only by the Tomcat service account. The sample uses `$ATT_HOME/data`; it is supported when it does not equal, contain, or sit inside any registered package root. `/var/lib/att-server` is another option. Keep it out of package content.
4. Copy `$ATT_HOME/config/att-server.example.yaml` to `$ATT_HOME/config/att-server.yaml` or a protected external path, update its absolute paths, and keep credentials out of package content. Do not replace an existing active config during an upgrade.
5. Deploy `att-server-4.2.5.war` to Tomcat 10.1+. Tomcat may run the WAR packed; isolated Workers load the matching `att-local-4.2.5.jar` from `$ATT_HOME/lib`.

The Local archive's extracted directory is `ATT_HOME`, the shared installation and deployment root. It contains launchers and the shared Worker/Engine runtime at `lib/att-local-4.2.5.jar`; install and upgrade or roll back the matching Local archive and Server WAR together. Startup verifies the Worker JAR version and embedded Schema Registry, and each Worker confirms that its registry matches the Server-validated runtime before processing requests. Keep one versioned `att-local-<version>.jar` in each `ATT_HOME/lib`. Executable package content lives beneath `packages/<id>/`; Server state lives in the separately configured `server.dataDir`. The archive includes `config/att-server.example.yaml` as a template, but never includes an active `config/att-server.yaml` or a `data/` directory.

Server config discovery checks, in order, JVM property `-Datt.server.config`, environment variable `ATT_SERVER_CONFIG`, then `$ATT_HOME/config/att-server.yaml`. For the last candidate, the Server resolves `ATT_HOME` from `-Datt.home` first and then the `ATT_HOME` environment variable. A selected config that is missing, unreadable, or invalid stops startup with its path in the diagnostic; the Server does not fall back to another source. With no explicit source or usable default candidate, startup fails with setup guidance.

Tomcat owns listeners, TLS, access logs, and container authentication. Give its service account read access to ATT_HOME and package roots, and ownership or write access to `server.dataDir`. In container-auth mode, configure `server.authorization.userRole` for active operators and `server.authorization.adminRole` for account and system administration. Operators can use packages, Run, Load, Jobs, and Schedules; administrators use Settings and account management. An account with the admin role does not gain operational API access. Local-auth mode applies the equivalent policy to active `USER` and `ADMIN` accounts. Health and version remain public. Terminate TLS before exposing BASIC credentials. The Server fails startup when its configuration, Java baseline, or package mappings are invalid.

## ATT_HOME layout and migration

A shared install can use this layout:

```text
att/                              # ATT_HOME
  att.sh
  att.bat
  lib/
  config/
    att-server.example.yaml       # Server settings template only
    config.yaml                   # shared Engine/Worker config
    tools/                        # shared Tool definitions
    dbhelpers/                    # shared helper descriptors
    mqhelpers/
    httphelpers/
    sshhelpers/
  data/                           # optional server.dataDir; Tomcat-owned
  packages/default/               # complete package root
    testcase/
    templates/
    testdata/                     # package-local descriptor and data
    tools/                        # package-local scripts
```

To migrate a flat Local installation, back it up first and extract the new archive to a staging directory. Keep one reviewed common `config/config.yaml` and move its shared Tool and helper descriptors into ATT_HOME `config/`. If former installations have different package configs, merge them deliberately; ATT does not choose or merge a package config at runtime. Do not copy old schema files into ATT_HOME or a Package: runtime validation uses the authoritative registry embedded in the matching ATT Engine JAR, and any existing `ATT_HOME/schemas/` directory is ignored. Move `testcase/`, `templates/`, `load/`, top-level `tools/`, `sql/`, and Testdata descriptors into each registered Package. Preserve package-local scripts under `packages/<id>/tools/`; these are separate from shared Tool definitions under ATT_HOME `config/tools/`. Exclude `att-server.example.yaml` and `att-server.yaml` when migrating runtime configuration. Keep any active Server config at `ATT_HOME/config/att-server.yaml` or its protected external path. Retain the old flat tree until the migrated Package passes validation and execution. Update each Server registry entry to the resulting Package root and set `ATT_HOME` or `-Datt.home` for Tomcat. Keep Server state outside Package roots and preserve it when replacing or upgrading the install; the Local archive does not contain `data/` or an active Server config.

## Distribution and compatibility

The Server binary is distributed separately as `att-4.2.5-server.tar.gz`, containing `server/att-server-4.2.5.war` (with ATT application code combined in `WEB-INF/lib/att-server-4.2.5.jar`) and the deployment guides. The local CLI is in `att-4.2.5-local.tar.gz` with the shared Worker/Engine runtime at `lib/att-local-4.2.5.jar`; the Server uses that same version-matched library. Tomcat provides the HTTP listener and Servlet API. The Server requires Java 17; the CLI, Engine, and Worker continue to target Java 8.
