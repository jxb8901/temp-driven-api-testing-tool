## 03 Runtime and Context Model

Run, Debug and Load share one canonical EXEC/META expression model. EXEC changes through framework lifecycle and explicit input/variable/action publication. META is curated, immutable and secret-safe.

### Identity roots

| Path | Meaning and type | Availability |
|---|---|---|
| EXEC.ID | Current Case, Debug execution or Load iteration ID; String. | After execution initialization; generated before Load business actions. |
| EXEC.RUN_ID | Enclosing ATT Run ID; String. | All modes; shared by Cases/iterations in a run. |
| EXEC.STARTED_AT | Current execution start time; ISO-8601 String. | All modes. |
| EXEC.RUN_STARTED_AT | Enclosing run start time; ISO-8601 String. | All modes. |
| EXEC.OUTPUT_DIR | Absolute execution workspace path. | After initialization; in Load it may be planned and not yet exist. |
| EXEC.INPUT | Case/stage, Debug sidecar or Load workload input map. | All modes. Stage inputs temporarily overlay Case inputs. |
| EXEC.VARS | Typed values published by assign. | All modes; empty before assignment. |
| EXEC.ACTIONS | Completed Actions in the active Template/Flow scope. | After an Action is published; Flow has a nested scope. |
| EXEC.LOAD.MODEL | closed or arrivalRate. | Load only; available before ID generation. |
| EXEC.LOAD.WORKLOAD_ID | Configured workload ID. | Load only; available before ID generation. |
| EXEC.LOAD.USER_ID | Stable virtual-user ID. | Closed Load only; absent for arrival-rate. |
| EXEC.LOAD.ITERATION | Numeric iteration sequence. | Load only; available before ID generation. |
| EXEC.LOAD.PHASE | WARMUP, RAMP_UP, STEADY or RAMP_DOWN. | Load only; available before ID generation. |

EXEC.LOAD exposes stable identity. Scheduler counters, queue state and timing diagnostics stay in evidence-only DIAG.load, which is not an expression root.

### META field inventory and lifecycle

The public META root contains only `PROJECT`, `SOURCE`, `TARGET`, `TEMPLATE`, `FLOW`, `TOOL`, `DBHELPER`, `MQHELPER`, and `HTTPHELPER` as listed below. META contains descriptive fields only. A path may be absent when its component is not active.

| Public path | Meaning, type and example | Modes and availability | Scope and when absent |
|---|---|---|---|
| META.PROJECT.id | Project directory name; String, e.g. `payment-att`. | Run, Debug, Load; after project binding. | Execution-wide. |
| META.PROJECT.root | Normalized project-root path; String, e.g. `/srv/att/payment`. | Run, Debug, Load; after project binding. | Execution-wide. |
| META.SOURCE.type | Source kind; String: `testcase`, `debug` or `load`. | Run, Debug, Load. | Execution-wide. |
| META.SOURCE.path | Normalized absolute source path; String, e.g. `/srv/att/payment/testcase/payment.xlsx`, `/srv/att/payment/debug.yaml` or `/srv/att/payment/load/payment.yaml`. | Run, Debug, Load when a source file exists. | Execution-wide; absent for an in-memory source. |
| META.SOURCE.caseId | Canonical TestCase or synthetic Debug Case ID; String, e.g. `payment.default.P001`. | Run, Debug. | Execution-wide; absent in Load. |
| META.SOURCE.workbookId | Workbook identifier; String, e.g. `payment`. | Run. | Execution-wide; absent outside workbook Cases. |
| META.SOURCE.groupId | Workbook group identifier; String, e.g. `default`. | Run. | Execution-wide; absent outside workbook Cases. |
| META.SOURCE.rowCaseId | Case ID from the workbook row; String, e.g. `P001`. | Run. | Execution-wide; absent outside workbook Cases. |
| META.SOURCE.sheet | Workbook sheet name; String, e.g. `Payment`. | Run when supplied by the workbook adapter. | Execution-wide; absent for Debug/Load or when unavailable. |
| META.SOURCE.row | One-based workbook row number; Number, e.g. `12`. | Run when supplied by the workbook adapter. | Execution-wide; absent for Debug/Load or when unavailable. |
| META.SOURCE.workbook | Optional workbook label; String when present, e.g. `payment_regression.xlsx`. | Run when supplied by the adapter. | Execution-wide; optional and otherwise absent. |
| META.SOURCE.scenario | Load scenario basename without its suffix; String, e.g. `payment-smoke`. | Load. | Execution-wide; absent outside Load. |
| META.TARGET.type | Resolved target kind; String: `testcase`, `template`, `flow` or `tool`. | Run, Debug, Load; after target selection. | Execution-wide. |
| META.TARGET.id | Resolved target identifier; String, e.g. `PAYMENT_INVOKE` or `payment.flow.v1`. | Run, Debug, Load; after target selection. | Execution-wide. |
| META.TEMPLATE.id | Active Template or resolved Load execution-wrapper ID; String, e.g. `PAYMENT_INVOKE`. | Run/Debug while a Stage runs; Load after target resolution and while its wrapper runs. | Component scope; absent before target/template resolution, restored or removed after the scope. In Load Flow/Tool targets this is the resolved synthetic wrapper. |
| META.TEMPLATE.path | Normalized Template or execution-wrapper directory; String, e.g. `/srv/att/templates/PAYMENT_INVOKE`. | Same availability as META.TEMPLATE.id. | Component scope; absent before resolution, restored or removed after the scope. |
| META.FLOW.id | Active Flow ID; String, e.g. `payment.request.v1`. | Run, Debug, Load while that Flow invocation runs. | Invocation scope; push on entry, restore on return, absent when inactive. |
| META.FLOW.invocationId | Caller Action ID; String, e.g. `sendRequest`. | Same availability as META.FLOW.id. | Invocation scope; absent when no Flow is active. |
| META.FLOW.depth | One-based nested Flow depth; Number, e.g. `1`. | Same availability as META.FLOW.id. | Invocation scope; absent when no Flow is active. |
| META.TOOL.id | Active configured Tool or built-in name; String, e.g. `payment.queryOrder` or `upper`. | Run, Debug, Load during the invocation. | Invocation scope; never retained as the “last Tool” after return. |
| META.TOOL.type | Invocation kind; String, e.g. `tool` or `builtin`. | Same availability as META.TOOL.id. | Invocation scope; absent after return unless an outer invocation remains. |
| META.DBHELPER.id | Logical DBHelper ID; String, e.g. `orders`. | Run, Debug, Load during a DBHelper operation/DB Action. | Invocation scope; absent after return unless an outer scope remains. |
| META.DBHELPER.type | Resource kind; String, `dbhelper`. | Same availability as META.DBHELPER.id. | Invocation scope; absent after return unless an outer scope remains. |
| META.MQHELPER.id | Logical MQHelper ID; String, e.g. `payment`. | Run, Debug, Load during an MQHelper operation. | Invocation scope; push/restore; absent after return unless an outer scope remains. |
| META.MQHELPER.type | Resource kind; String, `mqhelper`. | Same availability as META.MQHELPER.id. | Invocation scope; absent after return unless an outer scope remains. |
| META.HTTPHELPER.id | Logical HTTPHelper ID; String, e.g. `payment`. | Run, Debug, Load during an HTTPHelper operation. | Invocation scope; push/restore; absent after return unless an outer scope remains. |
| META.HTTPHELPER.type | Resource kind; String, `httphelper`. | Same availability as META.HTTPHELPER.id. | Invocation scope; absent after return unless an outer scope remains. |

META.SSHHELPER is not public. SSH connection and credential settings stay private to Tool invocation. META.TOOL may identify the active Tool, but SSH endpoint, user, identity file and credentials are not META fields.

ATT recursively filters credential-bearing keys such as password, secret, token, authorization/cookie, API key and private key. Expressions and adapters can read META but cannot mutate it.

### Invocation and scope rules

Entering a Template, Flow, Tool or helper invocation publishes metadata for that active scope. Nested calls push a frame; exit restores previous metadata. When no invocation is active, its branch is absent. Consumers must not depend on “last invoked” state.

EXEC.INPUT is the canonical input map. A Stage temporarily overlays Case inputs and restores them after completion. EXEC.VARS is shared across later Stages in a Case. EXEC.ACTIONS is scoped to the active Template or Flow. An Action reads local output while running and publishes its envelope at EXEC.ACTIONS.<id>.output.

### Action output and evidence paths

| Path | Meaning and availability |
|---|---|
| `output.result` | Primary typed Action result while the Action is active, including its assertion. |
| `output.evidence.collectors.<id>.result` | Typed result of an active Tool evidence collector. |
| `output.evidence.collectors.<id>.status` | Collector `PASS`/`ERROR` status while the Action is active. |
| `output.evidence.collectors.<id>.error` | Bounded failure summary with a non-blank `message` when the collector fails. |
| `output.evidence.collectors.<id>.evidence` | Preserved bounded/redacted underlying Tool/resource evidence, including resource identity and native failure fields when supplied. |
| `EXEC.ACTIONS.<actionId>.output.result` | Published primary typed result after the Action completes. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result` | Published final/winning collector result. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.status` | Published final/winning collector status. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.error/evidence` | Published collector failure summary and preserved operation evidence. |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.result/status` | Collector result/status for a specific retry attempt; earlier attempts remain after a later success. |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.error/evidence` | Failure summary and underlying evidence for that specific collector attempt. |

Strings, numbers, booleans, null, maps, lists and DocumentValue remain typed across Action/Template/Flow boundaries.

### Load execution ID initialization

Load uses schema att-load/v1.2. If execution.execIdFormat is present, ATT evaluates it once per started iteration with the normal ${...} / #{...} engine during initialization; otherwise the default run-scoped ID remains in effect.

Available values include EXEC.RUN_ID, timestamps, EXEC.INPUT, EXEC.LOAD.MODEL/WORKLOAD_ID/ITERATION/PHASE, closed-only EXEC.LOAD.USER_ID and the already curated META.PROJECT/SOURCE/TARGET/TEMPLATE. EXEC.ID and EXEC.OUTPUT_DIR are unavailable because the generated ID determines the workspace. No Action has run, so EXEC.ACTIONS and invocation-scoped Flow/Tool/helper META are absent.

Only deterministic, side-effect-free built-ins are allowed. External Tool/DB/MQ/HTTP/SSH calls and stateful, random, clock or filesystem functions are rejected. seq.next() is neither allowed nor required. Use stable identity components:

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Arrival-rate has no USER_ID:

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

IDs must be non-empty, path-safe single segments and unique within the Load run. Duplicate or unsafe values fail before the target starts; ATT does not append a hidden suffix.

### Run, execution and evidence navigation

| Identity | Meaning | Scope | Artifact role |
|---|---|---|---|
| EXEC.RUN_ID | Enclosing ATT run. | Run. | Run root, summary and report. |
| EXEC.ID | Current Case/Debug/Load execution. | Execution. | Key for logs/evidence when a workspace exists. |
| EXEC.OUTPUT_DIR | Workspace path associated with EXEC.ID. | Execution. | Physical Run/Debug workspace or planned lazy Load workspace. |

Normal Run stores functional Cases under output/<RUN_ID>/executions/<EXEC.ID>/. In Load, EXEC.OUTPUT_DIR and CASE.outputDirectory remain at output/load/<RUN_ID>/executions/<EXEC.ID>/ throughout the iteration. When retained, a copy of its artifacts is also stored under samples/<EXEC.ID>/ or failures/<EXEC.ID>/. Metrics-only iterations have EXEC.ID but no per-iteration directory after the scheduler releases their temporary workspace. Retained Load rows show EXEC.ID and link to case.log when present. Debug uses its debug ID as both EXEC.RUN_ID and EXEC.ID.

DIAG is evidence-only. Do not reference DIAG, EXEC.MODE or arbitrary scheduler counters in expressions; pass business variation through EXEC.INPUT.

### Optional lookup and compatibility

${path} is strict. ${path?} returns null for an allowed missing map/list path; it does not make malformed syntax or illegal scope access valid. Legacy CASE, RUN and ACTIONS aliases remain only where they map one-to-one to canonical data. New Templates should use EXEC and META.
