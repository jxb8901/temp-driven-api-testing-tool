const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { launchBrowser } = require('./browser-support.cjs');

// Fixtures come from real RUN/LOAD jobs in ServerWarUiIT, not a mocked API.
async function main() {
  const [origin, fixturePath] = process.argv.slice(2);
  const fixtures = JSON.parse(await fs.readFile(fixturePath, 'utf8'));
  assert.equal(fixtures.length, 2, 'Exercise both RUN and multi-workload LOAD');
  const browser = await launchBrowser();
  const online = await browser.newContext({ httpCredentials: { username: 'colleague', password: 'secret', send: 'always' } });
  const offline = await browser.newContext();
  const unexpectedNetwork = [];
  await offline.route(/^https?:/, route => { unexpectedNetwork.push(route.request().url()); return route.abort(); });
  try {
    const page = await online.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    for (const fixture of fixtures) {
      process.stdout.write(`Checking archive ${fixture.jobId}\n`);
      await page.goto(`${origin}/tools/att/ui/index.html#/jobs/${fixture.jobId}`);
      await page.getByRole('tab', { name: 'Artifacts', exact: true }).click();
      const archive = page.getByRole('region', { name: 'Job artifact archive', exact: true });
      await archive.getByText('Artifact Archive: READY', { exact: true }).waitFor();
      assert.equal(await archive.locator('a').count(), 1, 'Only the finalized ZIP has a download link');
      const downloadPromise = page.waitForEvent('download');
      await archive.getByRole('link', { name: 'Download Artifacts (.zip)', exact: true }).click();
      const download = await downloadPromise;
      assert.equal(await download.failure(), null);
      assert.deepEqual(await fs.readFile(await download.path()), await fs.readFile(fixture.archivePath));

      const text = fixture.entries.find(entry => /\.(log|ya?ml|json|txt)$/.test(entry.path));
      assert.ok(text, 'Real jobs must include text artifacts');
      // The first index page is bounded; request more pages until the chosen entry is visible.
      const preview = archive.getByRole('button', { name: `Preview: ${text.path}`, exact: true });
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
        await archive.getByRole('button', { name: `View Report: ${reportPath}`, exact: true }).click();
        const iframe = page.locator(`iframe[title=${JSON.stringify(`Report: ${reportPath}`)}]`);
        assert.equal(await iframe.getAttribute('sandbox'), 'allow-scripts');
        const frame = await (await iframe.elementHandle()).contentFrame();
        const reportUrl = `${origin}/tools/att/api/v1/jobs/${fixture.jobId}/artifacts/view/${reportPath.split('/').map(encodeURIComponent).join('/')}`;
        await frame.waitForURL(reportUrl);
        await frame.locator('body').waitFor();
        assert.equal(await frame.evaluate(() => { try { return !!parent.document.body; } catch { return false; } }), false,
          'The report must not read the application DOM');
        const relative = await frame.locator('a[href]').evaluateAll(links => links.map(link => link.getAttribute('href')).filter(href => href && !href.startsWith('#')));
        assert.ok(relative.length > 0, `Report ${reportPath} must retain real relative navigation`);
        const destination = new URL(relative[0], reportUrl).href;
        await frame.locator('a[href]').evaluateAll((links, href) => links.find(link => link.getAttribute('href') === href).click(), relative[0]);
        await frame.waitForURL(destination);
        assert.ok(frame.url().startsWith(`${origin}/tools/att/api/v1/jobs/${fixture.jobId}/artifacts/view/`));
        // Child-frame navigations participate in joint history but do not fire a main-frame load.
        await page.evaluate(() => history.back()); await frame.waitForURL(reportUrl);
        await page.evaluate(() => history.forward()); await frame.waitForURL(destination);
        await viewer.getByRole('button', { name: 'Close preview', exact: true }).click();

        const file = await offline.newPage();
        const offlineErrors = [];
        file.on('pageerror', error => offlineErrors.push(error.message));
        const fileUrl = pathToFileURL(path.join(fixture.offlineRoot, reportPath)).href;
        await file.goto(fileUrl);
        await file.locator('body').waitFor();
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
          const followed = Promise.race([
            file.waitForEvent('download').then(download => ({ download })),
            file.waitForURL(targetUrl, { waitUntil: 'commit' }).then(() => ({ navigated: true })).catch(() => new Promise(() => {})),
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
