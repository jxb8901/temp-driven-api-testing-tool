### 14.4 Limits and Defaults

Use the owning schema/configuration chapter for normative field defaults. Important architectural limits include:

- load V1 chooses exactly one workload model;
- arrival-rate overload policy is `drop`;
- Flow invocation receives a fresh Action scope and caller scope is restored on return;
- `EXEC.LOAD` exists only for Load iterations;
- resource lifecycle state is not a public Context tree;
- unknown schema fields are rejected except documented extension locations such as root `x-*` where supported.

Operational numeric limits such as timeout ranges, evidence sample bounds, result limits and pool sizes remain defined by their schemas/descriptors so this appendix does not become a second source of truth.
