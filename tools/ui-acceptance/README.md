# ATT UI browser acceptance checks

This development-only harness serves the built Vite UI at `/att/ui/` and provides synthetic responses for the existing `/att/api/v1/` endpoints. Build the static assets first with `mvn -B -ntp -pl att-web -am package`. It needs Python 3 and local Chromium, Firefox, and WebKit browsers. Node.js and npm are needed only to build or run these workstation checks; they are not part of ATT's WAR, installation, or production runtime.

## Install the development dependencies

From this directory, install the pinned test-only packages:

```sh
npm ci
npx playwright install chromium firefox webkit
```

The acceptance harness pins direct and transitive dependencies in `package.json` and `package-lock.json`. `npm ci` installs the test-only tools reproducibly. This is a workstation/CI dependency only; the WAR and Tomcat startup do not run npm.

If Chrome is already installed, set `ATT_UI_CHROME_PATH` to its executable to use it instead of Playwright's browser download. Set `PYTHON` if Python is not available as `python3`.

## Run browser checks

Run the smoke and accessibility checks with:

```sh
npm run smoke
```

The script covers static resources below the non-root `/att/` context, loaded/empty/error/loading dashboard states, the package/jobs/load route aliases, selected navigation and breadcrumb state, skip-link keyboard behavior, uncaught browser errors, and axe-core WCAG 2.0/2.1/2.2 A/AA rules. It reports selectors for any axe violations.

The fixture prefers the packaged Maven UI, then `att-web/frontend/dist`, and falls back to static login and legacy resources from `att-web/src/main/resources/META-INF/resources/ui`, matching the WAR resource layout. Set `ATT_UI_DIST` to a built Vite output directory to test a build stored outside the repository worktree; static login and legacy assets still come from the source resource directory.

The issue-216 integration check also runs Playwright against the packaged WAR while `ServerWarUiIT` has authenticated stock Tomcat mounted at the non-root `/tools/att` context. It uses the real API without route interception to open a Flow's server-projected XML reference, inspect allowed and withheld configuration sources, preview generated Testdata, and open Quick Load at a tablet viewport. CI installs this harness and its browsers before Maven, then sets `ATT_UI_SERVER_PLAYWRIGHT=1` for `mvn clean verify`. To run it locally, install the harness and browser dependencies as above, then run `ATT_UI_SERVER_PLAYWRIGHT=1 mvn -B -ntp clean verify` from the repository root.

The #236/#243 packaged archive check uses `ServerWarUiIT#packagedWarPublishesRunAndMultiWorkloadLoadArchives` and `artifact-archive-smoke.cjs`. Real RUN and multi-workload LOAD jobs produce the fixtures. The Java test compares every published ZIP entry with the authorized preview response, validates extracted relative-link targets, checks cross-USER access and denied identities, and waits for loose-output cleanup. With `ATT_UI_SERVER_PLAYWRIGHT=1`, the browser opens Studio as another USER, downloads the same finalized ZIP, checks the read-only text viewer and opaque-origin report iframe, follows online report links with back/forward, and opens extracted reports over `file://` with HTTP(S) requests blocked. Offline navigation, self-contained styles and built-in RUN search controls must work without ATT Server access. This check does not prove arbitrary detached-process containment or all failure/concurrency cases.

Run the Scheduled Load workflow and accessibility check with:

```sh
npm run smoke:scheduled-load
```

This flow covers server-computed previews, create/edit/pause/resume, explicit Run Now and delete confirmation, occurrence history and job links, disabled/authentication/role-error states, keyboard palette and shortcuts, light/dark preference, and 1280/768/390-pixel layouts.

Run the cross-browser responsive and screenshot check with:

```sh
npm run smoke:cross-browser
```

It exercises Chromium, Firefox, and WebKit at 1280, 768, and 390 pixels in light and dark themes, checks horizontal overflow and the tablet/phone schedule cards, runs axe at every viewport, and compares each capture with its committed browser/viewport/theme baseline for the current OS. Separate macOS and Linux baselines avoid treating platform font metrics as UI regressions. CI also uploads the generated screenshots under `tools/ui-acceptance/artifacts/scheduled-load/` for review. The comparison ignores per-channel pixel differences up to 32 and allows up to 4% of pixels to differ; larger differences fail with per-capture counts.

The hosted CI workflow runs on `ubuntu-latest`, which selects the committed `linux` baseline. Local macOS runs select `darwin`. The harness checks the selected baseline manifest's platform and requires all 18 captures; a missing or mismatched platform baseline fails instead of falling back to another platform. Baseline updates write only to the directory for the current Node.js platform.

Failure output is split into three artifacts:

- `visual-differences.json` records the browser, viewport, theme, changed pixel count, and changed ratio for each failed capture.
- `visual-differences.md` renders the same table for review, and the same table is appended to the GitHub Actions job summary.
- The capture directory keeps the actual PNGs beside the report so the baseline and current rendering can be compared directly.

## Run the full matrix for a pull request

Pull-request CI stays Chromium-only for fast feedback. To run the full
Chromium/Firefox/WebKit matrix against a PR head **before merging**, use either
supported path:

1. Add the `full-browser-suite` label to the pull request. The label event
   reruns CI on the current head with `FULL_BROWSER_SUITE=true`.
2. Start **CI** from the Actions tab with **Run workflow**, select the PR branch,
   and keep `full_browser_suite` enabled.

The job summary always states whether the run used the quick Chromium smoke set
or the full 18-capture matrix. A green quick PR job does not certify the full
matrix; use one of the paths above for Schedules or shared UI-shell changes.

## Review and refresh a baseline

After an intentional Scheduled Load visual change, inspect the failure report
and actual captures first. Then update the 18 baselines for the current OS with
a review rationale:

```sh
ATT_UI_VISUAL_BASELINE_REASON="Describe the reviewed UI change" \
ATT_UI_UPDATE_VISUAL_BASELINES=1 npm run smoke:cross-browser
```

Review the resulting PNGs and `manifest.json` before committing the matching
platform directory under `tools/ui-acceptance/visual-baselines/scheduled-load/`.
The manifest records the review rationale. Baseline updates without a reason
fail instead of silently accepting visual drift.

When Linux baselines must be refreshed, dispatch **CI** with
`update_visual_baselines=true` and `full_browser_suite=true` on the branch under
review. Download the `scheduled-load-visual-baselines-*` artifact, inspect every
PNG against the previous baseline, and commit the reviewed Linux directory from
that artifact. Do not copy captures from another platform.

Run the screenshot capture separately:

```sh
npm run screenshots
```

It writes 10 desktop screenshots at 1440 × 1000 in light and dark themes. The gallery covers the Dashboard, Explorer/Debug, Load Builder, Job Results, and Scheduled Load across all four delivery phases. A metadata manifest records each route, viewport, theme, fixture, browser, and Playwright version under `docs/system-design/ui-shell-baseline/`. These are synthetic demo captures, not production data or evidence that Tomcat was launched.

The phase screenshot gallery is linked from [`ui-shell-architecture.md`](../../docs/system-design/ui-shell-architecture.md#phase-screenshots), with each capture stored beside the manifest.

## Baseline and rollback

Review the four PNGs beside the annotated reference in `docs/system-design/ui-shell-architecture.md`. Regenerate them after intentional shell or token changes, then inspect both viewport widths and themes before updating the baseline. `manifest.json` records browser and Playwright versions.

To remove only this acceptance harness and its generated baseline, revert `tools/ui-acceptance/` and `docs/system-design/ui-shell-baseline/`. To roll back the app shell itself, follow the `Migration and rollback` section in `docs/system-design/ui-shell-architecture.md`; retain the earlier WAR UI regression test with that change.

The general dashboard and workflow smoke scripts use a local Python API fixture. The opt-in `ServerWarUiIT` Playwright step is separate: it runs inside the packaged-WAR test while authenticated Tomcat and the Server API are live under `/tools/att`.
