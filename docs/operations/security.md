<!-- att-doc-status: current release=4.2.8 -->

# ATT Server security and identity

## Authentication and identity

`server.auth.mode` selects the authentication provider. It defaults to `container` to preserve existing deployments. Set it to `local` to use ATT-managed accounts. Local mode requires `authenticationRequired: true`; startup rejects a configuration that combines local mode with the legacy anonymous setting.

In `container` mode, Tomcat authenticates requests and ATT uses the Servlet Principal. Existing BASIC, FORM, and SSO deployments keep their container-managed login flow. Health and version remain public. The legacy `server.authenticationRequired: false` option is available only in container mode. It enables shared anonymous access to the UI and API and should be limited to isolated trusted networks. All anonymous callers share drafts, jobs, and artifacts. ATT logs a warning when this mode is enabled.

In `local` mode, `/ui/` redirects unauthenticated browsers to ATT's login page. The REST API returns structured JSON `401` responses. The first administrator is created once using the random token in `dataDir/bootstrap-admin.token`; keep `dataDir` restricted to the Server operating-system account, enter the token on the login page, and remove access to the token file after bootstrap. Token initialization uses a private temporary file and atomic rename; on startup an invalid token is repaired only while no account exists and bootstrap has not been claimed. A claimed bootstrap is never reopened. No default account or password exists. Every request that carries a local password—including login, bootstrap, user creation, password reset, and self-service change—requires HTTPS except for loopback development. Tomcat recognizes proxy-terminated HTTPS only through its configured trusted proxy handling; forwarded headers from untrusted clients do not establish HTTPS. Local passwords are stored as salted PBKDF2-HMAC-SHA-256 hashes. The first release supports `ADMIN` and `USER`; active USER accounts can use operational APIs, while ADMIN accounts can use Settings and account administration only.

Local sessions use opaque server-side identifiers, rotate at login, expire after 30 minutes of inactivity or 12 hours, and are revoked at logout, password change/reset, or account disable. Sessions persist across Server restarts until expiry or revocation. Cookies are HttpOnly, SameSite=Lax, scoped to the deployed context, and marked Secure when Tomcat recognizes the request as HTTPS. Configure the trusted reverse proxy before enabling TLS termination so Tomcat sees the external HTTPS scheme. State-changing cookie-authenticated requests require the session CSRF token in `X-ATT-CSRF`; browser-originated Basic-authenticated mutations also require it. Setting `security.checkCrossOrigin: false` relaxes origin-equality rejection only: it does not bypass cookie CSRF checks or the same-or-trusted-origin JSON requirement for login/bootstrap, and it does not enable wildcard CORS. Eight failed attempts for the same normalized login and client address, or 32 failed attempts from one client address across usernames, are throttled within ten minutes. Bootstrap also allows eight failed token attempts per client address in ten minutes. Self-service current-password verification uses these same attempt limits and the bounded password-check semaphore. These in-memory limits are local to each Server process; use a shared edge rate limit when running multiple instances. Login responses do not distinguish unknown, disabled, and incorrectly authenticated accounts. Successful and failed login attempts are audited without credentials. Password hashes use 310,000 PBKDF2-HMAC-SHA-256 iterations and are upgraded at successful login when a stored hash uses fewer iterations.

`GET /api/v1/auth/me` returns the safe current-user profile and the CSRF token needed by browser clients. `POST /api/v1/auth/login` and `/api/v1/auth/logout` manage sessions. HTTPS Basic authentication is also supported for CLI and REST clients using local accounts. `GET` and `POST /api/v1/auth/users`, `POST /api/v1/auth/users/{loginId}/password`, `POST /api/v1/auth/users/{loginId}/enable`, and `DELETE /api/v1/auth/users/{loginId}` provide administrator account management. `POST /api/v1/auth/password` changes the current user's password after validating the current password. Password hashes, session identifiers, and passwords are never returned in API responses.

In Studio, local `ADMIN` users can manage accounts under **Settings → User Management**. The table exposes only login ID, display name, role, and active state. Create, enable/disable, and password-reset actions use explicit confirmation; disabling or resetting a user revokes that user's sessions. The Server rejects an attempt to disable the last active `ADMIN`, including concurrent requests from separate Server runtimes. Every local user can use **Change password** in the current-user menu. A successful change revokes the current session and returns to the login page. Account passwords are held only in the form while it is open and are not saved to browser storage or application logs. In container mode the Settings page explains that Tomcat or the configured SSO provider owns account management.

### Recover a locked-out local administrator

The one-time bootstrap is not a recovery mechanism and is never reopened. To recover an existing `ADMIN` who has lost the password or was disabled, use the offline recovery tool. This operation cannot create an account and will reject a missing account or a non-`ADMIN` account.

1. Stop every Tomcat process that uses this `server.dataDir` and wait for it to exit. Run the offline recovery command as the Server operating-system account, with write access to the existing `dataDir`.
2. Set `ATT_SERVER_WAR` to the deployed `att-server-4.1.4.war`, `CATALINA_HOME` to the Tomcat installation, and `JAVA_HOME` to Java 17 or later. Extract the WAR to a private temporary directory and launch the tool with its packaged libraries and Tomcat's Servlet API:

   ```sh
   umask 077
   recovery_dir="$(mktemp -d)"
   trap 'rm -rf "$recovery_dir"' EXIT
   (
     cd "$recovery_dir"
     "$JAVA_HOME/bin/jar" xf "$ATT_SERVER_WAR"
     "$JAVA_HOME/bin/java" -Datt.server.config=/absolute/path/to/att-server.yaml \
       -cp "WEB-INF/lib/*:$CATALINA_HOME/lib/*" att.server.AuthRecoveryTool
   )
   ```

3. At the interactive prompts, enter the existing ADMIN login ID, type `RECOVER ADMIN`, and enter the new password twice. Password input is not echoed and is not accepted as a command-line argument.
4. Start Tomcat and sign in with the new password.

The tool updates only the selected, existing `ADMIN` row, reactivates it if disabled, replaces its password hash and CSRF token, revokes all its sessions, and records an `ADMIN_RECOVERY` audit event. It does not modify the bootstrap claim or create a bootstrap token. If no existing database is found, the database cannot be opened, or the selected account is not an `ADMIN`, recovery fails without creating an account. Keep the backup, temporary extraction directory, and `server.dataDir` protected as server credentials.

Active local `USER` accounts share operational access: they can use eligible static and discovered packages, and can view or manage shared jobs and schedules. Draft IDs remain bound to their creating login. `ADMIN` accounts have Settings and account-management access only and receive no Package, Run, Load, Jobs, or Schedules API access. In container-auth mode, access is granted to the configured USER role and excluded for principals with the ADMIN role.

Anonymous job submissions may include an optional top-level `principal` label, for display and audit only. The Server trims and Unicode-normalizes the label, validates its length and characters, and stores it in the existing job/audit `principal` field with the suffix `(anonym)`, such as `Bob(anonym)`. Omitting it keeps the existing value `anonymous`; callers cannot supply the reserved suffix themselves. This label is self-declared and never changes authorization, draft access, job visibility, artifact access, cancellation, or scheduling. Authenticated mode always stores the Servlet Principal and ignores a submitted label. Attribution is not sent to the Worker or exposed in ATT expression Context; no additional persisted or response field is introduced.

State-changing requests require `application/json`; requests carrying an `Origin` must match the request origin. Behind a TLS-terminating proxy, configure Tomcat's `RemoteIpValve` to derive the Servlet scheme, host, and port from the proxy's forwarded headers. Set `internalProxies` to only the actual proxy addresses, and ensure the proxy removes client-supplied `Forwarded`/`X-Forwarded-*` headers before adding its own. For example, adapt these header names and trusted addresses to the proxy:

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       internalProxies="10\\.20\\.0\\.12"
       remoteIpHeader="X-Forwarded-For"
       protocolHeader="X-Forwarded-Proto"
       hostHeader="X-Forwarded-Host"
       portHeader="X-Forwarded-Port" />
```

Tomcat's `RemoteIpValve` must trust only the actual proxy addresses. Ensure the proxy removes client-supplied forwarded headers before adding its own. Bind Tomcat to loopback or a private interface and protect the proxy with a VPN, firewall, or IP allowlist. Use HTTPS for traffic outside localhost. Drafts are in memory and are cleared on Server restart. Changing authentication mode requires a restart; existing drafts are not transferred between identities. Local mode does not read or modify JSPWiki account files and does not implement external LDAP, OIDC, SAML, or shared SSO.

The bundled browser console is served at `<context path>/ui/`. It uses the selected container or local authentication mode and provides account management and password-change controls in local mode. See [ATT Server Web UI](../interfaces/studio/administration.md).
