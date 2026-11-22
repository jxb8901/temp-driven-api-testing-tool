<!-- att-doc-status: current release=4.4.1 -->

# Package-scoped shared editors acceptance

Run this matrix against a deployed staging Server that includes the Issue [#387](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/387) shared-editors build. Fixture-backed browser smoke and ordinary hosted CI do not satisfy this gate.

## Prepare the deployment

1. Record the exact WAR/build head SHA and the staging base URL.
2. Enable `server.packageConfiguration.sourceEditingEnabled: true` and keep `server.centralConfigurationEditingEnabled` at its intended deployment value.
3. Register Packages such as `alice` (owner-only) and `fpp-common` with `editors: [alice, bob]`. Do not put editor grants in Package-owned `package.yaml`.
4. Create active local or container USER identities for Alice, Bob, and Carol, plus an ADMIN-only identity. Keep passwords and tokens out of the evidence note.

## Acceptance matrix

| Check | Expected outcome | Evidence to attach |
| --- | --- | --- |
| Private Package remains owner-only | Alice edits `alice`; Bob and Carol receive 403 for privileged source and mutation APIs | Package IDs, redacted actors, HTTP status codes |
| Shared Package Online Edit | Alice and Bob complete Stage → Validate → Review → Publish on `fpp-common` | Package ID, Change Set IDs, published revision |
| Shared Package ZIP Import | Alice or Bob imports a Package ZIP through the same Change Set gates and publishes successfully | ZIP Change Set ID, published revision |
| Non-editor inspection | Carol can use safe inspection and RUN/Debug/LOAD/Jobs/Schedules; privileged authoring APIs return 403 | Route list with status codes |
| Multi-Package grant | A USER listed on several Packages can edit each granted Package without gaining ownership of other Packages | Package capability responses |
| No elevated rights | Editor grants do not add `SYSTEM_SETTINGS_WRITE`, Central Runtime Config authoring, or ADMIN rights; ADMIN-only identities remain denied for Package authoring and ordinary RUN/Jobs/Schedules | `/auth/capabilities`, denied Central/Package routes |
| Revocation | Removing Bob from `editors` clears privileged UI/source state, denies publish, and leaves already published data intact | Registry revision before/after, denied Change Set ID |
| Concurrent editors | Two editors stage from one revision; the first publish succeeds and the second receives 409 | Both Change Set IDs and 409 body |
| Studio Package binding | Authoring UI shows the bound Package ID and hides mutation controls for non-editors | UI note or screenshot path |
| Accepted Jobs and Schedules | While accepted, queued, or running Jobs and pinned Schedules exist for the shared Package, a trusted editor publish leaves their pinned Package roots, source revisions, and Schedule `expectedRevisionDigest` unchanged; a later Job sees the new revision | Job IDs, before/after revision digests, Schedule ID |

## Automated coverage that still requires this gate

- Hosted CI and `smoke:source-authoring` cover fixture-backed shared-editor flows, concurrent 409, and revocation clearing.
- Server integration tests cover trusted editor authorization and the Job/Schedule pin behavior under a shared-editor publish.
- This operator matrix remains required before closing #387: attach the staging head SHA, Package IDs, redacted identities/roles, observed API/UI outcomes, and Job/Schedule IDs on the issue.

## Pass criteria

Treat the gate as passed only when every row above has concrete staging evidence for the same build head. Do not close #387 on fixture smoke or CI alone.
