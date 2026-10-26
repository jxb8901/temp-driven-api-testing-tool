# Configuration model

ATT resolves shared runtime settings and Package-owned defaults in separate layers.

## Shared Runtime Configuration

ATT_HOME/config/config.yaml owns runtime defaults, resource registries and Environment Profiles. Selecting an Environment chooses one profile's bindings for logical helpers and may override configuration values that the schema allows at that layer.

## Package defaults and Testdata

The optional Package Manifest owns Package defaults and its declared Testdata inventory. A supported typed override changes only the allowlisted field types defined by the Manifest contract. It cannot replace the global file, add privileged configuration, or point into another Package.

## Effective configuration

Effective values are resolved from the shared configuration, selected Environment and permitted Package defaults. Invocation-level options can override supported values for one execution. A value does not become valid in another layer merely because it exists in one layer; each schema and semantic validator enforces its owner.

For exact precedence, legal override fields and compatibility behavior, use the normative [Configuration Reference](../reference/configuration.md) and [Package Manifest Reference](../reference/package-manifest.md).
