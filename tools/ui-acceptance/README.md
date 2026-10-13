# ATT UI browser acceptance checks

This development-only harness serves the built Vite UI at `/att/ui/` and provides synthetic responses for the existing `/att/api/v1/` endpoints. Build the static assets first with `mvn -B -ntp -pl att-web -am package`. It needs Python 3 and a local Chrome/Chromium browser. Node.js and npm are needed only to build or run these workstation checks; they are not part of ATT's WAR, installation, or production runtime.

## Install the development dependencies

From this directory, install the pinned test-only packages:

```sh
npm ci
npx playwright install chromium
```

The acceptance harness pins direct and transitive dependencies in `package.json` and `package-lock.json`. `npm ci` installs the test-only tools reproducibly. This is a workstation/CI dependency only; the WAR and Tomcat startup do not run npm.

If Chrome is already installed, set `ATT_UI_CHROME_PATH` to its executable to use it instead of Playwright's browser download. Set `PYTHON` if Python is not available as `python3`.

## Run browser checks

Run the smoke and accessibility checks with:

```sh
npm run smoke
```

The script covers static resources below the non-root `/att/` context, loaded/empty/error/loading dashboard states, the package/jobs/load route aliases, selected navigation and breadcrumb state, skip-link keyboard behavior, uncaught browser errors, and axe-core WCAG 2.0/2.1/2.2 A/AA rules. It reports selectors for any axe violations.

Run the screenshot capture separately:

```sh
npm run screenshots
```

It writes 1440 × 1000 and 768 × 1000 light/dark browser viewport captures and a metadata manifest under `docs/system-design/ui-shell-baseline/`. The images use explicit synthetic fixture data and are comparison baselines, not production data or evidence that Tomcat was launched.

The current baselines are [`dashboard-1440-light.png`](../../docs/system-design/ui-shell-baseline/dashboard-1440-light.png), [`dashboard-1440-dark.png`](../../docs/system-design/ui-shell-baseline/dashboard-1440-dark.png), [`dashboard-768-light.png`](../../docs/system-design/ui-shell-baseline/dashboard-768-light.png), and [`dashboard-768-dark.png`](../../docs/system-design/ui-shell-baseline/dashboard-768-dark.png).

## Baseline and rollback

Review the four PNGs beside the annotated reference in `docs/system-design/ui-shell-architecture.md`. Regenerate them after intentional shell or token changes, then inspect both viewport widths and themes before updating the baseline. `manifest.json` records browser and Playwright versions.

To remove only this acceptance harness and its generated baseline, revert `tools/ui-acceptance/` and `docs/system-design/ui-shell-baseline/`. To roll back the app shell itself, follow the `Migration and rollback` section in `docs/system-design/ui-shell-architecture.md`; retain the earlier WAR UI regression test with that change.

These checks use a local Python fixture and real browser. They do not replace the Java `ServerWarUiIT` packaging test or the stock-Tomcat non-root-context deployment check. Run the Maven integration checks separately in the project's normal Java CI/development environment.
