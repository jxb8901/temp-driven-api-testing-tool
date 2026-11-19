<!-- att-doc-status: current release=4.4.0 -->

# Author and publish source in Studio

Use this guide to edit Package resources or shared Runtime Config in ATT Studio. You will stage a candidate, validate it, review its changes, and publish it through the Server.

## Prerequisites

- Sign in to the ATT Server that owns the selected Package or Runtime Config.
- To edit a Package source, use an active account with source-editing access for that Package. The default `default` Package is not editable.
- To edit shared Runtime Config, use an `ADMIN` account. The Server must have `server.centralConfigurationEditingEnabled: true`; changing this deployment setting requires a Server restart.
- Keep related source files in the selected Package. Global Runtime Config owns shared Data Source connections and security policy; each Package owns its Metric Sources, Groups, and Profiles.

## Use the source workflow

1. In **Explorer**, open the Package and choose an executable resource or a supporting source. The supporting-source area includes the Package Manifest, Testdata, the File catalog, and Package SUT Metrics.
2. Open the source you intend to change. Safe inspection is read only. The authoring workspace appears only when the Server returns complete, unredacted source bytes and confirms your edit permission.
3. Choose **Edit** or **Create**, then make the change. For related files, add each operation to the same candidate before you stage it.
4. Choose **Stage candidate** or **Save Draft**. Staging creates a private Change Set; it does not modify published files.
5. Choose **Validate** to validate that candidate. This is different from **Validate Package**, which inspects published Package content and does not validate or publish your draft.
6. Review every changed file, inbound reference, and generated file. For shared Runtime Config, review the current Config Impact report and its affected Packages, Environments, and Schedules.
7. Choose **Review changed files**, then choose **Publish** only when the candidate and impact report are current. If an administrator acknowledgement is required, bind it to the current report before publishing.
8. Check the publication result. For Runtime Config, confirm that the Active revision matches the Published revision and that activation state is `ACTIVE` before starting new work.

The Server owns authorization, validation, revision checks, and publication. If the source changes while you edit, Studio preserves your local text but blocks staging against the old revision. Copy the changes you still need, reload the latest source, and reapply them. Studio does not silently rebase or merge full-file edits.

## Edit a Package Manifest

1. In **Explorer**, open **Supporting sources**, then choose **Package manifest** for the selected Package.
2. Use **Effective** to inspect resolved Package configuration, **Compare with Global** to compare Package and Global values, and **Declared** to inspect declared values. Choose **Source** to view or edit `package.yaml`; editing is available only when the Server returns the complete authorized source and grants Package source write access.
3. Edit the YAML and choose **Create Manifest draft**. This stages a private candidate without changing the published Package.
4. Choose **Validate Manifest draft**, then **Review validated Manifest**. Compare the published source with the candidate in the review panel, including its changed path and base Manifest revision.
5. Choose **Publish reviewed Manifest** and confirm. The new Manifest applies to future Package configuration resolution. This workflow publishes Package-owned configuration; shared Runtime Config has its own ADMIN workflow.
6. To inspect revisions, load Manifest history. **Stage rollback** creates a new draft from a retained revision; validate, review, and publish that draft before it becomes current. The published revision digest is available under **Published Manifest revision (advanced)** after publication.

If publication returns `409`, the source changed after the candidate was prepared. Discard the stale draft, refresh the Manifest, and reapply the changes to a new candidate.

## Direct filesystem edits and published state

Treat Package files and the Runtime Config deployment directory as deployment inputs. Editing them directly does not create an approved Change Set, acknowledgement, or online activation. Package edits may be detected as a new source revision and will make existing drafts stale. A direct edit to the central Runtime Config mirror is not adopted as the active revision; use the ADMIN Validate → Review Impact → Publish flow. Do not report a filesystem copy as Published or Active.

If an out-of-band edit is needed for recovery, stop new Job admission when Runtime Config is involved, preserve the current files, then reconcile the change through a reviewed Studio candidate. Do not edit or remove the Server-managed revision store by hand. Git is not required for source revisioning or publication; teams may keep an external Git workflow if they need it.

## Edit a Template or Flow and its referenced File

1. In the Package **File catalog**, search by path, resource, or Action ID. The catalog searches the Server's revision-fenced Package index rather than a partial browser scan.
2. Check the coverage state. When coverage is `PARTIAL`, some references may be omitted; do not treat an absent result as proof that a file is unused.
3. Open a listed resource or Action call site to inspect its safe source. Open a File only when the Server reports it available and returns an authorized source reference.
4. Add the Template or Flow edit and the File change to one candidate. Use the current-file action for the selected source and add related files before staging.
5. Validate and review the complete changed-file list, including inbound references, then publish the single candidate.

The File catalog groups references by canonical Package-relative path. It shows availability and call sites without creating a separate File copy for each Action. Inspection does not evaluate `${...}` or `#{...}` expressions.

## Upload a Testcase workbook and generate its snapshot

1. Open the Testcase authoring workspace for the selected Package-relative XML path.
2. Select **Create new workbook** or **Replace existing workbook**, and select the matching sidecar operation.
3. Upload the workbook and YAML sidecar together. Studio stages both in one private Change Set.
4. Choose **Generate Testcase snapshot**. The XML snapshot is generated into the draft; inspection and upload do not generate it implicitly.
5. Validate the draft, review the workbook, sidecar, and generated XML changes, then publish.

If snapshot generation or validation fails, fix the source and retry before publishing. The published Package remains unchanged until Publish succeeds.

## Create Testdata

1. Open **Testdata** for the selected Package and Environment, then choose **Create Testdata descriptor**.
2. Enter a unique Testdata ID. Choose literal JSON records or a generated sequence, then choose its selection and exhaustion policies.
3. Choose **Prepare atomic candidate**. Studio prepares `testdata/<ID>.yaml` and the Package Manifest inventory change together.
4. Review the generated descriptor and Manifest operations in the source workspace. Stage, validate, review, and publish both files together.

The guided creator requires an editable `att-package/v2` Manifest. Use the advanced Manifest editor when the Package needs another inventory change.

## Edit SUT Metrics

Open **SUT Metrics** under the selected Package's supporting sources to edit Package-owned Metric Sources, Groups, and Profiles. The Package Manifest inventories those files under `load/sut-metrics/`.

Use shared Runtime Config for Data Source connections and their security policy. A Package author does not publish to global Runtime Config. Keep credentials in the Server's configured secret provider; do not paste credentials into Package-owned metric definitions.

## Publish shared Runtime Config

1. Open **Shared Configuration** and select **Source**. Only an authorized `ADMIN` with the deployment editing switch enabled can read or edit the complete source.
2. Stage the Runtime Config change and complete **Validate** and **Review changed files / Impact**.
3. Confirm the Impact report is `AVAILABLE`, has `COMPLETE` coverage, and is not `BLOCKED`. Review every affected Package, Environment, and Schedule page.
4. Acknowledge the exact report when Studio requires it, then choose **Publish**.
5. Confirm the publication result reports `ACTIVE` and the same Published and Active revision. A failed or unavailable activation means the new Config is not ready for new work; follow the Server administrator's recovery procedure.

The Server rejects fields that are not eligible for online publication. Deployment, bootstrap, security, and other Server settings remain managed through the deployment configuration and may require a restart.

## Review history and stage a restore

1. Open an authorized source and choose **History / Restore**.
2. Select a retained transaction and compare its changed files. The Server may withhold protected source contents while leaving safe path and change metadata visible.
3. Choose **Stage restore as draft**. Studio creates a new candidate from the retained snapshot; it does not rewind the active source in place.
4. Review the complete candidate, validate it, and complete the normal review and publish steps. Central Config restore also requires a fresh Impact report and activation approval.

## Source snapshot storage and retention

The Server stores immutable source revisions in a private sibling directory beside each registered Package or central Config root. Its name follows `.<source-root-name>.att-source-revisions-<identity>/`; it contains active pointers, retained `revisions/`, and execution copies in `executions/`. Keep this directory on the same managed volume as its source root, include it in capacity monitoring and backups, and do not copy, edit, or delete individual entries by hand.

History cleanup retains up to the latest 500 committed transactions per source scope. Older unreferenced revisions and execution copies are removed; the active revision and revisions held by durable Job or Schedule source leases remain pinned even when their history record ages out. The 500-record limit is not a byte quota. Source trees are bounded at 2 GiB and 50,000 files for Package snapshots, and 512 MiB and 20,000 files for the approved central Config closure; an individual source file is limited to 64 MiB in revision capture. New online edits have smaller limits: 4 MiB per file and 8 MiB per Change Set.

There is no configured aggregate disk quota for retained or pinned snapshots. Monitor free space on the source volume and the ADMIN `/metrics` source-history retention status. Alert when cleanup failures increase, cleanup has not succeeded for an expected interval, or free space approaches the deployment reserve. If the volume is full, revision capture or publication fails closed; restore capacity and let Server recovery/cleanup run before retrying. Pinned revisions must not be removed to make space.

## Recover from an error

| Status | What to do |
|---|---|
| `401` | Sign in again, then reload the source. |
| `403` | Ask the Package owner or Server administrator to verify your current role and source-editing policy. |
| `409` | Reload the latest source, compare the retained draft or local text, and stage a new candidate. |
| `413` | Reduce the source or upload to the Server's configured size limits. |
| `422` | Read the validation diagnostics, correct the listed source, and validate again. |
| Impact is unavailable, partial, or blocked | Do not publish central Config. Ask the Server administrator to restore complete Impact coverage or resolve the blocking condition. |

See [Explorer and functional execution](explorer-run.md) for resource inspection and execution. See [Server configuration](../../operations/server-configuration.md) for the deployment switches and ownership rules.
