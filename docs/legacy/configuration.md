# Legacy Package configuration

This guide describes package-local paths and Server registration for existing Packages.

## Select the Package locally

The launcher uses `packages/default` unless `ATT_PACKAGE_ROOT` selects another root. `package.yaml` defines Package metadata and resource roots; shared Runtime Configuration remains under `ATT_HOME/config/`.

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh debug template PAYMENT_INVOKE
```

When a scenario is stored outside the Package, pass a Package-relative path such as `../../examples/legacy/load/closed-minimal.yaml`. Runtime configuration options such as `--config config/config.yaml` continue to resolve from `ATT_HOME`.

## Register the Package on Server

`packages/legacy/package.yaml` makes the directory a valid Package. It does not add the Package to a Server registry. An administrator must add the root under `packages.allowedRoots` and give it an entry under `packages.entries` in the Server configuration:

```yaml
packages:
  allowedRoots:
    - /opt/att/packages
  entries:
    default: /opt/att/packages/default
    legacy: /opt/att/packages/legacy
```

Restart or reload the Server according to the deployment's configuration procedure. Studio and the Server API expose only configured Package IDs. Source browsing and source edits also require the caller's configured Package role; registry membership does not grant editor access.

Keep all configured Package roots inside an approved allowed root. Use absolute server paths, and do not let user-editable manifests choose Server registry entries or infrastructure settings.

## Separate Package and Runtime Configuration

Package resources include Flows, Templates, Testdata, Tools, and helper descriptors. Shared environment profiles and credentials remain in Server or Local runtime configuration. A Package's `package.yaml` cannot override Server authorization, allowed roots, worker policy, or secret storage.

## Earlier Workbook configuration fields

Older workbook packages may still contain `templates.root` and `testcase.root`. They are deprecated; new packages declare Package resource roots in `package.yaml`. For an existing workbook Run, `report.fileNamePattern` uses the source workbook name through `${suiteName}` and writes the result below the Run's `workbooks/` directory. These settings are not used by the Native Case result workbook. See the [Legacy authoring guide](authoring.md) for the associated Package files.
