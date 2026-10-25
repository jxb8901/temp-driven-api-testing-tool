# Package model

ATT separates shared runtime ownership from business-resource ownership.

## Global Runtime Configuration

The installation owns one shared Runtime Configuration, normally at ATT_HOME/config/config.yaml. It defines runtime defaults, resource registries, Tool groups and Environment Profiles. Operators select an Environment to bind stable logical helper IDs to environment-specific DB, MQ, HTTP or SSH resources.

## Package ownership and isolation

A Package is a registered root for business resources: Testcase workbooks and sidecars, Templates, Flows, package-local scripts, SQL and Testdata. The Server registry assigns a logical Package ID to a root. Server requests use that ID and Package-relative resource names; clients do not choose a host path. A Package does not own a second Runtime Configuration or schema catalog.

Package isolation prevents one Package from using another Package's private resources by inventing a path. Shared Runtime Configuration remains global. This does not make every shared resource visible to every user: Server authorization, registered roots and inspection policy still apply.

## Optional Package Manifest

The optional package.yaml Manifest declares Package defaults and an explicit Testdata inventory. It can select a default Environment and controlled typed overrides. It does not replace shared Runtime Configuration and does not authorize arbitrary fields, privileged configuration or cross-Package reads.

Use [Package Manifest](../reference/package-manifest.md) as the normative owner for Manifest fields, typed overrides and Testdata resolution. [Configuration and Environments](../reference/configuration.md) owns shared configuration and precedence.

## Where to continue

- [Authoring model](authoring-model.md) explains how a Package is authored.
- [Configuration model](configuration-model.md) shows how shared and Package-owned values combine.
- [Run and Job lifecycle](jobs-and-runs.md) distinguishes Local output from Server records.
