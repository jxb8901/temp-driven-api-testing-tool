# Package-owned Tools (ATT 4.2.10)

ATT Tools belong to the **selected registered Package**, not shared Runtime Configuration. Create one YAML document per Tool under `<PACKAGE_ROOT>/tools/*.yaml`, with `schemaVersion: att-tool/v1`. This feature does not require Studio online source editing (#233).

```yaml
schemaVersion: att-tool/v1
id: sendPayment
name: Send Payment
description: Submit a request using an existing Package script
command: [./tools/scripts/send-payment.sh, "${input.requestText}"]
stdoutFormat: json
arguments:
  requestText:
    name: Request body
    required: true
```

A call-backed Tool uses centrally provided Helpers, selected by the effective Environment:

```yaml
schemaVersion: att-tool/v1
id: queryTransaction
name: Query transaction
description: Fetch one transaction status using the global DB helper
call: "#{db.orders.scalar(sql='select count(*) from orders where id = ?', params=[${input.transactionId}])}"
arguments:
  transactionId:
    name: Transaction ID
    required: true
```

Tool IDs are scoped to `(registeredPackageId, toolId)`. Identical IDs in separate Packages are independent. There is no shared/global Tool fallback, cross-Package import, or new script approval/sandbox. DB/MQ/HTTP/SSH Helpers, connection pools, credentials and endpoint bindings remain global. Scripts run with the existing ATT/Worker OS identity, execution behavior and Package path handling.

### Migrating a 4.2.9 installation

Legacy `config/config.yaml: tools:` and `toolGroups:` **must be converted before a 4.2.10 runtime starts**. The new runtime fails with a migration diagnostic rather than silently running a global Tool.

1. Back up your configuration and Package repositories.
2. Run `python3 tools/migrate_package_tools.py --config config/config.yaml --package packages/default` for a **dry run**. Install PyYAML if not already installed (`python3 -m pip install pyyaml`).
3. Examine Tool ID collisions, global Helper bindings, executable locations and any listed unsupported Tool Group settings. Copy a global Tool to each explicit Package that needs it; this is not an implicit shared registry.
4. Run the same command with `--write` after review. Update independent environment configuration files if your deployment uses them. Recheck relative script paths against the selected Package root and manually copy scripts as appropriate.
5. Run Package validation, direct Tool Debug/RUN, and a LOAD smoke test; revalidate existing Job/Schedule revisions before reuse.

The migration utility deliberately refuses unsupported Tool Group SSH bindings rather than dropping them. It preserves old dotted qualified names (such as `fpp.invokeApi`) as **Package-local IDs**, enabling gradual template upgrades. A dotted ID does not imply a shared Tool Group. Only `tools/*.yaml` descriptors are discovered; nested `tools/scripts/` is for scripts, not definitions.

### Studio

Choose a concrete registered Package; Explorer lists the corresponding Tools and shows each Tool's actual Package YAML in the Source tab subject to the existing source policy. Shared Configuration shows central Runtime/Environment and DB/MQ/HTTP/SSH Helper settings, **not** Tool catalogs. All Packages does not combine Tool inventories. Online Tool editing remains outside #352 and can use #233 later.

See also [Package model](../concepts/package-model.md) and [Tools reference](../reference/resources/tools.md).
