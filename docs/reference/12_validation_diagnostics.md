## 12 Validation and Troubleshooting

### Where to look first

Run `validate --package` and fix the diagnostic's file/field first. For runtime failures, inspect the report status/message, then the execution's `case.log`, `case.yaml` and Action evidence. [Reliability](08_reliability_execution_control.md) defines FAIL versus ERROR, continuation and retries; [Results](11_results_reports_evidence.md) identifies collector failure paths. See [Appendix D](appendices/limits_defaults.md) for Windows launchers, Java SSH negotiation and stack-trace policy.

### Start with validation

Run this after every workbook, sidecar, template, helper, or tool change:

```sh
./att.sh validate --package
```

For one environment, use `./att.sh validate --config config/config.yaml --env SIT --package`. ATT validates descriptors against the active schemas in [Appendix A](appendices/schema_matrix.md). Superseded schema files under `schemas/history/` are historical references, not runtime compatibility contracts. Update the declared `schemaVersion` and migrate fields to the active contract before validation. Diagnostics retain the original violation, file and YAML field location and provide migration guidance; they never rewrite descriptors. For example, replace a historical Render action with an Assign using `&{path}` and pass the resulting String as described in [Actions and Typed Values](14_actions.md). Unsupported versions fail before execution.

Current schemas are in [`schemas/`](../../schemas/); older definitions are under [`schemas/history/`](../../schemas/history/). `validate --package` checks every catalog-registered schema resource, even when the package does not use it. A missing, unreadable, unsafe, or duplicate registered schema is a hard `PACKAGE_INVALID` error. Validation never rewrites YAML. Review the migration guidance, update the file, then rerun package validation for each selected `--env`.

Then use the diagnostic code and structured location. Do not automate against message text.

| Category | Typical cause | Corrective action |
|---|---|---|
| `ATT-TC` | Missing/stale snapshot, sidecar/sheet/header error, or duplicate Case ID | Check snapshot/basenames, sheet mapping, effective headers, and full IDs |
| `ATT-CTX` | Unknown or ambiguous Context path | Inspect requested/current/missing fields, nearest suggestion, or canonical candidates |
| `ATT-STG` | Blank required selector, invalid selector YAML, duplicate stage key | Check selector form, `name`, aliases, and required flag |
| `ATT-TPL` | Unknown/duplicate template, invalid action or payload | Check symbolic name/full path, descriptor, action type, and local files |
| `ATT-CFG` | Unknown field, duplicate key, wrong schema/type/enum | Compare with Chapter 6 and remove unsupported fields |
| `ATT-TOOL` | Unknown/missing argument, process or parse failure | Compare call contract; inspect exit code and bounded stdout/stderr capture evidence |
| `ATT-PATH` | Illegal ID or escaping path | Remove illegal characters and keep content below configured roots |
| `ATT-RUN` | Timeout, non-zero exit, render/runtime failure | Inspect case log and action/tool evidence |

### Common questions

#### Why is the Case ID rejected although Excel displays it correctly?

ATT imports displayed cell text, then applies strict ID safety checks. Check hidden leading/trailing whitespace, trailing `.`, path characters, controls, and Windows device names. Store identifiers as text to preserve leading zeroes.

#### Can two sheets both contain `TC001`?

Yes. Give sheets different group IDs, producing IDs such as `payment.payment.TC001` and `payment.batch.TC001`.

#### Why did `N/A` become empty?

ATT normalizes `N/A`, `NA`, `NULL`, `NONE`, empty, and whitespace-only values to blank before data mapping and stage selection.

#### Why does a Context variable fail?

ATT treats an absent path as an authoring/runtime error instead of silently rendering it as empty. Follow `ATT-CTX-001` and its `requestedPath`, `currentNode`, `missingSegment`, and nearest suggestion to check the case-sensitive scope, physical header/alias, stage key, action ID, and availability point. A suffix shorthand must identify exactly one readable logical path; `ATT-CTX-002` lists every conflicting candidate so you can lengthen it. The diagnostic intentionally omits the full Context tree. A declared optional field with a blank value is still valid and renders as the empty string.

#### Why did a FAIL become ERROR?

A false assertion is FAIL. Invalid expression syntax/navigation, tool failure, timeout, parse failure, I/O failure, or runtime exception is ERROR. Inspect the action evidence rather than only the final aggregate status.

#### Why did a tool run more than once?

Its action used retry and received an eligible non-zero exit code. Inspect the attempt list and final action record in the case log.

#### Can I use a shell pipeline in `command`?

No. ATT passes `|`, `>`, and `<` literally. Put shell behavior inside a reviewed tool script.

#### Why does a required array argument reject `[]`?

Required validation happens before argv expansion. An empty typed List is missing input; pass at least one scalar item or make the argument optional.

#### Should I use package or selected validation?

Use selected mode for fast local feedback. Use package mode before release, CI promotion, or sharing a package.

#### Can reports be opened without a server?

Yes. Keep the generated run directory together so relative artifact links continue to work.

#### Does build execute tests again?

No. It archives one completed persisted run.

### Security reminders

Do not place passwords, tokens, private keys, or sensitive customer data in workbook cells, template descriptors, command strings, stdout, or stderr. Prefer approved secret injection inside tool scripts. Review reports and archives before sharing.

### Validation JSON contract

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "3.7.3",
  "valid": false,
  "mode": "package",
  "summary": {"errors": 1, "warnings": 0, "suites": 1, "cases": 22, "templates": 7, "tools": 7},
  "diagnostics": [{
    "code": "ATT-TPL-104",
    "severity": "ERROR",
    "message": "assert action requires a non-blank expression",
    "file": "templates/PAYMENT_VERIFY/template.yaml",
    "field": "actions.assertStatus.expression",
    "sheet": null,
    "row": null,
    "column": null,
    "template": "PAYMENT_VERIFY",
    "action": "assertStatus",
    "suggestion": "Add expression to the assert action"
  }]
}
```

Every diagnostic always contains `code`, `severity`, `message`, `file`, `field`, `sheet`, `row`, `column`, `template`, `action`, and `suggestion`. Inapplicable fields are `null`. When package and case validation discover the same root failure, ATT emits one diagnostic with `occurrences` and, when applicable, an `affectedCases` list; `summary.errors` counts unique diagnostics while `summary.errorOccurrences` preserves the raw occurrence count. Codes are stable; automation must not parse human messages.

ATT may also include `summary`, `detail`, `source`, `context`, and `schemaViolations`. `source` holds physical YAML or payload `line`, `column`, `endLine`, and `endColumn`; the top-level `row` and `column` continue to identify an Excel cell. For single-line plain or directly quoted YAML scalars, an expression syntax error points to its character. Folded, multiline, or escaped scalars use the YAML scalar range when an exact mapping is unavailable. Every schema violation retains its own path, keyword, message, and physical source. `context` may contain the Case, Stage, Flow ID, and nested call chain. Expression syntax details identify the containing tool-call argument (for example, `logFiles`), the unexpected token, and a bounded caret excerpt when it is safe to show; source excerpts are omitted when the field or line may contain credentials or secrets.

Runtime Action failures preserve the same structure in Case YAML, `run.yaml`, regenerated reports, CI JSON, and JUnit failure detail. A nested Flow failure identifies the inner `flow.yaml` and Action while the call chain identifies how the Template reached it. Tool and DB evidence adds attempts, timeout, parse/capture, parameter binding, and cancellation details where available. File save failures include the configured path and allowed artifact root.
