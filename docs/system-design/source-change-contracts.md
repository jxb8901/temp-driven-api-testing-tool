# Source change contracts

This page maps the versioned source contracts to the modules that own their implementation. It is intended for the #254 runtime, #255 client, and #335 impact branches.

The shared Java contract is `att-source-management/v1` in `att-server-api`. It defines independent revision identities, validated candidates, effective Config projections, immutable source leases, publication hooks, client DTOs, and impact reports. The packaged [JSON Schema](../../att-server-api/src/main/resources/schemas/att-source-management-v1.schema.json) defines the stable `#255` wire shape.

## Ownership by branch

| Branch | Owns | Extension points | Keep out of its change set |
| --- | --- | --- | --- |
| #252/#253 | Whole-candidate Engine validation in `att-engine/src/main/java/att/validation/SourceCandidateValidator.java`; Engine-compatible Change Set adapter in `att-server/src/main/java/att/server/ChangeSetCandidateValidator.java`; draft, review, revision fence, and source publication in `att-server/src/main/java/att/server/ChangeSetPublisher.java` | `CandidateValidation`, `EffectiveResolution`, `PreCommitApprovalHook`, `PublicationLifecycleHook`, and `ImmutableSourceClosureStore` in `SourceManagementContracts` | Job snapshot persistence, queued dispatch, Schedule policy, browser editor, and cross-Package impact decisions |
| #254 | Implement the runtime side of `ImmutableSourceClosureStore`: exact revision reads, retain/release, stale fences, retirement, and explicit unavailable behavior. Connect leases to Job acceptance, queued dispatch, and Schedule snapshots. | `SourceLeaseRequest`, `SourceLease`, `VersionedSourceBytes`, `RevisionSet`, and `StaleSourceException` | Change Set publication and Engine effective resolution |
| #255 | Build owner and central ADMIN client flows on the versioned DTOs and JSON Schema. Preserve `NO_STORE` for privileged source, ETags, bounded pagination, and typed 403/409/413/422 handling. | `AuthorityContext`, `ChangeSetView`, `PrivilegedSourceView`, `SafeSourceView`, `ApiFailure`, `Page`, and the `PackageCategory` enum | Engine validators, source revision fencing, and publication transaction internals |
| #335 | Supply Config impact reports, freshness checks, acknowledgement binding, and activation/drain behavior. Integrate its provider with `ImpactReportProvider` and the Server Config precommit gate. | `ImpactFreshnessToken`, `ImpactReport`, `AffectedPackage`, `AffectedSchedule`, `ImpactAcknowledgement`, `PreCommitApprovalHook`, and `ActivationStatus` | A second Package-wide impact analyzer or a replacement Change Set publisher |

## Revision and data rules

Keep Package registration, Package business source, Change Set, Manifest, Config source, effective Config, Environment, Engine schema, and Schedule snapshot identities separate. Do not serialize authored source, descriptor values, credentials, or workbook cells inside revision or dependency projections.

`VersionedSourceBytes` is the only contract that carries authored bytes. It binds the bytes to a source revision, logical path, and verified SHA-256. A revision digest by itself does not grant byte access. Leases remain pinned to one immutable revision after a newer revision is published; a stale acquisition fails with `ATT-SOURCE-REVISION-STALE`.

Package IDs scope dependency identity. Two Packages may use the same local resource or Testdata ID without sharing a binding. `EffectiveResolution` reports Engine precedence and known direct or transitive references; it does not decide cross-Package impact.

## Availability and publication boundaries

The #335 report provider may be `UNAVAILABLE` or `STALE`. Clients must show that state and must not infer `COMPATIBLE`. A Config Change Set remains fail-closed until the provider approves the same candidate at the commit fence.

Published source and active runtime state are separate. A publication event identifies one coherent before/after revision set and its changed logical paths. Invalidation scopes name the affected Package and optional Environment. Activation status reports the published revision, active revision, and failure state independently.

## Contract tests

`SourceManagementContractsTest` is an independent consumer contract suite. Its fake runtime retains exact old bytes across publication and rejects stale reads. Its client checks owner versus central capabilities, privileged no-store, safe inspection, and typed failures. Its impact fake reports unavailability and refuses approval. Extend these fakes when a downstream branch changes a contract; keep production adapters in their owning modules.
