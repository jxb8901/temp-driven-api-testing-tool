# Run a Load test

Use Studio for the six-mode Load Template catalog and its validated builder, or use the current 4.2.8 Local CLI for an authored scenario.

## Studio workflow

1. Open Load Testing and choose a template that matches the workload shape.
2. Bind each required Target. Configure workload pacing and business inputs in the editor.
3. Validate the composed scenario and review the redacted preview.
4. Confirm Run Now or create a Schedule from the validated draft.
5. Review execution records and evidence in Jobs and Results.

For the six mode definitions, see the [Load model](../concepts/load-model.md). The [Load Reference](../reference/execution-modes/load.md) owns template, binding and execution rules.

## Current 4.2.8 CLI

Run an authored scenario or promote one supported Debug sidecar into a Load workload:

~~~sh
./att.sh load ../examples/load/closed-smoke.yaml --config config/config.yaml --env SIT
./att.sh load --debug template PAYMENT_INVOKE --load-template builtin:single-vu --preview
./att.sh load --debug --load-template load/templates/mixed.yaml --bindings load/bindings/mixed.yaml --env SIT --preview
~~~

List and preview operations start no traffic. Removing preview from a valid execution command can generate Load traffic. Keep credentials and real endpoints out of committed examples.

## Long-running LOAD runs

Browser-created Quick and Advanced Load drafts use the Server's configured inline duration limit (`server.inlineLoad.maxDurationSeconds`, up to 864,000 seconds / 10 days). Package-backed scenarios use the same effective-envelope validation and configured Server limit. The full envelope runs in ramp-up + warmup + steady duration + ramp-down order. For example:

~~~sh
./att.sh load load/overnight.yaml --config config/config.yaml --env SIT
~~~

In Studio, open **Package LOAD scenarios** from the template builder, select the saved scenario, then validate and submit it. Long runs receive a read-only preflight that estimates retained evidence files and bytes, checks quotas, Tool output retention, artifact archive capacity, and available output disk space. It reports risks as warnings and does not reject a run solely because of its duration.

Current 4.2.8 Load CLI and proposed CLI vNext are different interfaces. See the [CLI Reference](../reference/cli.md) and [Planned vNext page](../interfaces/cli/vnext-proposed.md).

### MQ pool wait diagnosis

A LOAD MQ borrow uses one wait budget for admission, physical capacity and connection creation. A zero timeout only acquires an immediately idle connection. If a connection attempt outlives the budget, its physical slot remains reserved until it completes; a late connection can be reused or is closed during shutdown. Pool shutdown waits at most the borrow timeout (with a short floor) for outstanding creates and closes; if reservations remain, teardown fails with `LOAD_RESOURCE_TEARDOWN_TIMEOUT` and diagnostics retain `residualCreating` / `residualClosing` without releasing unconfirmed physical slots. If the pool is exhausted while a physical connection is being created or closed, the borrower waits for availability and reports `MQ_POOL_TIMEOUT` when the budget expires. The diagnostic includes bounded helper and instance identifiers and a pool snapshot. Check active, idle, creating, closing, and waiting counts alongside the configured pool size and borrow timeout. A failed physical disconnect remains charged until teardown can be confirmed. Increasing ramp-down or setting `minIdle` to zero is not required for pool correctness.
