# ATT Remote client

ATT Remote lets you use an ATT Server from the existing `att.sh` or `att.bat` launcher. It sends requests to the Server's public REST API, watches job events over SSE, and returns the canonical result as an ATT exit code. Local commands remain local.

## Configure a Server profile

Create `~/.att/servers.yaml` with the Server URL and optional Basic-auth settings. Never put a password in this file.

```yaml
default: sit

servers:
  sit:
    url: https://att-sit.example.com
    auth:
      type: basic
      username: att-user
      passwordEnv: ATT_SIT_PASSWORD

  ci:
    url: https://att-ci.example.com
    auth:
      type: basic
      username: att-ci
      passwordEnv: ATT_CI_PASSWORD
```

Inject the named environment variable from your CI secret store. For interactive use, omit `passwordEnv`; the CLI prompts for a password without echoing it. Set `ATT_SERVER_CONFIG` to use another profile file.

Select a profile with `--server`. `ATT_SERVER` can override the default profile URL. An explicit `--server` profile takes precedence over `ATT_SERVER`; otherwise the precedence is `ATT_SERVER` URL, then the configured default profile. The profile still supplies authentication settings when `ATT_SERVER` overrides its URL.

Use `--no-auth` to bypass the profile credentials and password prompt when the Server explicitly allows anonymous access. Basic authentication works only over HTTPS. The client uses the JVM's default TLS trust and hostname verification. It has no insecure TLS switch. Health and version checks happen before each operation; the Server must declare API version `1`.

## Discover packages

Use the package ID configured by the Server. The client does not request or display package root paths.

```sh
./att.sh remote --server sit ping
./att.sh remote --server sit version
./att.sh remote --server sit packages
./att.sh remote --server sit package payments
```

## Submit Run, Debug, Load, and Validate jobs

Pass the logical Server package ID first. Familiar ATT selection and environment options map to Server job fields.

```sh
./att.sh remote --server sit run payments --suite testcase/payment.xlsx --tag smoke --env SIT
./att.sh remote --server sit debug payments flow PAYMENT.submit --set vars.Channel=WEB
./att.sh remote --server sit load payments load/payment.yaml --users 2 --duration 30s
./att.sh remote --server sit validate payments --package
```

Paths such as `--suite`, `--suite-dir`, `--config`, `--input`, and a Load scenario are logical paths inside the Server package. The client rejects absolute paths and parent-directory traversal. The request contains `packageId`, never `PACKAGE_ROOT`, `SERVER_DATA_DIR`, Worker paths, or a client output directory.

Commands wait for a terminal result by default. The client submits once, consumes ordered status/progress/log/diagnostic events, retrieves the result, and exits. It does not invoke `att-engine` locally.

Use `--detach` to return the job ID without keeping a background process:

```sh
./att.sh remote --server ci run payments --all --detach
```

## Manage jobs

Use the returned job ID to inspect, watch, retrieve, cancel, or collect evidence.

```sh
./att.sh remote --server sit jobs
./att.sh remote --server sit job J0123456789ABCDEF
./att.sh remote --server sit watch J0123456789ABCDEF
./att.sh remote --server sit result J0123456789ABCDEF --format json
./att.sh remote --server sit cancel J0123456789ABCDEF
./att.sh remote --server sit artifacts J0123456789ABCDEF
./att.sh remote --server sit artifacts J0123456789ABCDEF --download ./results
./att.sh remote --server sit artifact J0123456789ABCDEF report/index.html --download ./results
```

Artifact names come from the Server's logical artifact list. Downloads preserve bytes, stay beneath the chosen directory, reject symlinked parents, and fail if a destination file already exists.

## Use JSON output and exit codes

Add `--format json` to return structured JSON for listings, job results, and detached submissions. Progress remains on the terminal in human mode; JSON mode emits the final structured object without mixing progress text into it.

| Exit code | Meaning |
|---:|---|
| `0` | PASS or successful operation |
| `1` | ATT job completed with FAIL |
| `2` | INVALID request, package, or validation result |
| `3` | ATT execution ERROR or cancellation result |
| `4` | Connection, authentication, or protocol failure before a trusted result |

An exit code of `4` means the client did not establish a trustworthy ATT result. It does not imply that an already accepted Server job was cancelled.

## SSE reconnect behavior

Attached commands and `watch` remember the latest processed event ID. After a temporary disconnect, the client reconnects with `Last-Event-ID`; the Server replays retained events after that ID. Replayed IDs are deduplicated. Reconnect attempts are bounded. A client disconnect does not cancel the Server job and never causes a resubmission.

## Troubleshoot connection failures

- For TLS errors, install the correct CA chain in the Java trust store used by the CLI. The client verifies certificates and hostnames.
- For HTTP 401 or 403, check the Server profile username and CI secret environment variable. Passwords are not included in command-line arguments or logs.
- For API compatibility errors, deploy a Server that declares `/api/v1` with `apiVersion: "1"`.
- For exit code `4` during an attached run, check the job with `att remote job <jobId>` before deciding whether to submit another one.

## Module boundaries

`att-cli` routes `att remote` before parsing local CLI options. `att-remote` owns HTTP, Basic authentication, profiles, SSE, rendering, and safe artifact downloads. It depends only on `att-server-api` and client libraries. `att-server-api` contains Java 8 wire-contract classes shared with `att-server`; it contains no Servlet, persistence, Worker, or Engine implementation. Local execution continues through `att-engine`; remote execution always uses the Server REST/SSE API.
