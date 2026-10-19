const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { launchBrowser, selectFixture, startFixture, stopFixture } = require('./browser-support.cjs');

// Presentation fixtures only; real publication/outcomes are checked by artifact-archive-smoke.cjs.
async function main() {
  const fixture = await startFixture();
  let browser;
  try {
    browser = await launchBrowser();
    const context = await browser.newContext({ bypassCSP: true });
    await selectFixture(context, fixture.origin, 'loaded');
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
    let outcome = 'READY';
    const metrics = () => outcome === 'unavailable' ? null : {
      version: 1, outcome, attempts: 2, retries: 1, queueWaitMs: 0,
      writerCpuMs: null, writerAllocatedBytes: 1099511627776, sourceBytes: 0,
      zipBytes: 987654321098, compressionRatio: 1.23456,
      failurePhase: outcome === 'FAILED' ? 'VERIFY' : null,
      failureReason: outcome === 'FAILED' ? 'verification_failed' : null,
    };
    await page.route('**/api/v1/jobs/ARCHIVE-REVIEW**', route => {
      const endpoint = new URL(route.request().url()).pathname.split('ARCHIVE-REVIEW')[1];
      const job = { jobId: 'ARCHIVE-REVIEW', packageId: 'payments', command: 'load', status: 'ERROR',
        archiveMetrics: metrics(), performance: { timings: { executionMs: 0 }, worker: { sampleCount: 0 } } };
      if (endpoint === '/events') return route.fulfill({ contentType: 'text/event-stream', body: 'event: status\ndata: {"status":"ERROR"}\n\n' });
      const body = endpoint === '/result' ? { job, result: { status: 'ERROR', metrics: { completedThroughput: 0, p95Ms: 0 } } }
        : endpoint === '/artifacts' ? { items: [], primaryReports: [], archiveStatus: 'READY', archiveMetrics: metrics() } : job;
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    });
    const directory = process.env.ATT_UI_SCREENSHOT_DIR || '/tmp/att-archive-metrics';
    await fs.mkdir(directory, { recursive: true });
    for (outcome of ['READY', 'FAILED', 'unavailable']) {
      process.stdout.write(`Checking archive metrics ${outcome}\n`);
      await page.goto(`${fixture.origin}/att/ui/index.html#/jobs/ARCHIVE-REVIEW`);
      await page.reload();
      await page.getByRole('tab', { name: 'Metrics', exact: true }).click().catch(async error => { throw new Error(`${error}\n${(await page.locator('body').innerText()).slice(0, 2500)}`); });
      const section = page.getByRole('region', { name: 'Artifact archive metrics', exact: true });
      if (outcome === 'unavailable') await section.getByText('Archive metrics unavailable.', { exact: true }).waitFor();
      else {
        await section.locator('.att-status-badge').getByText(outcome, { exact: true }).waitFor();
        assert.equal(await section.locator('td[title="1099511627776 bytes"]').innerText(), '1 TB');
        assert.equal(await section.locator('td[title="0 bytes"]').innerText(), '0 B');
        assert.equal(await section.getByText('Unavailable', { exact: true }).count() > 0, true);
      }
      assert.ok(await page.getByRole('region', { name: 'Server timings', exact: true }).count());
      assert.ok(await page.getByRole('region', { name: 'Worker metrics', exact: true }).count());
      for (const [width, theme] of [[1280, 'light'], [390, 'light'], [390, 'dark']]) {
        await page.setViewportSize({ width, height: 900 });
        await page.evaluate(theme => { document.documentElement.dataset.theme = theme; window.scrollTo(0, 0); }, theme);
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
        if (outcome !== 'unavailable') {
          assert.equal(await section.locator('td').first().evaluate(cell => getComputedStyle(cell).textAlign), 'right');
          assert.equal(await section.locator('td').first().evaluate(cell => getComputedStyle(cell).fontWeight), '400');
        }
        await page.screenshot({ fullPage: true, animations: 'disabled', path: path.join(directory, `archive-${outcome}-${width}-${theme}.png`) });
      }
      await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
      const violations = await page.evaluate(async () => (await axe.run(document.querySelector('[aria-label="Artifact archive metrics"]'), {
        runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21aa'] },
      })).violations.map(item => ({ id: item.id, targets: item.nodes.map(node => node.target) })));
      assert.deepEqual(violations, []);
    }
    assert.deepEqual(errors, []);
    process.stdout.write('Archive metrics presentation passed: READY/FAILED/unavailable, zero/large values, light/dark desktop/mobile and accessibility.\n');
  } finally {
    if (browser) await browser.close();
    await stopFixture(fixture);
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
