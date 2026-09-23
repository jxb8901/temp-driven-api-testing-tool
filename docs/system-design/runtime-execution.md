# Runtime and Execution Internals

Status: Maintainer documentation

This material was moved out of the normative end-user Reference Manual by issue #42. It describes implementation ownership and invariants. Supported user-visible contracts are defined in `docs/reference/`; this file explains internal sequencing and safety decisions.

This chapter explains the behavior that users normally do not need while authoring cases but maintainers need when modifying validation, execution, persistence, or reports.

#### Ownership model

```text
case owns ordered stages
stage defines a template-selector column and owns stage-private data
the current row's selector cell names the template to resolve
template owns ordered actions
tool action invokes one independent global tool contract through declared arguments
```

The authoritative persisted runtime tree has one `CASE` root. Convenience scopes such as `ACTIONS`, `TOOL`, and `DB` do not create alternative persisted roots.

#### Validation pipeline

ATT uses Draft 2020-12 schemas before semantic checks. Validation then resolves workbook mappings, selectors, templates, payloads, expressions, tools, argument contracts, identifiers, paths, and package integrity.

Package mode discovers everything below the configured roots. Selected mode validates only the immutable dependency closure selected for execution. Validation completes before external tools or final run publication.

#### Execution and aggregation

The runner plans selected cases and executes stage/template/action order deterministically. `onFailure` controls continuation but does not suppress result severity. Aggregation is exact and shared by all consumers:

```text
if any ERROR exists: ERROR
else if any INVALID exists: INVALID
else if any FAIL exists: FAIL
else if any PASS exists: PASS
else: SKIPPED
```

Therefore PASS + SKIPPED is PASS, all SKIPPED is SKIPPED, and a selection that resolves to no cases is a command error rather than a SKIPPED run.

Report, manifest, CLI summary, CI JSON, JUnit XML, JUnit HTML, and process exit code must derive from the same aggregate model.

#### Run lifecycle

After validation and planning, ATT atomically reserves:

```text
<outputDirectory>/<RunID>/
```

Evidence is written there and can be inspected while Actions execute. After all required outputs are finalized, ATT writes a `COMPLETE` manifest and atomically replaces `latest-run.yaml`. An interrupted run stays at the reserved path without a completed manifest and is not eligible for `report`, `build`, `rerun-failed`, or latest-run selection. A pre-existing Run ID is rejected before execution; move or clean an incomplete directory before retrying that ID.

#### Process safety

ATT constructs argv directly and uses no implicit shell. Local stdout and stderr are drained concurrently, retained in memory only as bounded head/tail previews, and streamed through bounded temporary spools into the Case log. The spools are removed after logging or explicit `saveAs`; ordinary runs create no `process-output` file or directory. Evidence records original byte counts and truncation flags. Timeout termination stops the managed process according to platform support and retains the same bounded evidence. Structured parsers reject malformed/ambiguous input and XML external-resource features.

`run --profile` writes `performance.json` beside `run.yaml`. It records configuration load, validation, plan, Case execution, result-workbook, HTML/CI report, and input-hash timings; selected/completed Case counts; schema/Template/payload cache loads and hits; process-output bytes/truncations; and a completion-time heap snapshot. It is diagnostic evidence, not a stable CI schema contract.

Workbook import uses Apache POI `DataFormatter` for ordinary cells and deliberately does not create a `FormulaEvaluator`; formula expressions, not cached results, enter Context.

#### CI and parallel execution

| Concurrent operation | Contract |
|---|---|
| Two runs use the same Run ID | Atomic directory reservation allows only one to start; the other fails without overwriting evidence. |
| Multiple runs update `latest-run.yaml` | Each writes its completed manifest first; the last completion wins the atomic pointer update. Completion order, not start order, determines latest. |
| `build` and `run` execute together | Build pins one completed latest-run/manifest pair and ignores any run without a `COMPLETE` manifest. |
| `report` and `clean` execute together | This destructive race is unsupported. Report fails rather than producing a partial result; serialize report/archive/clean jobs sharing one output root. |

Use `--allow-parallel-runs` only to permit multiple ATT processes to share one output root; it does not add Case workers inside one run. `--parallel` remains a deprecated compatibility alias. Use separate `--output-dir` values when parallel jobs need independent run history, cleanup, or latest-run behavior.

#### Path and identifier safety

Validated Run ID and Case ID map directly to directory names. Every write resolves against an intended root, normalizes the path, resolves relevant existing symlinks, and verifies strict containment. Logical CLI identifiers are never accepted as arbitrary filesystem paths.

#### Reproducibility and versioned outputs

The completed manifest captures runtime identity, effective inputs, hashes, selected cases, summary, and output paths. Validation JSON, run manifest, and CI summary have explicit `schemaVersion` values. JUnit XML is constrained by XSD. Consumers should validate the declared version rather than infer structure.

#### Maintainer release checklist

- Run the full automated test suite and require all tests to pass.
- Run `validate --package` against representative packages.
- Verify FAIL/ERROR/INVALID aggregation and exit codes across CLI and all reports.
- Verify JSON/XML parsing, repeated XML children, attributes, and namespaces.
- Verify timeout and retry evidence, including exhausted and later-success cases.
- Verify Run ID collision, atomic completion, latest-run update, and interrupted runs.
- Verify report/build/clean boundaries and concurrent-command behavior.
- Verify schemas, examples, generated documentation, and this manual remain aligned.
