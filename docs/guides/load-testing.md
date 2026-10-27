# Run a Load test

Use Studio for the six-mode Load Template catalog and its validated builder, or use the current 4.2.5 Local CLI for an authored scenario.

## Studio workflow

1. Open Load Testing and choose a template that matches the workload shape.
2. Bind each required Target. Configure workload pacing and business inputs in the editor.
3. Validate the composed scenario and review the redacted preview.
4. Confirm Run Now or create a Schedule from the validated draft.
5. Review execution records and evidence in Jobs and Results.

For the six mode definitions, see the [Load model](../concepts/load-model.md). The [Load Reference](../reference/execution-modes/load.md) owns template, binding and execution rules.

## Current 4.2.5 CLI

Run an authored scenario or promote one supported Debug sidecar into a Load workload:

~~~sh
./att.sh load ../examples/load/closed-smoke.yaml --config config/config.yaml --env SIT
./att.sh load --debug template PAYMENT_INVOKE --load-template builtin:single-vu --preview
./att.sh load --debug --load-template load/templates/mixed.yaml --bindings load/bindings/mixed.yaml --env SIT --preview
~~~

List and preview operations start no traffic. Removing preview from a valid execution command can generate Load traffic. Keep credentials and real endpoints out of committed examples.

Current 4.2.5 Load CLI and proposed CLI vNext are different interfaces. See the [CLI Reference](../reference/cli.md) and [Planned vNext page](../interfaces/cli/vnext-proposed.md).
