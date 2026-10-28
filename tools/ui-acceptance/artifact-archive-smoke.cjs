const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { launchBrowser } = require('./browser-support.cjs');

function artifactCategory(path) {
  const lower = path.toLowerCase();
  const leaf = lower.slice(lower.lastIndexOf('/') + 1);
  if (lower.includes('/checkpoints/')) return 'Recovery checkpoints';
  if (lower.includes('/details/') || lower.includes('/timeseries/') || leaf.startsWith('series-') || leaf.includes('metric')) return 'Metrics and time series';
  if (leaf.includes('summary') || leaf.includes('manifest') || lower.endsWith('/report/index.html')) return 'Summaries and manifests';
  if (lower.includes('/evidence/') || lower.includes('/samples/') || lower.includes('/events/') || lower.includes('/failures/')
    || lower.includes('/iterations/') || lower.includes('/executions/') || leaf.includes('iteration')) return 'Execution evidence';
  return 'Other artifacts';
}

// Fixtures come from real RUN/LOAD jobs in ServerWarUiIT, not a mocked API.
async function main() {
  const [origin, fixturePath] = process.argv.slice(2);
  const fixtures = JSON.parse(await fs.readFile(fixturePath, 'utf8'));
  assert.equal(fixtures.length, 2, 'Exercise both RUN and multi-workload LOAD');
  const browser = await launchBrowser();
  const online = await browser.newContext({ httpCredentials: { username: 'colleague', password: 'secret', send: 'always' } });
  const offline = await browser.newContext();
  for (const context of [online, offline]) {
    context.setDefaultTimeout(15_000);
    context.setDefaultNavigationTimeout(20_000);
  }
  const unexpectedNetwork = [];
  await offline.route(/^https?:/, route => { unexpectedNetwork.push(route.request().url()); return route.abort(); });
  try {
    const page = await online.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    for (const fixture of fixtures) {
      process.stdout.write(`Checking archive ${fixture.jobId}\n`);
      await page.goto(`${origin}/tools/att/ui/index.html#/jobs/${fixture.jobId}`);
      await page.getByRole('tab', { name: 'Metrics', exact: true }).click();
      const metrics = page.getByRole('region', { name: 'Artifact archive metrics', exact: true });
      await metrics.locator('.att-status-badge').filter({ hasText: 'READY' }).waitFor();
      assert.equal(await metrics.locator('.att-table-scroll .att-data-table').count(), 1);
      for (const [width, theme] of [[1280, 'light'], [390, 'light'], [390, 'dark']]) {
        await page.setViewportSize({ width, height: 900 });
        await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
        const geometry = await metrics.evaluate(section => {
          const table = section.querySelector('table');
          const value = table.querySelector('td');
          return { alignment: getComputedStyle(value).textAlign, weight: getComputedStyle(value).fontWeight,
            tableWidth: table.getBoundingClientRect().width, scrollWidth: section.querySelector('.att-table-scroll').clientWidth,
            overflow: document.documentElement.scrollWidth > window.innerWidth };
        });
        assert.equal(geometry.alignment, 'right');
        assert.equal(geometry.weight, '400');
        assert.ok(geometry.tableWidth >= geometry.scrollWidth - 2);
        assert.equal(geometry.overflow, false, 'Metrics must not overflow the page');
        await page.evaluate(() => window.scrollTo(0, 0));
        await page.screenshot({ fullPage: true, animations: 'disabled', path: path.join(process.env.ATT_UI_SCREENSHOT_DIR || path.dirname(fixturePath), `archive-metrics-${fixture.jobId}-${width}-${theme}.png`) });
      }
      await page.setViewportSize({ width: 1280, height: 900 });
      await page.getByRole('tab', { name: 'Artifacts', exact: true }).click();
      const archive = page.getByRole('region', { name: 'Job artifact archive', exact: true });
      await archive.getByText('Artifact Archive: READY', { exact: true }).waitFor();
      const downloadPromise = page.waitForEvent('download');
      const archiveLink = archive.getByRole('link', { name: /^Download Artifacts \(\.zip\) — / });
      assert.equal(await archiveLink.count(), 1, 'Only one finalized ZIP download link is shown');
      assert.equal(await archive.locator('a[href*="/artifacts/archive.zip"]').count(), 1,
        'Artifact viewer links are not archive download links');
      const archiveBytes = (await fs.stat(fixture.archivePath)).size;
      assert.equal(await archiveLink.getAttribute('title'), `${archiveBytes} bytes`);
      await archiveLink.click();
      const download = await downloadPromise;
      assert.equal(await download.failure(), null);
      assert.deepEqual(await fs.readFile(await download.path()), await fs.readFile(fixture.archivePath));

      const text = fixture.entries.find(entry => /\.(log|ya?ml|json|txt)$/.test(entry.path));
      assert.ok(text, 'Real jobs must include text artifacts');
      // The first index page is bounded; request more pages until the chosen entry is visible.
      await archive.getByText(new RegExp(`^${artifactCategory(text.path)} ·`)).click();
      const preview = archive.getByRole('button', { name: `Preview ${text.path}`, exact: true });
      while (!await preview.count()) {
        const more = archive.getByRole('button', { name: 'Load more artifacts', exact: true });
        assert.equal(await more.count(), 1, `Missing preview for ${text.path}`);
        await more.click();
        await more.waitFor({ state: 'attached' }).catch(() => {});
        await page.waitForFunction(() => !document.querySelector('[aria-label="Job artifact archive"] button[disabled]'));
      }
      await preview.click();
      const viewer = page.getByRole('region', { name: 'Artifact preview', exact: true });
      await viewer.locator('.cm-content').waitFor();
      assert.ok(await viewer.locator('.cm-lineNumbers').count(), 'Preview shows line numbers');
      assert.equal(await viewer.locator('.cm-content').getAttribute('contenteditable'), 'false');
      await viewer.getByRole('button', { name: 'Close preview', exact: true }).click();

      for (const report of fixture.reports) {
        const reportPath = report.path;
        process.stdout.write(`Checking online/offline report ${reportPath}\n`);
        const reportUrl = `${origin}/tools/att/api/v1/jobs/${fixture.jobId}/artifacts/view/${reportPath.split('/').map(encodeURIComponent).join('/')}`;
        const reportResponse = page.waitForResponse(response => response.url() === reportUrl && response.status() === 200);
        await archive.getByRole('button', { name: `View Report: ${reportPath}`, exact: true }).click();
        await reportResponse;
        const iframe = page.locator(`iframe[title=${JSON.stringify(`Report: ${reportPath}`)}]`);
        await iframe.waitFor();
        assert.equal(await iframe.getAttribute('sandbox'), 'allow-scripts');
        assert.equal(await iframe.getAttribute('src'), null, 'No anonymous iframe API navigation');
        assert.ok((await iframe.getAttribute('srcdoc')).includes('Content-Security-Policy'));
        const frame = await (await iframe.elementHandle()).contentFrame();
        // Chromium can retain a blank frame bookkeeping URL for srcdoc; inspect the document itself.
        await frame.waitForFunction(() => location.href === 'about:srcdoc');
        await frame.locator('body').waitFor();
        assert.equal(await frame.evaluate(() => location.href), 'about:srcdoc');
        process.stdout.write(`  Online report loaded: ${reportPath}\n`);
        assert.equal(await frame.evaluate(() => { try { return !!parent.document.body; } catch { return false; } }), false,
          'The report must not read the application DOM');
        const relative = await frame.locator('a[data-att-report-link]').evaluateAll(links => links.map(link => link.getAttribute('data-att-report-link')));
        assert.ok(relative.length > 0, `Report ${reportPath} must retain bridged relative navigation`);
        const destination = new URL(relative[0], reportUrl).href;
        const linkedResponse = page.waitForResponse(response => response.url() === destination && response.status() === 200);
        await frame.locator('a[data-att-report-link]').first().click();
        await linkedResponse;
        const reportViewer = viewer.getByRole('region', { name: 'Isolated authenticated report preview', exact: true });
        const destinationPath = decodeURIComponent(new URL(destination).pathname.split('/artifacts/view/')[1]);
        await reportViewer.getByText(destinationPath, { exact: true }).waitFor();
        if (/\.html?$/i.test(destinationPath)) await reportViewer.locator('iframe').waitFor();
        else await reportViewer.locator('.cm-content').waitFor();
        const backResponse = page.waitForResponse(response => response.url() === reportUrl && response.status() === 200);
        await reportViewer.getByRole('button', { name: 'Report back', exact: true }).click();
        await backResponse;
        await reportViewer.locator(`iframe[title=${JSON.stringify(`Report: ${reportPath}`)}]`).waitFor();
        const forwardResponse = page.waitForResponse(response => response.url() === destination && response.status() === 200);
        await reportViewer.getByRole('button', { name: 'Report forward', exact: true }).click();
        await forwardResponse;
        await reportViewer.getByText(destinationPath, { exact: true }).waitFor();
        process.stdout.write(`  Online relative navigation and history passed: ${reportPath}\n`);
        await viewer.getByRole('button', { name: 'Close preview', exact: true }).click();

        const file = await offline.newPage();
        const offlineErrors = [];
        file.on('pageerror', error => offlineErrors.push(error.message));
        const fileUrl = pathToFileURL(path.join(fixture.offlineRoot, reportPath)).href;
        await file.goto(fileUrl);
        await file.locator('body').waitFor();
        process.stdout.write(`  Offline report loaded: ${reportPath}\n`);
        assert.ok(await file.locator('style').count(), 'The extracted report retains self-contained styling');
        assert.deepEqual(await file.locator('a[href]').evaluateAll(links => links.map(link => link.getAttribute('href')).filter(href => href && !href.startsWith('#'))), relative);
        const controls = file.locator('#caseSearch');
        if (await controls.count()) {
          await controls.fill('no-case-matches-this-sentinel');
          assert.equal(await file.locator('#caseTable tbody tr:not([hidden])').count(), 0, 'Reviewed report filters work offline');
          await controls.fill('');
          assert.ok(await file.locator('#caseTable tbody tr:not([hidden])').count() > 0);
        }
        for (const href of relative) {
          await file.goto(fileUrl);
          // file:// has no HTTP Content-Type. Chromium may hand YAML to its native downloader.
          // Verify the actual relative target bytes in that case, rather than overriding browser policy.
          const targetUrl = new URL(href, fileUrl).href;
          process.stdout.write(`  Following offline link: ${href}\n`);
          const followed = Promise.any([
            file.waitForEvent('download').then(download => ({ download })),
            file.waitForURL(targetUrl, { waitUntil: 'commit' }).then(() => ({ navigated: true })),
          ]);
          await file.locator('a[href]').evaluateAll((links, value) => links.find(link => link.getAttribute('href') === value).click(), href);
          const result = await followed;
          assert.ok(targetUrl.startsWith(pathToFileURL(fixture.offlineRoot + path.sep).href));
          if (result.download) {
            assert.equal(result.download.url(), targetUrl);
            assert.equal(await result.download.failure(), null);
            assert.deepEqual(await fs.readFile(await result.download.path()), await fs.readFile(new URL(targetUrl)));
          } else assert.equal(file.url(), targetUrl);
        }
        // Chromium displays text/log targets as a navigation. Closing the page
        // while that file:// document is current intermittently never returns.
        await file.goto(fileUrl);
        assert.deepEqual(offlineErrors, [], 'Extracted reports must work without application scripts');
        await file.close();
      }
    }
    assert.deepEqual(errors, [], 'The real archive viewer must not throw browser errors');
    assert.deepEqual(unexpectedNetwork, [], 'Extracted reports must not need a running ATT Server');
    process.stdout.write('RUN/LOAD archive browser smoke passed: cross-USER ZIP, text viewer, sandboxed reports, back/forward, extracted offline links and controls.\n');
  } finally {
    await online.close(); await offline.close(); await browser.close();
  }
}

main().catch(error => { process.stderr.write(`${error.stack || error}\n`); process.exitCode = 1; });
