<!-- att-doc-status: current release=4.2.5 -->

# Studio administration

![ATT Studio Configuration screen](../../system-design/ui-shell-baseline/configuration-1440-light.png)

*Static UI-shell baseline capture with sample configuration data; the version badge belongs to the screenshot baseline.*

## Account management

In local mode, the current-user menu offers **Change password** to every signed-in local user. The form requires the current password, new password, and confirmation. A successful change revokes the current session and redirects to the login page with a success message. Password inputs stay in the active form only; the UI does not write them to local storage, session storage, URLs, or application logs.

Local `ADMIN` users can open **Settings → User Management** to search and sort the safe account list by login ID, display name, role, and status. They can create `ADMIN` or `USER` accounts, enable or disable an account, and reset a password. Create and password forms require matching confirmation and follow the Server's 12–1024 character password policy. Existing passwords are never displayed. Disabling a user or resetting a password requires confirmation and revokes that user's sessions. The Server prevents disabling the last active `ADMIN`, including concurrent disable requests. If every administrator is unable to sign in, use the offline recovery procedure in the [Server deployment guide](../../operations/security.md#recover-a-locked-out-local-administrator); recovery only updates an existing `ADMIN` and never reopens bootstrap.

The account list and controls are available only to authenticated local `ADMIN` users. Other local users see no account data or management controls. In container mode, Settings explains that Tomcat or the configured SSO provider manages accounts; ATT does not show external accounts as if it owned them. Settings can use English or Traditional Chinese, follow the browser language, or save a language choice in this browser. The UI keeps the Server authoritative for access checks and password policy.
