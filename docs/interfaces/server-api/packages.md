<!-- att-doc-status: current release=4.3.0 -->

# Package inspection APIs

## Trusted registry and source scope

The Server loads Package roots only from the selected deployment-controlled `att-server.yaml` `packages.entries` mapping. It polls for in-place edits and atomic replacements while Tomcat serves requests. Each candidate mapping is validated as a whole against canonical existing directories and `packages.allowedRoots`; invalid or unstable candidates keep the last-known-good registry. Changes to settings outside `packages` are reported as restart-required and are not partially applied. Unregistered directories never appear as Packages.

```http
GET /api/v1/registry
GET /api/v1/packages/payments/scope
```

`GET /registry` returns `att-package-registry/v1`: a content-addressable `revision`, monotonic `generation`, reload timestamps, `Ready` or `Invalid` status, sanitized diagnostic, and explicitly registered logical package IDs with `Registered`, `Warming`, or `Ready` states. It does not expose filesystem paths. `GET /packages/{packageId}/scope` returns `att-source-scope/v1` with distinct registry, Package source, Package Manifest, Central Runtime Config source, active effective Config, execution, and embedded Engine schema revision identities, plus safe environment/version metadata. `packageSourceRevision` tracks Package-owned content; `executionRevisionDigest` binds execution comparisons to the selected Engine/schema, effective Config, and Environment. Change Set `baseSourceRevision` is a separate aggregate optimistic-concurrency fence and must not be substituted for either identity. The versioned Source Management `RevisionSet` exposes typed `packageSourceRevision` and `executionRevisionDigest` identities; `businessSource` remains a compatibility alias for Package source. It is read-only; the API does not accept source roots from clients. Drafts and approvals created against a prior registry revision are rejected after a registry change. Accepted Jobs retain their already-approved package root and inputs.

## Package resource inspection

Use the public read-only endpoints with the identity resolved by the Server: a Servlet Principal in authenticated mode or the shared `anonymous` identity in anonymous mode. `kind` is `case`, `template`, `flow`, or `tool`; `resourceId` is the opaque package-scoped ID returned by the list endpoint. The list `query` matches logical IDs, names, descriptions, and tags. Omit `type`, `query`, or `limit` to use the defaults. A continuation cursor is valid only for the same package, identity, type, and query.

```http
GET /api/v1/packages/payments/resources?type=case&query=refund&limit=50
```

The response includes safe summaries, a total count, and an opaque `nextCursor` when another page exists:

```json
{
  "items": [{
    "resourceId": "case.<opaque>",
    "type": "case",
    "logicalId": "PAYMENT.REFUND01",
    "name": "Refund request",
    "state": "ready",
    "sourceAvailable": false,
    "provenance": {"suite": "testcase/payments.xlsx", "groupId": "PAYMENT", "sheet": "Cases", "rowNumber": 12},
    "references": [{"type": "template", "logicalId": "PAYMENT.refund", "resourceId": "template.<opaque>", "resolution": "resolved"}],
    "referencedBy": [],
    "diagnostics": []
  }],
  "total": 1,
  "nextCursor": null,
  "diagnostics": [],
  "requestId": "..."
}
```

Read one resource's parsed definition and relationships, then request its safe source projection separately:

```http
GET /api/v1/packages/payments/resources/template/{resourceId}
GET /api/v1/packages/payments/resources/template/{resourceId}/source
GET /api/v1/packages/payments/resources/template/{resourceId}/source-file?referenceId=<opaque>
```

The detail response contains `resource`, `definition`, `diagnostics`, and `requestId`. The Template/Flow YAML source response contains `resource`, `available`, `format`, `text`, `redacted`, `fileReferences`, `fileReferencesTruncated`, `fileReferencesUnavailable`, and `requestId`. Each eligible `fileReferences` item contains a package-relative `logicalPath` and opaque `referenceId`; pass that ID to `source-file` to read the bounded text after the Server repeats canonical containment and visibility checks. `fileReferencesUnavailable` is `true` when one or more literal locators could not be safely verified; it does not expose the authored path or file contents. Source-file responses include `logicalPath`, sanitized `text`, and a `redacted` flag. The route uses the same configured identity and authorization mode as other inspection endpoints; anonymous mode remains subject to its independent inspection feature gate. Never construct a filesystem URL from a logical path. For unavailable source, `available` is `false`, `reason` is stable, and no partial text is returned. References with no matching resource have `resolution: "unresolved"` and a stable resource diagnostic.

Invalid list parameters return `400`; unknown packages/resources return the same `404` shape. Stale cursors return `409`, oversized responses `413`, a full inspection queue `503`, and an inspector timeout `504`. Error responses use the existing `error.code`, `error.summary`, and `requestId` envelope; they do not include physical paths or parser exception messages.

## Lossless authoring-source reads

Safe source inspection above remains available to authorized USER operators and returns the existing redacted projection. It is not writable input. Package authoring reads use a separate endpoint and require an authenticated active USER whose canonical login ID exactly equals the registered Package ID; `default` is never editable. Local login IDs are NFKC-normalized and lowercased with `Locale.ROOT`, then restricted to lowercase ASCII letters, digits, dots, underscores, and hyphens. Trusted container/SSO principals must already use that same canonical form; the source API does not fold case or Unicode and does not merge identities. The trusted deployment switch `server.packageConfiguration.sourceEditingEnabled` must also be `true`. ADMIN does not receive Package USER operations or owner access, and a Package owner does not receive central configuration privileges.

```http
GET /api/v1/packages/{packageId}/authoring-source?type=manifest
GET /api/v1/packages/{packageId}/authoring-source?type=resource&resourceType=template&resourceId={resourceId}
GET /api/v1/packages/{packageId}/authoring-source?type=file&resourceType=template&resourceId={resourceId}&referenceId={opaque}
GET /api/v1/packages/{packageId}/authoring-source?type=load&path=load%2Fscenario.yaml
GET /api/v1/packages/{packageId}/authoring-source?type=testdata&resourceId={descriptorId}&environment=SIT
GET /api/v1/packages/{packageId}/authoring-source?type=testcase&resourceId={readyCaseResourceId}&selector=sidecar
GET /api/v1/configuration/authoring-source?path=config%2Fconfig.yaml
```

Package sources are limited to fixed `package.yaml`, ready Template/Flow YAML, allowed `load/*.yaml` and `load/*.yml` sources, configured Package Testdata descriptors, eligible UTF-8 text files statically reached through an authorized Template/Flow `&{path}` reference, and the YAML sidecar of a ready indexed Testcase. The Testcase request requires its inspected Case `resourceId` and the fixed `selector=sidecar`; it does not accept a package path. The `file` request must reuse the opaque reference and origin issued by safe source inspection. Central source is restricted to the selected `config/config.yaml` Runtime Config file and YAML Helper/Tool descriptors declared by that central config; `att-server.yaml` and sensitive credential/key paths are excluded. It requires active `SYSTEM_SETTINGS_WRITE` (ADMIN) and the independent deployment switch `server.centralConfigurationEditingEnabled: true`, which defaults to `false`.

Successful authoring responses contain exact UTF-8 `text`, `logicalPath`, `present`, a SHA-256 `revision`, and a quoted `sourceEtag`; the same content ETag is returned in the HTTP `ETag` header. Responses use `Cache-Control: private, no-store`. The Server repeats identity/role, canonical root, registry, path allowlist, symlink, size, UTF-8, and protected-secret checks for each read. Protected credentials and keys fail closed even when ordinary masking is disabled. A `redacted` query parameter is not accepted. Unauthorized callers receive `401`/`403`; unavailable or unapproved paths return `404`; a source/root race returns `409`; and an over-limit file returns `413`.

Package inspection treats absent optional `templates/`, `testcase/`, and `load/` roots as empty inventories. A present root that is not a readable directory or that escapes the canonical Package returns HTTP `422 ATT-RESOURCE-ROOT-INVALID` with a safe `field` identifying the affected root. Other Worker validation errors return `422 ATT-RESOURCE-VALIDATION-FAILED`; neither response includes an absolute path or parser detail.

## Package configuration inspection

Configuration inspection uses the same bounded inspection Worker as package resources. These read-only endpoints use the resolved authenticated or anonymous identity:

```http
GET /api/v1/configuration/declared
GET /api/v1/configuration/effective?environment=SIT
GET /api/v1/configuration/compare?left=SIT&right=UAT&section=dbhelpers
GET /api/v1/configuration/files?path=config&limit=100
GET /api/v1/configuration/source?path=config/config.yaml
GET /api/v1/packages/payments/configuration?view=declared
GET /api/v1/packages/payments/configuration/effective?environment=SIT
GET /api/v1/packages/payments/configuration/effective?environment=SIT&section=dbhelpers&offset=0&limit=50
GET /api/v1/packages/payments/configuration/effective?environment=SIT&section=dbhelpers&includeAllFields=true
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&section=dbhelpers
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&section=dbhelpers&includeInherited=true&offset=0&limit=50
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&section=dbhelpers&includeAllFields=true
GET /api/v1/packages/payments/configuration/source?path=templates%2Fpayment%2Fpayload%2Frequest.xml&origin=flows%2Fpayment.yaml
GET /api/v1/packages/payments/configuration/testdata/accounts/records?environment=SIT&offset=0&limit=50
GET /api/v1/packages/payments/configuration/testdata/generatedAccounts/records?environment=SIT&offset=0&limit=50&previewGenerated=true
```

The declared view returns the schema version, safe global fields, configured profiles, and for each configuration section its declared or absent state, entry count, and profile inheritance or replacement state. It does not return helper field values. Effective views use `FrameworkConfigLoader` to select the environment and return safe helper, Tool, and Testdata descriptor metadata. Omit `environment` to inspect a root-only package or use the configured default. The first effective response returns section counts; request a section by its ID (`dbhelpers`, `mqhelpers`, `sshhelpers`, `httphelpers`, `tools`, or `testdata`) to read its entries. Effective section and comparison field responses accept `offset` and `limit` (1–100; default 50) and return `total` and `nextOffset` for paging. Inspection never connects to DB, MQ, HTTP, or SSH services and never returns unrestricted configuration YAML. Testdata records are available only through the bounded, configured-descriptor preview endpoint below.

```http
GET /api/v1/packages/payments/configuration/testdata/{descriptorId}/records?environment=SIT&offset=0&limit=50
```

The descriptor must be configured for the selected environment. Records are paged (1–100 per response); credentials, authentication material, and classified personal-data fields are always masked, including in anonymous mode. Ordinary values remain visible by default. When `redaction.enabled` is true, the configured sensitive paths and field patterns are masked as well. The masking switch cannot authorize disclosure of protected values. Unknown or unconfigured descriptors return `404`.

Comparison requires a section ID returned by the declared or effective view. The Server verifies that ID, filters both profiles to that section, and only then paginates. By default, it compares profile-sourced values; `includeInherited=true` also includes inherited and global values with their origins. `includeAllFields=true` adds otherwise-omitted non-secret DB properties and evidence fields, and requires active `SYSTEM_SETTINGS_WRITE` (ADMIN). These responses are not cached and are audited. This prevents unrelated sections from leaking into later pages. A sensitive field is returned as `state: "hidden"` with `change: "hidden"`; the response does not reveal whether its value is equal or different. Absolute local paths, credentials, server-owned secrets, and unrestricted descriptor content are not returned. Non-credential URLs and MQ host/port values can be visible in Effective/Compare when masking is disabled; HTTP header maps and SSH host/user/port/identity-file values remain protected in every view. Enabling masking also hides configured operational paths and fields.

The `source` endpoint resolves eligible package-local text references against their origin under `safe-package-files`; `allowlist-only` requires the existing exact package-relative `server.inspection.safeTextSources` entries. A linked request must include the opaque Server-issued `referenceId` returned by its authorized parent source; caller-supplied path and origin are insufficient. External scripts are available only through their exact canonical per-package allowlist under a configured external root, and their absolute paths are never returned. This remains a bounded source endpoint, not an arbitrary file browser. Testdata record previews require an effective descriptor ID. Literal records are paged with `limit` from 1 to 100 (the UI requests 50 at a time). Generated descriptors return `state: "preview-required"` and no records unless `previewGenerated=true` is explicitly requested. The same inspection identity, redaction policy, worker, source-size, response-size, queue, and timeout limits apply.

Configuration responses include `view`, `state`, `schemaVersion` when available, and `diagnostics`. Paged responses also include `section`, `offset`, `limit`, `total`, and `nextOffset`. An invalid configuration returns HTTP 200 with `state: "invalid"` and a stable diagnostic; malformed profile parameters return `400`. Unknown packages return `404`, oversized responses return `413`, a full inspection queue returns `503`, and an inspector timeout returns `504`. The same response-size, queue, and timeout limits used by resource inspection apply.
