# Standalone Debug

Debug runs one Flow or Tool with explicit inputs and writes results to its own directory. It does not require a Test Case Definition. For a Package authored with an earlier model, see the [Legacy documentation](../../legacy/README.md).

```sh
./att.sh debug flow common.payment.v1 --input common.payment.debug.yaml
./att.sh debug tool sample.getAcDate --input sample-tool.debug.yaml --env UAT
```

## Bootstrap inputs

Use `att-debug/v1.2`. Flow Debug accepts `inputs`, optional `vars`, and optional Testdata imports. Tool Debug accepts the Tool's declared `arguments`. These values are validated before execution; framework-owned identity and output fields cannot be overridden.

```yaml
schemaVersion: att-debug/v1.2
inputs:
  amount: 100
vars:
  reference: REF-001
```

A Flow that reads `EXEC.INPUT` needs `inputs`; `vars` seeds a reusable Flow's initial `EXEC.VARS`. Testdata references use the same mapping rules as RUN and LOAD. See [Testdata registry and input mapping](../test-authoring.md).

## Discovery and results

Run `./att.sh debug` without a target to list statically valid Flows and Tools. Discovery validates the selected dependency closure and does not execute resources. Use `--format json` for machine-readable output.

Each invocation writes `case.log`, `result.yaml`, and retained Action artifacts under `output/debug/<debugId>/`. Debug does not update the ordinary `latest-run.yaml` pointer. Exit codes are `0` for PASS, `1` for a failing Action, `2` for invalid CLI/input/configuration, and `3` for a runtime error.

For resource calls, use the same package-relative file-content expression in Debug, RUN, and LOAD. Validation resolves referenced files before external I/O. Review the selected Environment and safe inputs before running a Tool that can contact an external system.
